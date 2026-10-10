/**
 * 1.2.0 Season agent v2: Seeker Season partner drops ("today's Season tasks").
 *
 * Sources, honestly:
 *  - READ by the worker: the official Solana Mobile blog (newest posts from solanamobile.com/sitemap.xml) and the
 *    docs dApp Store page. A post is sent to the model only when it is new or its text changed (dedupe by URL +
 *    SHA-256 of the text); an extracted drop must quote the page word for word or it is dropped.
 *  - CURATED: drops announced on X by @solanamobile, checked by hand with the post link and date (season-curated.js).
 *  - X: built in but OFF. It turns on with SEASON_X=1 and a paid X API bearer token (X_BEARER_TOKEN). Until then the
 *    API says x:false and the app never claims X is read.
 * Items with a deadline in the past are dropped. Refreshed by the 6-hourly cron and by a stale read (> 12 h).
 */
import { chat } from "./assistant-extras.js";
import { SITEMAP, blogUrls, pageText, sha256 } from "./season-rules.js";
import { CURATED, CURATED_CHECKED } from "./season-curated.js";

export const DROPS_KEY = "season-drops:current";
export const SEEN_KEY = "season-drops:seen";
export const DOCS_DROP_PAGES = ["https://docs.solanamobile.com/dapp-store/intro"];
export const SOLANA_MOBILE_X_ID = "1536816010375974913";
const BLOG_POSTS = 8;
const MAX_EXTRACT = 4;
const MAX_ITEMS = 16;
/** Bump when the extraction rules change: every page is read again once. */
export const DROPS_PROMPT = 2;
export const STALE_MS = 12 * 3600_000;
const UA = { "User-Agent": "SolarchikSeasonDrops/1.2 (+https://github.com/Solar-DePIN-Hub/solarchik-assistant)" };

export const SOURCES_TEXT = {
  en: "Reads the official Solana Mobile blog and docs. X support is built in and turns on with the paid API.",
  uk: "Читає офіційний блог і документацію Solana Mobile. Підтримка X вбудована й вмикається з платним API.",
};

export function xEnabled(env) {
  return env?.SEASON_X === "1" && !!env?.X_BEARER_TOKEN;
}

export const DROPS_SCHEMA = {
  name: "season_drops",
  strict: true,
  schema: {
    type: "object",
    additionalProperties: false,
    required: ["drops"],
    properties: {
      drops: {
        type: "array",
        items: {
          type: "object",
          additionalProperties: false,
          required: ["app", "perk", "perk_uk", "deadline", "link", "quote", "current", "seeker_perk"],
          properties: {
            current: { type: "boolean" },
            seeker_perk: { type: "boolean" },
            app: { type: "string" },
            perk: { type: "string" },
            perk_uk: { type: "string" },
            deadline: { type: "string" },
            link: { type: "string" },
            quote: { type: "string" },
          },
        },
      },
    },
  },
};

export function dropsSystem() {
  return [
    "You read one official Solana Mobile page (or post) and list Seeker Season partner drops: a named app or dApp that gives Seeker owners a perk (bonus, multiplier, badge, free credit, NFT, tournament, exclusive access).",
    "Only drops the text states. Never invent apps, perks, amounts, points or dates. No drops in the text: return an empty list.",
    "current: true only when the text presents the perk as available now or upcoming; false for a past or ended campaign (past tense, 'you could', a season or quest round that is over, a recap).",
    "seeker_perk: true only when Seeker owners get something specific (a bonus, multiplier, badge, credit, NFT, tournament, exclusive feature); false for a plain 'app X is available'.",
    "app: the app's name as written. perk: one short English sentence, facts only. perk_uk: the same in Ukrainian (never Russian).",
    "deadline: YYYY-MM-DD only when the text gives an end date for the perk, else empty. link: a URL from the text for the app, else empty.",
    "quote: copy 5-20 words from the text that state the perk, exactly as written.",
  ].join("\n");
}

const norm = (t) => String(t || "").toLowerCase().replace(/[’‘`]/g, "'").replace(/[“”]/g, '"').replace(/\s+/g, " ").trim();
const DATE = /^20\d\d-\d\d-\d\d$/;
const clip = (v, n) => String(v ?? "").replace(/\s+/g, " ").trim().slice(0, n);

/** Keeps a model drop only when its quote is really in the source text; cleans fields. */
export function sanitizeDrops(raw, text) {
  let o;
  try { o = typeof raw === "string" ? JSON.parse(raw) : raw; } catch { return { drops: [], failed: true }; }
  const src = norm(text);
  const out = [];
  let dropped = 0;
  for (const d of Array.isArray(o?.drops) ? o.drops : []) {
    const app = clip(d?.app, 40), perk = clip(d?.perk, 200), quote = clip(d?.quote, 300);
    if (!app || !perk || !quote || quote.split(" ").length < 4 || !src.includes(norm(quote))) { dropped++; continue; }
    if (d?.current !== true || d?.seeker_perk !== true) { dropped++; continue; } // past campaigns and plain listings are not tasks
    const link = /^https:\/\/[^\s]+$/.test(clip(d?.link, 300)) ? clip(d.link, 300) : "";
    out.push({ app, perk, perk_uk: clip(d?.perk_uk, 220), deadline: DATE.test(clip(d?.deadline, 10)) ? clip(d.deadline, 10) : "", link, quote });
  }
  return { drops: out, dropped };
}

export async function dropId(sourceUrl, app) {
  return (await sha256(String(sourceUrl) + "|" + norm(app))).slice(0, 16);
}

/** Deadline today or later, or none. `today` is YYYY-MM-DD (UTC). */
export function live(item, today) {
  return !item.deadline || item.deadline >= today;
}

export async function curatedItems() {
  const out = [];
  for (const c of CURATED) out.push({ id: await dropId(c.sourceUrl, c.app), ...c, origin: "curated", checked: CURATED_CHECKED });
  return out;
}

async function getText(url, fetcher) {
  const r = await fetcher(url, { headers: UA, signal: AbortSignal.timeout(12000) });
  if (!r.ok) throw new Error(url + " HTTP " + r.status);
  return r.text();
}

async function extract(env, url, title, text) {
  const out = await chat(env, {
    temperature: 0,
    max_tokens: 1500,
    response_format: { type: "json_schema", json_schema: DROPS_SCHEMA },
    messages: [
      { role: "system", content: dropsSystem() },
      { role: "user", content: `URL: ${url}\nTITLE: ${title}\n\nTEXT:\n${String(text).slice(0, 12000)}` },
    ],
  }, 25000);
  if (!out.text) return { drops: [], failed: true };
  return sanitizeDrops(out.text, text);
}

/** Disabled by default. With SEASON_X=1 + X_BEARER_TOKEN: recent @solanamobile posts that mention Seeker Season. */
export async function xPosts(env, fetcher = fetch) {
  if (!xEnabled(env)) return [];
  const u = `https://api.x.com/2/users/${SOLANA_MOBILE_X_ID}/tweets?max_results=20&exclude=replies,retweets&tweet.fields=created_at`;
  const r = await fetcher(u, { headers: { Authorization: "Bearer " + env.X_BEARER_TOKEN }, signal: AbortSignal.timeout(10000) });
  if (!r.ok) throw new Error("x HTTP " + r.status);
  const j = await r.json();
  return (j.data || []).filter((p) => /seeker season|seekers get/i.test(p.text || "")).map((p) => ({
    url: `https://x.com/solanamobile/status/${p.id}`, title: "@solanamobile on X", text: p.text, published: String(p.created_at || "").slice(0, 10),
  }));
}

/**
 * One refresh. deps: { kv, fetcher, extractor, now }. Only new or changed sources go to the model (at most
 * MAX_EXTRACT a run); returns { drops, newItems, checked, extracted, errors }.
 */
export async function checkDrops(env, deps = {}) {
  const kv = deps.kv || env.BALANCES;
  const fetcher = deps.fetcher || fetch;
  const extractor = deps.extractor || ((u, t, x) => extract(env, u, t, x));
  const now = deps.now || Date.now();
  const today = new Date(now).toISOString().slice(0, 10);
  const prev = (await kv.get(DROPS_KEY, "json").catch(() => null)) || { items: [] };
  const seen = (await kv.get(SEEN_KEY, "json").catch(() => null)) || {};
  const errors = [];
  const pages = [];
  try {
    for (const url of blogUrls(await getText(SITEMAP, fetcher), BLOG_POSTS)) pages.push({ url, origin: "blog" });
  } catch (e) { errors.push(String(e.message || e)); }
  for (const url of DOCS_DROP_PAGES) pages.push({ url, origin: "docs" });
  let xs = [];
  try { xs = await xPosts(env, fetcher); } catch (e) { errors.push(String(e.message || e)); }

  const found = [];
  let extracted = 0;
  const handle = async (url, origin, title, text, published) => {
    const hash = (await sha256(text)) + ":" + DROPS_PROMPT;
    if (seen[url] === hash) return; // already read this exact text
    if (extracted >= MAX_EXTRACT) return; // next run
    extracted++;
    const x = await extractor(url, title, text);
    if (x.failed) { errors.push(url + " extraction failed"); return; }
    seen[url] = hash;
    for (const d of x.drops) found.push({ id: await dropId(url, d.app), app: d.app, perk: d.perk, perk_uk: d.perk_uk, deadline: d.deadline, link: d.link, sourceUrl: url, sourceDate: published || "", origin, quote: d.quote, foundAt: now, prompt: DROPS_PROMPT });
  };
  for (const p of pages) {
    try {
      const { title, text, published } = pageText(await getText(p.url, fetcher));
      if (text.length < 200) throw new Error(p.url + " too little text");
      await handle(p.url, p.origin, title, text, published);
    } catch (e) { errors.push(String(e.message || e)); }
  }
  for (const p of xs) {
    try { await handle(p.url, "x", p.title, p.text, p.published); } catch (e) { errors.push(String(e.message || e)); }
  }

  const curated = await curatedItems();
  const curatedApps = new Set(curated.map((c) => norm(c.app)));
  const byId = new Map();
  // a page read with older rules is dropped with its items (they are re-read); an app in the curated list keeps that entry
  const keepPrev = (prev.items || []).filter((i) => i.origin !== "curated" && (i.prompt || 1) === DROPS_PROMPT);
  for (const it of [...curated, ...[...keepPrev, ...found].filter((i) => !curatedApps.has(norm(i.app)))]) {
    const old = byId.get(it.id);
    byId.set(it.id, old ? { ...old, ...it, foundAt: old.foundAt || it.foundAt } : it);
  }
  const prevIds = new Set((prev.items || []).map((i) => i.id));
  const seenApp = new Set();
  const items = [...byId.values()].filter((i) => live(i, today))
    .sort((a, b) => String(b.sourceDate).localeCompare(String(a.sourceDate)))
    .filter((i) => { const k = norm(i.app); if (seenApp.has(k)) return false; seenApp.add(k); return true; }) // one entry per app: the newest
    .slice(0, MAX_ITEMS);
  const newItems = items.filter((i) => !prevIds.has(i.id)).length;
  const drops = { ok: true, items, checkedAt: now, x: xEnabled(env), sources: { blog: true, docs: true, curated: CURATED_CHECKED, x: xEnabled(env) }, errors };
  await kv.put(DROPS_KEY, JSON.stringify(drops));
  await kv.put(SEEN_KEY, JSON.stringify(seen));
  return { drops, newItems, checked: pages.length + xs.length, extracted, errors };
}

/** What the app reads: items for today (deadline passed → gone), the language's perk text and the source line. */
export function dropsView(drops, lang, now = Date.now()) {
  const today = new Date(now).toISOString().slice(0, 10);
  const uk = lang === "uk";
  const items = (drops?.items || []).filter((i) => live(i, today)).map((i) => ({
    id: i.id, app: i.app, perk: (uk && i.perk_uk) || i.perk, deadline: i.deadline || "", link: i.link || "",
    sourceUrl: i.sourceUrl, sourceDate: i.sourceDate || "", origin: i.origin, via: i.via || "", checked: i.checked || "",
  }));
  return { ok: true, items, checkedAt: drops?.checkedAt || 0, x: !!drops?.x, sourcesText: SOURCES_TEXT[uk ? "uk" : "en"], curatedChecked: CURATED_CHECKED };
}

export async function seasonDropsRoute(env, request, json, ctx) {
  const url = new URL(request.url);
  if (request.method !== "GET" || url.pathname !== "/season/drops") return null;
  const lang = url.searchParams.get("lang") === "uk" ? "uk" : "en";
  let cur = await env.BALANCES.get(DROPS_KEY, "json").catch(() => null);
  if (!cur) {
    // first read: the curated list straight away, a real refresh in the background
    cur = { items: await curatedItems(), checkedAt: 0, x: xEnabled(env) };
  }
  if (ctx && env.OPENAI_API_KEY && Date.now() - (cur.checkedAt || 0) > STALE_MS) {
    const lock = await env.BALANCES.get("season-drops:lock").catch(() => null);
    if (!lock) {
      await env.BALANCES.put("season-drops:lock", "1", { expirationTtl: 300 }).catch(() => {});
      ctx.waitUntil(checkDrops(env).catch((e) => console.log(JSON.stringify({ event: "season_drops_fail", error: String(e) }))));
    }
  }
  return json(dropsView(cur, lang));
}
