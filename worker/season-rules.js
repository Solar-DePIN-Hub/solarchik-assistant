// 1.1.0 Season rules watcher (assistant app only).
//   Cron (every 6 h) and POST /season/rules/check read OFFICIAL Solana Mobile sources only:
//     - solanamobile.com/sitemap.xml -> the newest blog posts (the sitemap lists them newest first; there is no RSS)
//     - docs.solanamobile.com SKR / Seeker pages
//   X @solanamobile is NOT read: there is no free, legal way to read it without logging in, so it is skipped.
//   A page whose text changed (sha-256) goes to the model with a strict JSON schema: "scoring signals" (what counts,
//   what is favoured or devalued, campaigns with dates, featured dApps), each with a verbatim quote. A signal is kept
//   only if its quote really appears in the page text and the model is confident; nothing is invented or guessed.
//   The result is versioned JSON in KV (season-rules:current, season-rules:v<N>). GET /season/rules serves it.
//   The phone decides what to apply: anything that would raise spending or add a new action needs the user's OK.

import { chat } from "./assistant-extras.js";

export const RULES_KEY = "season-rules:current";
export const SITEMAP = "https://solanamobile.com/sitemap.xml";
export const DOCS_PAGES = ["https://docs.solanamobile.com/solana-mobile-stack/skr"];
export const SKIPPED = [{ source: "x.com/solanamobile", reason: "no free, legal source without login; not read" }];
const BLOG_POSTS = 4;
const MAX_EXTRACT = 3;
const MIN_CONFIDENCE = 0.6;
/** Bumped when the extraction prompt changes, so every source is read again once. */
export const PROMPT_VERSION = 4;
const MANUAL_EVERY_MS = 5 * 60_000;
export const KINDS = ["favored", "devalued", "counts", "campaign", "featured_dapp", "info"];
export const ACTIONS = ["daily_use", "wallet_activity", "checkin", "swap", "dapp", "staking", "quest", "nft", "other"];

const UA = { "User-Agent": "SolarchikSeasonRules/1.1 (+https://github.com/Solar-DePIN-Hub/solarchik-assistant)" };

/** Official blog post URLs, newest first, from the sitemap. */
export function blogUrls(sitemapXml, n = BLOG_POSTS) {
  const out = [];
  for (const m of String(sitemapXml).matchAll(/<loc>\s*([^<\s]+)\s*<\/loc>/g)) {
    const u = m[1].trim();
    if (/^https:\/\/solanamobile\.com\/blog\/[^/]+$/.test(u) && !out.includes(u)) out.push(u);
    if (out.length >= n) break;
  }
  return out;
}

function decode(t) {
  return t
    .replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"').replace(/&#x27;|&#39;|&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(+d)).replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)));
}

/** Title + readable text of a page (the <article> when there is one), whitespace-normalised. */
export function pageText(html) {
  let s = String(html);
  const title = decode((s.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1] || "").replace(/\s+/g, " ").trim()).slice(0, 160);
  const a = s.indexOf("<article"), b = s.lastIndexOf("</article>");
  if (a >= 0 && b > a) s = s.slice(a, b);
  else {
    const m = s.indexOf("<main"), e = s.lastIndexOf("</main>");
    if (m >= 0 && e > m) s = s.slice(m, e);
  }
  s = s.replace(/<(script|style|svg|noscript|nav|footer)[\s\S]*?<\/\1>/gi, " ").replace(/<[^>]+>/g, " ");
  const text = decode(s).replace(/[\u200b-\u200d\ufeff]/g, "").replace(/\s+/g, " ").trim();
  return { title, text: text.slice(0, 20000), published: publishedOf(text) || publishedOf(String(html).slice(Math.max(0, String(html).indexOf("<h1")))) };
}

/** First "Mon D, YYYY" date in the text, as YYYY-MM-DD. */
export function publishedOf(text) {
  const m = String(text).match(/\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]* (\d{1,2}), (20\d\d)\b/);
  if (!m) return "";
  const mm = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"].indexOf(m[1]) + 1;
  return `${m[3]}-${String(mm).padStart(2, "0")}-${m[2].padStart(2, "0")}`;
}

export async function sha256(t) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(t));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export const SIGNALS_SCHEMA = {
  name: "season_signals",
  strict: true,
  schema: {
    type: "object",
    additionalProperties: false,
    required: ["relevant", "summary", "summary_uk", "signals"],
    properties: {
      relevant: { type: "boolean" },
      summary: { type: "string" },
      summary_uk: { type: "string" },
      signals: {
        type: "array",
        items: {
          type: "object",
          additionalProperties: false,
          required: ["kind", "action", "dapp", "start", "end", "text", "text_uk", "quote", "confidence"],
          properties: {
            kind: { type: "string", enum: KINDS },
            action: { type: "string", enum: ACTIONS },
            dapp: { type: "string" },
            start: { type: "string" },
            end: { type: "string" },
            text: { type: "string" },
            text_uk: { type: "string" },
            quote: { type: "string" },
            confidence: { type: "number" },
          },
        },
      },
    },
  },
};

export function signalsSystem() {
  return [
    "You read one official Solana Mobile page and extract only what it says about Seeker Season scoring or Seeker activity: which kinds of user activity count, are favoured or are devalued, campaigns or quests with dates, and dApps that are featured for Seekers.",
    "kind: favored (counts more / is encouraged), devalued (counts less / discouraged), counts (explicitly counts), campaign (a campaign, round or quest period; put dates in start/end as YYYY-MM-DD when the page gives them, else empty), featured_dapp (a dApp named for Seekers; put its name in dapp), info (other relevant fact).",
    "action: daily_use, wallet_activity (everyday wallet use, onchain activity), checkin, swap, dapp (opening/using dApps), staking (SKR staking), quest, nft, other.",
    "quote: copy 5 to 30 words VERBATIM from the page that support the signal. text: one short plain sentence in English. confidence: 0..1, how clearly the page states it.",
    "Most important: any statement about how Season or onchain activity is scored or weighted (what counts more or less). Developer hackathons, grants and prizes are not user activity: use kind info for them. Give at most 8 signals, most important first.",
    "Use ONLY the page. Never guess or add rules from general knowledge. If the page says nothing about Season scoring or Seeker activity, return relevant=false and no signals. summary: at most two short sentences about what the page says for Seeker users (empty if not relevant).",
    "summary_uk and text_uk: the same as summary and text, in natural informal Ukrainian (ти-form). Keep app and product names as they are.",
  ].join("\n");
}

/** Clips at a sentence end when possible, never mid-word. */
export function clipSentences(t, n) {
  const s = String(t || "").replace(/\s+/g, " ").trim();
  if (s.length <= n) return s;
  const cut = s.slice(0, n);
  const end = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "));
  if (end > n * 0.4) return cut.slice(0, end + 1);
  return cut.slice(0, cut.lastIndexOf(" ")).replace(/[,;:]$/, "") + "…";
}

const norm = (t) => String(t).toLowerCase().replace(/[’‘`]/g, "'").replace(/[“”]/g, '"').replace(/\s+/g, " ").trim();
const DATE = /^20\d\d-\d\d-\d\d$/;

/** Keeps a signal only when its quote is really on the page and the model is confident. */
export function sanitizeSignals(raw, text) {
  const page = norm(text);
  let o;
  try { o = typeof raw === "string" ? JSON.parse(raw) : raw; } catch { return { relevant: false, summary: "", signals: [], dropped: 0, failed: true }; }
  if (!o || typeof o !== "object") return { relevant: false, summary: "", signals: [], dropped: 0, failed: true };
  const sigs = Array.isArray(o?.signals) ? o.signals : [];
  const kept = [];
  let dropped = 0;
  for (const s of sigs.slice(0, 12)) {
    const quote = String(s?.quote || "").replace(/\s+/g, " ").trim().slice(0, 300);
    const ok = KINDS.includes(s?.kind) && ACTIONS.includes(s?.action) && quote.split(" ").length >= 4 &&
      page.includes(norm(quote)) && Number(s?.confidence) >= MIN_CONFIDENCE;
    if (!ok) { dropped++; continue; }
    // developer hackathons, grants and prizes are not user activity: never a scoring signal, at most a note
    const dev = /\b(hackathon|prize|grant|judg|submission)/i.test(quote + " " + (s.text || ""));
    kept.push({
      kind: dev && s.kind !== "campaign" ? "info" : s.kind, action: s.action, dapp: String(s.dapp || "").slice(0, 40),
      start: DATE.test(s.start) ? s.start : "", end: DATE.test(s.end) ? s.end : "",
      text: clipSentences(s.text, 200), text_uk: clipSentences(s.text_uk, 220), quote,
      confidence: Math.round(Number(s.confidence) * 100) / 100,
    });
  }
  const relevant = o?.relevant === true && kept.length > 0;
  return { relevant, summary: relevant ? clipSentences(o?.summary, 320) : "", summary_uk: relevant ? clipSentences(o?.summary_uk, 360) : "", signals: relevant ? kept : [], dropped };
}

async function getText(url, fetcher) {
  const r = await fetcher(url, { headers: UA, signal: AbortSignal.timeout(12000) });
  if (!r.ok) throw new Error(url + " HTTP " + r.status);
  return r.text();
}

async function extract(env, url, title, text) {
  const out = await chat(env, {
    temperature: 0,
    max_tokens: 2000,
    response_format: { type: "json_schema", json_schema: SIGNALS_SCHEMA },
    messages: [
      { role: "system", content: signalsSystem() },
      { role: "user", content: `URL: ${url}\nTITLE: ${title}\n\nPAGE:\n${text.slice(0, 14000)}` },
    ],
  }, 25000);
  return { ...sanitizeSignals(out.text, text), model: out.model };
}

/** Flattened view the app reads: every kept signal with its source. */
export function flatten(sources) {
  const out = [];
  for (const s of sources) for (const g of s.signals || []) out.push({ ...g, url: s.url, title: s.title, published: s.published });
  return out;
}

/**
 * One check. deps: { kv, fetcher, extractor, now }. Returns { rules, changed, checked, extracted, errors }.
 * A source is re-read by the model only when its text hash changed; at most MAX_EXTRACT per run.
 */
export async function checkRules(env, deps = {}) {
  const kv = deps.kv || env.BALANCES;
  const fetcher = deps.fetcher || fetch;
  const extractor = deps.extractor || ((u, t, x) => extract(env, u, t, x));
  const now = deps.now || Date.now();
  const prev = (await kv.get(RULES_KEY, "json").catch(() => null)) || { version: 0, sources: [], signals: [] };
  const errors = [];
  let urls = [];
  try { urls = blogUrls(await getText(SITEMAP, fetcher)); } catch (e) { errors.push(String(e.message || e)); }
  urls = [...urls, ...DOCS_PAGES];
  const byUrl = new Map((prev.sources || []).map((s) => [s.url, s]));
  const sources = [];
  let extracted = 0;
  let changed = false;
  for (const url of urls) {
    const old = byUrl.get(url);
    try {
      const { title, text, published } = pageText(await getText(url, fetcher));
      if (text.length < 200) throw new Error(url + " too little text");
      const hash = await sha256(text);
      if (old && old.hash === hash && old.prompt === PROMPT_VERSION) { sources.push({ ...old, checkedAt: now }); continue; }
      if (extracted >= MAX_EXTRACT) { if (old) sources.push(old); continue; }
      extracted++;
      const x = await extractor(url, title, text);
      if (x.failed) { errors.push(url + " extraction failed; kept the previous reading"); if (old) sources.push(old); continue; }
      const next = { url, title, published, hash, prompt: PROMPT_VERSION, checkedAt: now, changedAt: now, relevant: x.relevant, summary: x.summary, summary_uk: x.summary_uk || "", signals: x.signals, dropped: x.dropped || 0, model: x.model || "" };
      if (!old || JSON.stringify(old.signals) !== JSON.stringify(next.signals) || old.summary !== next.summary) changed = true;
      sources.push(next);
    } catch (e) {
      errors.push(String(e.message || e));
      if (old) sources.push(old);
    }
  }
  if ((prev.sources || []).some((s) => !urls.includes(s.url))) changed = true; // a post left the newest list
  const rules = {
    ok: true,
    version: changed ? (prev.version || 0) + 1 : prev.version || 0,
    updatedAt: changed ? now : prev.updatedAt || 0,
    checkedAt: now,
    sources,
    signals: flatten(sources),
    skipped: SKIPPED,
    errors,
  };
  if (rules.version === 0 && sources.length) { rules.version = 1; rules.updatedAt = now; changed = true; }
  await kv.put(RULES_KEY, JSON.stringify(rules));
  if (changed) await kv.put("season-rules:v" + rules.version, JSON.stringify(rules));
  return { rules, changed, checked: urls.length, extracted, errors };
}

export async function seasonRulesRoute(env, request, rateOk, json) {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/season/rules") {
    const cur = await env.BALANCES.get(RULES_KEY, "json").catch(() => null);
    return json(cur || { ok: true, version: 0, sources: [], signals: [], skipped: SKIPPED });
  }
  if (request.method === "POST" && url.pathname === "/season/rules/check") {
    if (!rateOk(request.headers.get("cf-connecting-ip"))) return json({ ok: false, error: "rate" }, 429);
    if (!env.OPENAI_API_KEY) return json({ ok: false, error: "no model key" }, 503);
    const cur = await env.BALANCES.get(RULES_KEY, "json").catch(() => null);
    if (cur && Date.now() - (cur.checkedAt || 0) < MANUAL_EVERY_MS) return json({ ...cur, throttled: true });
    const r = await checkRules(env);
    return json({ ...r.rules, changed: r.changed, extracted: r.extracted });
  }
  return null;
}
