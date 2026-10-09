import { MINT_CLUSTER, MINT_TREASURY, PRO_LAMPORTS, mintConfig, mintCosign, verifyMint } from "./agent-mint.js";
import { briefingRoute, callActionsRoute } from "./assistant-extras.js";
import { checkRules, seasonRulesRoute } from "./season-rules.js";

const SESSION_USD = 0.2;

/**
 * Paid credit only. A top-up is a mainnet USDC transfer to PAY_WALLET made from the Solana Pay link the
 * apps build (src/lib/game/pay.ts payUrl): amount, reference key and memo = userId.slice(0, 32).
 * /topup {userId, sig} or {userId, ref} credits exactly the USDC that reached PAY_WALLET, once per
 * signature. Before 2026-10-02 /topup added $5 to any userId with no payment at all.
 */
const PAY_WALLET = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic";
const USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
const MEMO_PROGRAMS = new Set(["MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr", "Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo"]);
const MIN_USD = 1;
const MAX_USD = 100;
const MAX_AGE_SEC = 30 * 24 * 3600;
const B58 = /^[1-9A-HJ-NP-Za-km-z]+$/;

const VOICE = `You are Solarchik, a short solar-powered secretary.
Greet once. Ask only the caller's name and what they wanted. Never ask for a company, job, or anything else personal.
Use the caller's number as the callback; ask for another number only if they offer one.
Keep spoken answers under 20 words. Warm, a bit cheeky, never rude.
Never give wallets, seeds, passwords, or home address.
If spam or scam, refuse and end the call.
When you have name plus reason, confirm once and say the owner will see the note, then say goodbye.
In Ukrainian, address the caller with polite «ви» every time (you speak for the owner to someone you do not know): «Як вас звати і що ви хотіли передати?». Never switch to «ти» mid-call; «ви хотіли» also avoids guessing the caller's gender.`;

const NOTE_RULE = "\nBefore goodbye, call the save_call_note tool once with what you learned.";

/**
 * Live call 2 Oct (…qjb3): the model said goodbye without ever calling the tool. The note step is now spelled
 * out as the hard rule of the call, ahead of the language line; NOTE_RULE stays the last line.
 */
export const NOTE_FIRST = `NOTE TOOL (most important rule): you have the tool save_call_note.
Call it as soon as you know what the caller wants, even without a name (pass what you have; callback = the caller's number unless they gave another).
Always call save_call_note BEFORE you say goodbye, and before ending a spam call too. Never finish a call without it.
Call it once; if the caller adds something important afterwards, call it again with the full note. After it answers "Saved", say a short goodbye.`;

const SYSTEM = `You are Solarchik, the secretary in the player's cabinet.
Speak short. Warm. Under 40 words.
Ask who is calling and why. Never give wallet, address, codes, family.
Spam: end fast. Real call: ask only name and what they want. Never ask for a company.
When you have enough, last line exactly:
SUMMARY_JSON={"caller_name":"...","callback":"...","intent":"...","urgency":"low|medium|high","spam_risk":"low|medium|high","action":"callback|ignore|block","notes":"..."}`;

/** Standard Webhooks tolerance (OpenAI signs webhooks this way): reject timestamps older or newer than 5 min. */
export const WEBHOOK_TOLERANCE_SEC = 300;

const enc = new TextEncoder();

function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function bytesToB64(bytes) {
  let bin = "";
  for (const b of new Uint8Array(bytes)) bin += String.fromCharCode(b);
  return btoa(bin);
}

/** Constant-time string compare (both sides hashed first, so length does not leak either). */
async function sameSecret(a, b) {
  const [x, y] = await Promise.all([crypto.subtle.digest("SHA-256", enc.encode(String(a))), crypto.subtle.digest("SHA-256", enc.encode(String(b)))]);
  const u = new Uint8Array(x);
  const v = new Uint8Array(y);
  let diff = 0;
  for (let i = 0; i < u.length; i++) diff |= u[i] ^ v[i];
  return diff === 0;
}

/**
 * Standard Webhooks verification (webhook-id, webhook-timestamp, webhook-signature), as used by OpenAI.
 * secret: "whsec_<base64>" (the prefix is optional). Signed content: `${id}.${timestamp}.${rawBody}`,
 * HMAC-SHA256, base64; the header carries one or more space-separated "v1,<sig>" entries.
 * Returns "" when valid, else a short reason.
 */
export async function webhookProblem(secret, headers, rawBody, nowSec = Math.floor(Date.now() / 1000)) {
  const id = headers.get("webhook-id") || "";
  const ts = headers.get("webhook-timestamp") || "";
  const sigHeader = headers.get("webhook-signature") || "";
  if (!id || !ts || !sigHeader) return "missing_headers";
  if (!/^\d{1,12}$/.test(ts)) return "bad_timestamp";
  if (Math.abs(nowSec - Number(ts)) > WEBHOOK_TOLERANCE_SEC) return "stale_timestamp";
  let keyBytes;
  try {
    keyBytes = b64ToBytes(String(secret).replace(/^whsec_/, ""));
  } catch {
    return "bad_secret";
  }
  const key = await crypto.subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = bytesToB64(await crypto.subtle.sign("HMAC", key, enc.encode(`${id}.${ts}.${rawBody}`)));
  for (const part of sigHeader.split(" ")) {
    const [version, sig] = part.split(",");
    if (version === "v1" && sig && (await sameSecret(sig, mac))) return "";
  }
  return "bad_signature";
}

/**
 * /expect and /voicemail write to the inbox. No app calls them (the Android app and the web desk only read
 * /inbox), so they take the operator token: `Authorization: Bearer <SECRETARY_TOKEN>`. Without the secret
 * set on the worker they stay closed.
 */
export async function operatorOk(env, request) {
  const token = String(env.SECRETARY_TOKEN || "");
  if (!token) return false;
  const auth = request.headers.get("authorization") || "";
  const m = /^Bearer\s+(.+)$/i.exec(auth.trim());
  return Boolean(m) && (await sameSecret(m[1], token));
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    },
  });
}

async function getUsd(env, userId) {
  const raw = await env.BALANCES.get(userId);
  return Number(raw || 0);
}

async function setUsd(env, userId, usd) {
  const next = Math.round(usd * 100) / 100;
  await env.BALANCES.put(userId, String(next));
  return next;
}

function parseSummary(text) {
  const match = String(text || "").match(/SUMMARY_JSON=(\{[\s\S]*\})/);
  if (!match) return null;
  try {
    const o = JSON.parse(match[1]);
    if (o && typeof o === "object") delete o.company; // the secretary never collects a company
    return o;
  } catch {
    return null;
  }
}

async function secretary(env, text) {
  const res = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: {
      Authorization: "Bearer " + env.OPENAI_API_KEY,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: "gpt-4o-mini",
      temperature: 0.4,
      max_tokens: 220,
      messages: [
        { role: "system", content: SYSTEM },
        { role: "user", content: text + "\n\nOutput SUMMARY_JSON now." },
      ],
    }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error?.message || "openai " + res.status);
  const reply = data.choices?.[0]?.message?.content?.trim() || "";
  return { reply, summary: parseSummary(reply) };
}

/**
 * Mainnet RPCs tried in order (tested live from a Worker, 2026-10-02 ~24:00 Kyiv, `GET /rpc-health` repeats it):
 * - api.mainnet-beta.solana.com answers HTTP 403 "Your IP or provider is blocked" to Cloudflare egress (fails in
 *   ~15 ms, so it costs nothing to ask it first). The live "rpc 403" was this host: publicnode then answered an
 *   empty history and the error text of the first host was all the app saw.
 * - solana-rpc.publicnode.com: 200 from Workers, fast (~20 ms), but keeps only recent history: older signatures
 *   come back as [] / null. A top-up is checked minutes after paying, which it covers.
 * - public.rpc.solanavibestation.com: full history but rate-limited (429 on bursts).
 * - rpc.solanatracker.io/public: 200, recent history only.
 * SOLANA_RPC (secret or var, e.g. a keyed Helius URL) always goes first. An empty answer to a history lookup
 * ([] or null) moves on to the next RPC, so a pruned node cannot hide a real payment.
 */
export const RPCS = [
  "https://api.mainnet-beta.solana.com", // 403 from Workers in ~15 ms today; kept first as the canonical endpoint
  "https://solana-rpc.publicnode.com",
  "https://public.rpc.solanavibestation.com",
  "https://rpc.solanatracker.io/public",
];
const HISTORY = new Set(["getSignaturesForAddress", "getTransaction"]);

export function rpcUrls(env) {
  return [...new Set([env?.SOLANA_RPC, ...RPCS].filter(Boolean))];
}

async function rpc(env, method, params) {
  let last = "rpc";
  let empty;
  let sawEmpty = false;
  for (const url of rpcUrls(env)) {
    try {
      const res = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
        signal: AbortSignal.timeout(8000),
      });
      const j = await res.json().catch(() => ({}));
      if (res.ok && !j.error && "result" in j) {
        const r = j.result;
        const isEmpty = r == null || (Array.isArray(r) && r.length === 0);
        if (!(HISTORY.has(method) && isEmpty)) return r;
        empty = r;
        sawEmpty = true;
        last = "empty";
      } else last = "rpc " + (j.error?.code ?? res.status);
    } catch (e) {
      last = "rpc " + (e?.name || "error");
    }
    console.log(JSON.stringify({ event: "rpc_fail", host: url === env?.SOLANA_RPC ? "env" : new URL(url).host, method, detail: last }));
  }
  if (sawEmpty) return empty;
  throw new Error(last);
}

/** GET /rpc-health: which RPC answers from this Worker right now (no keys: SOLANA_RPC shows as "env"). */
async function rpcHealth(env) {
  const out = [];
  for (const url of rpcUrls(env)) {
    const t = Date.now();
    let status = "";
    let history = null;
    try {
      const res = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "getSignaturesForAddress", params: [PAY_WALLET, { limit: 3 }] }),
        signal: AbortSignal.timeout(6000),
      });
      const j = await res.json().catch(() => ({}));
      status = j.error ? "rpc " + (j.error.code ?? res.status) : String(res.status);
      if (Array.isArray(j.result)) history = j.result.length;
    } catch (e) {
      status = e?.name || "error";
    }
    out.push({ host: url === env?.SOLANA_RPC ? "env" : new URL(url).host, status, history, ms: Date.now() - t });
  }
  return json({ wallet: PAY_WALLET, rpcs: out });
}

function keyOf(k) {
  return typeof k === "string" ? k : k && typeof k === "object" ? String(k.pubkey || "") : "";
}

/** Memo strings in a jsonParsed transaction (top-level and inner instructions, plus the memo log line). */
export function memosOf(tx) {
  const out = [];
  const visit = (ix) => {
    if (!ix) return;
    if (ix.program === "spl-memo" || MEMO_PROGRAMS.has(ix.programId)) {
      if (typeof ix.parsed === "string") out.push(ix.parsed);
    }
  };
  for (const ix of tx?.transaction?.message?.instructions || []) visit(ix);
  for (const inner of tx?.meta?.innerInstructions || []) for (const ix of inner.instructions || []) visit(ix);
  for (const line of tx?.meta?.logMessages || []) {
    const m = /^Program log: Memo \(len \d+\): "(.*)"$/.exec(line);
    if (m) out.push(m[1]);
  }
  return out;
}

/** USDC that reached PAY_WALLET in this transaction, in dollars (post - pre over PAY_WALLET-owned USDC accounts). */
export function usdcIn(tx, mint = USDC_MINT, wallet = PAY_WALLET) {
  const sum = (rows) =>
    (rows || []).reduce((s, r) => (r?.mint === mint && r?.owner === wallet ? s + (Number(r.uiTokenAmount?.amount) || 0) : s), 0);
  return (sum(tx?.meta?.postTokenBalances) - sum(tx?.meta?.preTokenBalances)) / 1e6;
}

/** Why this transaction cannot pay for userId, or "" when it can. */
export function payProblem(tx, userId, nowSec, ref = "", mint = USDC_MINT, wallet = PAY_WALLET) {
  if (!tx || !tx.meta) return "not_found";
  if (tx.meta.err) return "failed_tx";
  if (tx.blockTime && nowSec - tx.blockTime > MAX_AGE_SEC) return "too_old";
  if (ref && !(tx.transaction?.message?.accountKeys || []).map(keyOf).includes(ref)) return "wrong_reference";
  const usd = usdcIn(tx, mint, wallet);
  if (!(usd >= MIN_USD - 0.000001)) return "no_usdc_to_treasury";
  if (usd > MAX_USD + 0.000001) return "amount_too_large";
  if (!memosOf(tx).some((m) => m.trim() === userId.slice(0, 32))) return "memo_mismatch";
  return "";
}

async function topup(env, body) {
  const userId = String(body?.userId || "").trim();
  const sig = String(body?.sig || "").trim();
  const ref = String(body?.ref || "").trim();
  if (userId.length < 8 || userId.length > 80 || /\s/.test(userId)) return json({ error: "userId required" }, 400);
  const sigOk = sig.length >= 64 && sig.length <= 90 && B58.test(sig);
  const refOk = ref.length >= 32 && ref.length <= 44 && B58.test(ref);
  if (!sigOk && !refOk) return json({ error: "PAYMENT_REQUIRED", detail: "send sig (USDC transfer signature) or ref (Solana Pay reference)" }, 402);

  let sigs = [sig];
  if (!sigOk) {
    try {
      const rows = await rpc(env, "getSignaturesForAddress", [ref, { limit: 10, commitment: "confirmed" }]);
      sigs = (Array.isArray(rows) ? rows : []).filter((r) => !r.err).map((r) => r.signature);
    } catch (e) {
      return json({ error: "RPC_UNAVAILABLE", detail: String(e?.message || e) }, 503);
    }
    if (!sigs.length) return json({ error: "PAYMENT_NOT_FOUND", detail: "no transaction with this reference yet" }, 402);
  }

  // USDC_MINT override exists only for the local devnet test (wrangler dev); production leaves it unset.
  const mint = env.USDC_MINT || USDC_MINT;
  const now = Math.floor(Date.now() / 1000);
  let last = "not_found";
  for (const s of sigs.slice(0, 10)) {
    if (await env.BALANCES.get("paid:" + s)) {
      last = "already_used";
      continue;
    }
    let tx;
    try {
      tx = await rpc(env, "getTransaction", [s, { encoding: "jsonParsed", maxSupportedTransactionVersion: 0, commitment: "confirmed" }]);
    } catch (e) {
      return json({ error: "RPC_UNAVAILABLE", detail: String(e?.message || e) }, 503);
    }
    const problem = payProblem(tx, userId, now, sigOk ? "" : ref, mint);
    if (problem) {
      last = problem;
      continue;
    }
    const added = Math.floor(usdcIn(tx, mint) * 100) / 100;
    // Mark the signature spent before crediting, so a retry can never credit it twice.
    await env.BALANCES.put("paid:" + s, JSON.stringify({ userId, usd: added, at: Date.now() }));
    const usd = await setUsd(env, userId, (await getUsd(env, userId)) + added);
    return json({ userId, usd, added, sig: s });
  }
  if (last === "already_used") return json({ error: "ALREADY_USED" }, 409);
  return json({ error: "PAYMENT_INVALID", detail: last }, 402);
}

// ---- Incoming phone calls (Zadarma number -> OpenAI SIP -> realtime.call.incoming -> /sip) ----

/** The secretary's own line. Calls diverted to it map to the owner/demo account unless KV maps them. */
export const DEFAULT_NUMBER = "+380914810885";

/**
 * One phone number as E.164. plus=true when the source had a leading "+". Ukrainian local numbers
 * (0XXXXXXXXX) become +380XXXXXXXXX; 380XXXXXXXXX without "+" gets it; 00-prefixed international loses 00.
 */
export function normNumber(raw, plus = false) {
  let d = String(raw || "").replace(/\D/g, "");
  if (!plus && d.startsWith("00")) d = d.slice(2);
  if (!plus && /^0\d{9}$/.test(d)) return "+38" + d;
  if (d.length < 8 || d.length > 15 || d.startsWith("0")) return "";
  return "+" + d;
}

/**
 * Every phone number in one SIP header value, in order: user parts of sip:/sips:/tel: URIs
 * ("<sip:380914810885;user=phone@x>", "tel:+380-91-481-08-85", URL-encoded %2B), a quoted all-digit display
 * name, or a value that is only a number ("+380 91 481-08-85", "0914810885"). Dots are not number separators,
 * so Call-IDs, IPs and timestamps with dots are never read as numbers.
 */
export function numbersIn(value) {
  const v = String(value || "").replace(/%2B/gi, "+");
  const out = [];
  const add = (n) => n && !out.includes(n) && out.push(n);
  for (const m of v.matchAll(/(?:sips?|tel):(\+?)([\d()\- ]{6,24})(?=[@;>,?\s"]|$)/gi)) add(normNumber(m[2], m[1] === "+"));
  for (const m of v.matchAll(/"\s*(\+?)(\d[\d\s()-]{6,22}\d)\s*"/g)) add(normNumber(m[2], m[1] === "+"));
  const whole = /^\s*<?\s*(\+?)(\d[\d\s()-]{6,22}\d)\s*>?\s*$/.exec(v);
  if (whole) add(normNumber(whole[2], whole[1] === "+"));
  return out;
}

/** "+380 91 481-08-85", "sip:+380914810885@x", "<tel:380914810885>;reason=unconditional", "0914810885" -> "+380914810885". */
export function e164(raw) {
  return numbersIn(raw)[0] || "";
}

function header(sipHeaders, name) {
  const want = name.toLowerCase();
  for (const h of Array.isArray(sipHeaders) ? sipHeaders : []) {
    if (String(h?.name || "").toLowerCase() === want) return String(h?.value || "");
  }
  return "";
}

/** The secretary's own line (SECRETARY_NUMBER, else DEFAULT_NUMBER). */
export function ownLine(env) {
  return e164(env?.SECRETARY_NUMBER) || DEFAULT_NUMBER;
}

/** Headers that may carry the number the call was meant for (player's number or the secretary line), in priority order. */
const CALLED_HEADERS = ["Diversion", "History-Info", "P-Called-Party-ID", "Request-URI", "X-Original-To", "Original-To", "X-Called-Party-ID"];
/** X-* header names that hint at the called side / at the caller (the latter are never read as the called number). */
const X_CALLED = /num|did|dnis|called|(^|-)to($|-)|dest|ext|line|phone|target|orig|redirect|forward|divert|request|uri/i;
const X_CALLER = /from|caller|calling|cli|ani|source|src|remote|asserted|pai|rpid/i;

/**
 * Who the call is for and who is calling. A forwarded call carries the player's own number in Diversion /
 * History-Info / P-Called-Party-ID (or a Zadarma X-header); a direct call may only have To. From (then
 * P-Asserted-Identity, Remote-Party-ID) is the caller. OpenAI's To is usually the proj_…@sip.api.openai.com URI
 * (no number). When no called number is found at all, the call is for the secretary's own line: this OpenAI
 * project receives SIP only from that line (assumed: true).
 */
export function callParties(sipHeaders, env = {}) {
  const list = Array.isArray(sipHeaders) ? sipHeaders : [];
  const caller =
    e164(header(list, "From")) || e164(header(list, "P-Asserted-Identity")) || e164(header(list, "Remote-Party-ID")) || "unknown";
  const candidates = [];
  const add = (n, via) => {
    if (n && n !== caller && !candidates.some((c) => c.number === n)) candidates.push({ number: n, via });
  };
  for (const name of CALLED_HEADERS) for (const n of numbersIn(header(list, name))) add(n, name);
  for (const h of list) {
    const name = String(h?.name || "");
    if (!/^x-/i.test(name) || X_CALLER.test(name) || CALLED_HEADERS.some((c) => c.toLowerCase() === name.toLowerCase())) continue;
    const value = String(h?.value || "");
    if (X_CALLED.test(name) || /(?:sips?|tel):/i.test(value)) for (const n of numbersIn(value)) add(n, name);
  }
  const to = e164(header(list, "To"));
  if (to) add(to, "To");
  const forwarded = candidates.find((c) => c.via !== "To");
  if (!candidates.length) {
    const own = ownLine(env);
    return { forwardedFrom: "", to: "", number: own, caller, candidates: [], via: "", assumed: true };
  }
  return {
    forwardedFrom: forwarded?.number || "",
    to: to && to !== caller ? to : "",
    number: candidates[0].number,
    caller,
    candidates: candidates.map((c) => c.number),
    via: candidates[0].via,
    assumed: false,
  };
}

/** KV phone:<number> -> userId (set with POST /phone); the secretary's own number -> OWNER_USER_ID. */
export async function playerFor(env, parties) {
  return (await playerRoute(env, parties)).userId;
}

/**
 * playerFor plus how the player was found: "phone" (KV phone map), "claim" (an armed claim or a caller the
 * demo line remembers), "fallback" (nobody claimed the demo line: its OWNER_USER_ID account), "" (none).
 */
export async function playerRoute(env, parties) {
  const nums = parties.candidates?.length ? parties.candidates : [parties.forwardedFrom, parties.to].filter(Boolean);
  for (const n of nums) {
    const mapped = await env.BALANCES.get("phone:" + n);
    if (mapped) return { userId: mapped, via: "phone" };
  }
  if (isOwnLine(env, parties)) {
    const claimed = await claimedPlayer(env, parties.caller);
    if (claimed) return { userId: claimed, via: "claim" };
    if (env.OWNER_USER_ID) return { userId: String(env.OWNER_USER_ID), via: "fallback" };
  }
  return { userId: "", via: "" };
}

/**
 * The demo line is shared. A player who taps "Call the secretary" in the app arms a claim (POST /call-claim,
 * 3 min); the next call to the line goes to their inbox and their caller number is remembered for later calls
 * (so a judge's own calls land in their own app). Trade-off, stated in the app: anyone calling the line during
 * those 3 minutes lands in the claimer's inbox.
 */
async function claimedPlayer(env, caller) {
  const room = claimRoom(env);
  if (!room) return "";
  try {
    const res = await room.fetch("https://call-room/claim-take", { method: "POST", body: JSON.stringify({ caller: e164(caller) }) });
    const j = await res.json();
    return validUserId(j?.userId) ? j.userId : "";
  } catch {
    return "";
  }
}

export function isOwnLine(env, parties) {
  const own = ownLine(env);
  return parties.number === own || parties.to === own || (parties.candidates || []).includes(own);
}

/**
 * Privacy-safe copy of a SIP header value for logs: quoted display names dropped, IPv4 addresses replaced,
 * every run of 5+ digits reduced to its last 4 ("+380914810885" -> "+…0885"), length capped.
 */
export function maskValue(value) {
  return String(value ?? "")
    .slice(0, 400)
    .replace(/"[^"]*"/g, '"…"')
    .replace(/\b\d{1,3}(?:\.\d{1,3}){3}\b/g, "ip")
    .replace(/(\+?)(\d[\d\s()-]{3,}\d)/g, (m, plus, body) => {
      const d = body.replace(/\D/g, "");
      return d.length >= 5 ? plus + "…" + d.slice(-4) : m;
    })
    .slice(0, 160);
}

export function maskHeaders(sipHeaders) {
  return (Array.isArray(sipHeaders) ? sipHeaders : []).slice(0, 40).map((h) => ({ n: String(h?.name || "").slice(0, 60), v: maskValue(h?.value) }));
}

const last4 = (n) => (n && n !== "unknown" ? "…" + String(n).slice(-4) : String(n || ""));

// ---- Secretary language (per player): KV secretary_lang:<userId> = auto | uk | en (default auto) ----

export const LANGS = ["auto", "uk", "en"];
export const LANG_RULE = {
  uk: "Always speak Ukrainian.",
  en: "Always speak English.",
  auto: "Greet in Ukrainian, then reply in the language the caller speaks.",
};

export async function langOf(env, userId) {
  if (!userId) return "auto";
  const v = await env.BALANCES.get("secretary_lang:" + userId);
  return LANGS.includes(v) ? v : "auto";
}

export function voiceFor(lang, withNote) {
  return VOICE + "\n" + (withNote ? NOTE_FIRST + "\n" : "") + (LANG_RULE[lang] || LANG_RULE.auto) + (withNote ? NOTE_RULE : "");
}

function validUserId(userId) {
  return typeof userId === "string" && userId.length >= 8 && userId.length <= 80 && !/\s/.test(userId);
}

// ---- Starter (trial) credit and the demo line budget ----

/** Sessions of starter credit per user id (env TRIAL_SESSIONS, default 3 -> $0.60). 0 turns the trial off. */
export const TRIAL_SESSIONS = 3;
/** Trial-funded sessions per UTC day across everyone (env TRIAL_DAILY_CAP). */
export const TRIAL_DAILY_CAP = 30;
/** Trial-funded calls per caller number per UTC day (env TRIAL_CALLER_CAP). Hidden numbers share one bucket. */
export const TRIAL_CALLER_CAP = 3;
const COUNTER_TTL = 2 * 86400;

/**
 * Owner / admin player ids: never blocked by the starter credit or the shared trial caps, and their own calls
 * are not billed (source "owner"). d61556d7… is the owner's account; 06e76c23… is the player id of the owner's
 * phone (the app armed the demo line for it and the line remembers its caller number). env ADMIN_USER_IDS
 * (comma-separated) adds more. Calls that only reach an admin id as the demo line's unclaimed fallback
 * (strangers dialling +380914810885) are NOT exempt: they keep the demo budget and its caps.
 */
export const ADMIN_USER_IDS = ["d61556d7-b92a-4a54-aa84-95897565439d", "06e76c23-f2f9-447c-8a9c-b70d14b1eadd"];

export function isAdmin(env, userId) {
  if (!validUserId(userId)) return false;
  if (ADMIN_USER_IDS.includes(userId)) return true;
  return String(env?.ADMIN_USER_IDS || "")
    .split(",")
    .map((x) => x.trim())
    .filter(Boolean)
    .includes(userId);
}

function capOf(v, d) {
  if (v === undefined || v === null || v === "") return d;
  const n = Number(v);
  return Number.isFinite(n) ? Math.max(0, Math.floor(n)) : d;
}

const cents = (x) => Math.round(x * 100) / 100;

function dayKey(now = Date.now()) {
  return new Date(now).toISOString().slice(0, 10);
}

async function sha16(text) {
  const h = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(String(text))));
  return [...h.slice(0, 8)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Starter credit of a user id. It is granted once per id: until the first trial-funded session it is
 * virtual (nothing written, so reading /balance costs no KV writes), shown to ids that have never had a paid
 * balance key; the first trial session writes trial_granted:<id> and trial:<id>. Ids that already have a
 * paid balance key before claiming it do not get it.
 */
export async function trialOf(env, userId) {
  const sessions = capOf(env.TRIAL_SESSIONS, TRIAL_SESSIONS);
  if (!validUserId(userId) || sessions === 0) return { usd: 0, granted: false };
  if (await env.BALANCES.get("trial_granted:" + userId)) {
    return { usd: Number((await env.BALANCES.get("trial:" + userId)) || 0), granted: true };
  }
  if ((await env.BALANCES.get(userId)) !== null) return { usd: 0, granted: false };
  return { usd: cents(sessions * SESSION_USD), granted: false };
}

/**
 * Takes one slot of the shared trial/demo budget: the global daily cap and, for phone calls, the per-caller
 * cap. Returns "" when taken, else TRIAL_DAILY_CAP / TRIAL_CALLER_CAP. KV counters are best effort (not atomic).
 */
export async function takeTrialSlot(env, caller = null, now = Date.now()) {
  const day = dayKey(now);
  const gKey = "trial_day:" + day;
  const used = Number((await env.BALANCES.get(gKey)) || 0);
  if (used >= capOf(env.TRIAL_DAILY_CAP, TRIAL_DAILY_CAP)) return "TRIAL_DAILY_CAP";
  let cKey = "";
  let cUsed = 0;
  if (caller !== null) {
    cKey = "trial_caller:" + day + ":" + (await sha16(caller || "unknown"));
    cUsed = Number((await env.BALANCES.get(cKey)) || 0);
    if (cUsed >= capOf(env.TRIAL_CALLER_CAP, TRIAL_CALLER_CAP)) return "TRIAL_CALLER_CAP";
  }
  await env.BALANCES.put(gKey, String(used + 1), { expirationTtl: COUNTER_TTL });
  if (cKey) await env.BALANCES.put(cKey, String(cUsed + 1), { expirationTtl: COUNTER_TTL });
  return "";
}

/**
 * Gives back a slot taken by takeTrialSlot (same day [now]) when the session never really happened: the
 * accept failed, or the call ended in seconds with no word from the caller. Before 0.22.0 only the credit was
 * refunded and the counters kept the slot, so two failed accepts plus one call used up a caller's whole day
 * (live 2 Oct: trial_caller = 3 for +…0117 while its credit was back to $0.60).
 */
export async function releaseTrialSlot(env, caller = null, now = Date.now()) {
  const day = dayKey(now);
  const dec = async (key) => {
    const n = Number((await env.BALANCES.get(key)) || 0);
    if (n > 0) await env.BALANCES.put(key, String(n - 1), { expirationTtl: COUNTER_TTL });
  };
  await dec("trial_day:" + day);
  if (caller !== null) await dec("trial_caller:" + day + ":" + (await sha16(caller || "unknown")));
}

/**
 * One session for userId: starter credit first (within the caps), then paid credit.
 * -> { ok, source: "trial" | "paid", usd (total left), refund() } or { ok: false, reason, usd }.
 * caller = the caller number for phone calls (per-caller cap), null for /screen.
 */
export async function chargeSession(env, userId, caller = null, { fallback = false } = {}) {
  const paid = await getUsd(env, userId);
  const t = await trialOf(env, userId);
  if (isAdmin(env, userId) && !fallback) {
    // The owner's own account and phone: never blocked, never billed, and no shared trial slot is used.
    return { ok: true, source: "owner", usd: cents(paid + t.usd), trialUsd: t.usd, refund: async () => {} };
  }
  let reason = "";
  if (t.usd >= SESSION_USD - 1e-9) {
    const takenAt = Date.now();
    reason = await takeTrialSlot(env, caller, takenAt);
    if (!reason) {
      if (!t.granted) await env.BALANCES.put("trial_granted:" + userId, String(Date.now()));
      const left = cents(t.usd - SESSION_USD);
      await env.BALANCES.put("trial:" + userId, String(left));
      return {
        ok: true,
        source: "trial",
        usd: cents(paid + left),
        trialUsd: left,
        refund: async () => {
          const cur = Number((await env.BALANCES.get("trial:" + userId)) || 0);
          await env.BALANCES.put("trial:" + userId, String(cents(cur + SESSION_USD)));
          await releaseTrialSlot(env, caller, takenAt);
        },
      };
    }
  }
  if (paid >= SESSION_USD - 1e-9) {
    const next = await setUsd(env, userId, paid - SESSION_USD);
    return {
      ok: true,
      source: "paid",
      usd: cents(next + t.usd),
      trialUsd: t.usd,
      refund: async () => void (await setUsd(env, userId, (await getUsd(env, userId)) + SESSION_USD)),
    };
  }
  return { ok: false, reason: reason || "NEED_TOPUP", usd: cents(paid + t.usd) };
}

const MISSED = {
  NEED_TOPUP: "Missed call: the secretary has no credit. Top up to let it answer.",
  TRIAL_DAILY_CAP: "Missed call: today's free trial calls are used up for everyone. Top up to let the secretary answer.",
  TRIAL_CALLER_CAP: "Missed call: this caller used up today's free trial calls. Top up to let the secretary answer.",
};

// ---- 1.1.3 call cost cap: max call length + daily AI call minutes (account and global) ----
//
// Every AI call is wrapped up and hung up by its call room (CallRoom alarms): at CALL_WRAP_SEC the secretary is
// told (realtime sideband, response.create) to say it will pass the message on and say goodbye; at CALL_MAX_SEC
// the worker hangs up (POST /v1/realtime/calls/{id}/hangup). Finished calls add their real length to KV counters
// per UTC day: call_sec_day:<day> (everyone) and call_sec_user:<day>:<userId>. When either daily cap is reached a
// new call is not given to the secretary: it gets one short scripted goodbye (a few seconds, no tools, no
// secretary prompt; hung up after CAPPED_CALL_SEC) and a missed-call line, and nothing is charged. After
// CALL_CAP_MESSAGES such goodbyes in a day, further calls are rejected (486) without any realtime session.
// OpenAI SIP has no way to play audio without a realtime session, so the goodbye is a tiny one.
// Counters are best effort (KV, not atomic): a call that starts under the cap can still run its full length.
// All values are env-configurable ([vars] or secrets); 0 turns that cap off.

/** Hard maximum length of one AI call, seconds (env CALL_MAX_SEC). */
export const CALL_MAX_SEC = 180;
/** When the secretary is told to wrap up, seconds after answering (env CALL_WRAP_SEC). */
export const CALL_WRAP_SEC = 165;
/** AI call minutes per UTC day for everyone together (env CALL_DAILY_MIN_GLOBAL). */
export const CALL_DAILY_MIN_GLOBAL = 30;
/** AI call minutes per UTC day for one account (env CALL_DAILY_MIN_ACCOUNT). */
export const CALL_DAILY_MIN_ACCOUNT = 20;
/** A capped call's goodbye is hung up after this many seconds (env CAPPED_CALL_SEC). */
export const CAPPED_CALL_SEC = 15;
/** Scripted "limit reached" goodbyes per UTC day; after that capped calls are rejected silently (env CALL_CAP_MESSAGES). */
export const CALL_CAP_MESSAGES = 20;

export function callLimits(env) {
  const max = capOf(env?.CALL_MAX_SEC, CALL_MAX_SEC);
  const wrap = Math.min(capOf(env?.CALL_WRAP_SEC, CALL_WRAP_SEC), max ? Math.max(0, max - 5) : Infinity);
  return {
    maxSec: max,
    wrapSec: wrap,
    globalMin: capOf(env?.CALL_DAILY_MIN_GLOBAL, CALL_DAILY_MIN_GLOBAL),
    accountMin: capOf(env?.CALL_DAILY_MIN_ACCOUNT, CALL_DAILY_MIN_ACCOUNT),
    cappedSec: Math.max(5, capOf(env?.CAPPED_CALL_SEC, CAPPED_CALL_SEC)),
    capMessages: capOf(env?.CALL_CAP_MESSAGES, CALL_CAP_MESSAGES),
  };
}

const callSecKeys = (userId, now) => {
  const day = dayKey(now);
  return { g: "call_sec_day:" + day, u: userId ? "call_sec_user:" + day + ":" + userId : "" };
};

/** Seconds of AI calls today: { global, account }. */
export async function callUsage(env, userId, now = Date.now()) {
  const k = callSecKeys(userId, now);
  const global = Number((await env.BALANCES.get(k.g)) || 0) || 0;
  const account = k.u ? Number((await env.BALANCES.get(k.u)) || 0) || 0 : 0;
  return { global, account };
}

/** Adds a finished call's real length to today's counters (the day the call was answered). */
export async function addCallSeconds(env, userId, sec, at = Date.now()) {
  if (!Number.isFinite(sec) || sec <= 0) return;
  const k = callSecKeys(userId, at);
  const add = async (key) => {
    const n = Number((await env.BALANCES.get(key)) || 0) || 0;
    await env.BALANCES.put(key, String(Math.round(n + sec)), { expirationTtl: COUNTER_TTL });
  };
  await add(k.g);
  if (k.u) await add(k.u);
}

/** "" while today's AI minutes are under both caps, else CALL_MINUTES_GLOBAL / CALL_MINUTES_ACCOUNT. */
export async function callCapReason(env, userId, now = Date.now()) {
  const L = callLimits(env);
  const u = await callUsage(env, userId, now);
  if (L.globalMin > 0 && u.global >= L.globalMin * 60) return "CALL_MINUTES_GLOBAL";
  if (userId && L.accountMin > 0 && u.account >= L.accountMin * 60) return "CALL_MINUTES_ACCOUNT";
  return "";
}

const CAP_LINE = {
  uk: "Добрий день! На жаль, сьогодні помічник уже не може приймати дзвінки. Будь ласка, зателефонуйте завтра або напишіть повідомлення. Дякую і до побачення!",
  en: "Hello! Sorry, the assistant can't take any more calls today. Please call back tomorrow or send a text message. Thank you, goodbye!",
};

/** The capped call's whole script: one short goodbye, nothing else. */
export function capInstructions(lang) {
  const line = lang === "en" ? CAP_LINE.en : lang === "uk" ? CAP_LINE.uk : CAP_LINE.uk + " " + CAP_LINE.en;
  return `You are a phone line that is closed for today. Say exactly this once, in a calm friendly voice, and nothing else: "${line}" Do not answer questions, do not take messages, do not continue the conversation.`;
}

/** What the secretary is told at CALL_WRAP_SEC (sent as response.create instructions over the sideband). */
export function wrapInstructions(lang) {
  if (lang === "en") return "The call time is almost over. In one or two short sentences, politely tell the caller that you will pass their message on to the owner, thank them and say goodbye. Do not ask any more questions.";
  return "Час дзвінка майже вийшов. Одним-двома короткими реченнями ввічливо скажіть абонентові (на «ви»), що передасте повідомлення власникові, подякуйте і попрощайтеся. Більше нічого не питайте. If the caller speaks English, say it in English.";
}

export const CAPPED_TEXT = "Missed call: today's AI call minutes are used up, so the secretary did not answer. The caller heard a short message.";

/** Takes one of today's scripted goodbyes; false when they are used up (then the call is rejected silently). */
async function takeCapMessage(env, now = Date.now()) {
  const max = callLimits(env).capMessages;
  if (max === 0) return false;
  const key = "call_cap_msg:" + dayKey(now);
  const n = Number((await env.BALANCES.get(key)) || 0) || 0;
  if (n >= max) return false;
  await env.BALANCES.put(key, String(n + 1), { expirationTtl: COUNTER_TTL });
  return true;
}

/** Ends an AI call (SIP or WebRTC). true on 2xx. */
export async function hangupCall(env, callId) {
  try {
    const res = await fetch("https://api.openai.com/v1/realtime/calls/" + encodeURIComponent(callId) + "/hangup", {
      method: "POST",
      headers: { Authorization: "Bearer " + env.OPENAI_API_KEY },
      signal: AbortSignal.timeout(10000),
    });
    return res.ok;
  } catch {
    return false;
  }
}

// ---- Webhook dedup: the same realtime.call.incoming may be delivered more than once ----

export const DEDUP_TTL_SEC = 600;
const SEEN = new Map();

/** Tests only: forget the in-isolate dedup memory. */
export function resetDedupMemory() {
  SEEN.clear();
}

/**
 * true for the first delivery of these keys (dedup:wh:<webhook-id>, dedup:call:<call_id>), false for a repeat.
 * The in-isolate map is checked and set before any await, so two deliveries racing into the same isolate are
 * caught; KV (TTL 10 min) catches later repeats across isolates. KV is eventually consistent, so two
 * deliveries landing in different data centres within ~a second can still both pass (strict dedup would need
 * a Durable Object).
 */
export async function firstDelivery(env, keys, now = Date.now()) {
  for (const [k, exp] of SEEN) if (exp < now) SEEN.delete(k);
  const ks = keys.filter(Boolean);
  // A repeat still records its own delivery id, so a later retry of either delivery is known too.
  const remember = () => Promise.all(ks.map((k) => env.BALANCES.put(k, String(now), { expirationTtl: DEDUP_TTL_SEC })));
  if (ks.some((k) => SEEN.has(k))) {
    for (const k of ks) SEEN.set(k, now + DEDUP_TTL_SEC * 1000);
    await remember();
    return false;
  }
  for (const k of ks) SEEN.set(k, now + DEDUP_TTL_SEC * 1000);
  let seen = false;
  for (const k of ks) if (await env.BALANCES.get(k)) seen = true;
  await remember();
  return !seen;
}

/** After a failed accept: let OpenAI's retry try again. */
async function forgetDelivery(env, keys) {
  for (const k of keys.filter(Boolean)) {
    SEEN.delete(k);
    await env.BALANCES.delete?.(k);
  }
}

async function hmacHex(key, text) {
  const k = await crypto.subtle.importKey("raw", enc.encode(String(key)), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", k, enc.encode(text)));
  return [...mac].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function callKey(env) {
  return env.CALL_TOKEN_SECRET || env.OPENAI_WEBHOOK_SECRET || env.OPENAI_API_KEY || "";
}

/** Per-call token for the note tool: `<callId>.<hmac>`; only OpenAI (who got it in the accept body) has it. */
export async function callToken(env, callId) {
  return callId + "." + (await hmacHex(callKey(env), "solarchik-call:" + callId));
}

export async function callFromToken(env, token) {
  const t = String(token || "");
  const dot = t.lastIndexOf(".");
  if (dot < 1 || !callKey(env)) return "";
  const callId = t.slice(0, dot);
  return (await sameSecret(t, await callToken(env, callId))) ? callId : "";
}

/**
 * One phone call reaches /sip twice (3 Oct, …ayTaC80A): a live.* delivery with data.session_id "live_<x>" and a
 * realtime.call.incoming with data.call_id "rtc_<x>", the same <x>. Only the rtc_ id can be accepted. The
 * canonical key (prefix stripped) names the call room, so both deliveries meet in one Durable Object.
 */
export function canonCallId(id) {
  return String(id || "").replace(/^(rtc|live)_/, "");
}

/**
 * The inbox as the apps get it: per call, a failed "could not pick up" ghost (the live_ twin of an answered call)
 * is dropped when the same call also has an answered/pending/blocked line. Old KV data still has such ghosts.
 */
export function cleanInbox(items) {
  const list = (Array.isArray(items) ? items : []).filter((it) => it && typeof it === "object");
  const good = new Set(list.filter((it) => it.callId && it.status !== "failed").map((it) => canonCallId(it.callId)));
  return list.filter((it) => !(it.callId && it.status === "failed" && good.has(canonCallId(it.callId))));
}

const BLOCK_MAX = 200;

export async function blockedNumbers(env, userId) {
  try {
    const v = JSON.parse((await env.BALANCES.get("block:" + userId)) || "[]");
    return Array.isArray(v) ? v.filter((n) => typeof n === "string") : [];
  } catch {
    return [];
  }
}

export async function isBlocked(env, userId, caller) {
  const n = e164(caller);
  if (!userId || !n) return false;
  return (await blockedNumbers(env, userId)).includes(n);
}

async function setBlocked(env, userId, number, blocked) {
  const list = (await blockedNumbers(env, userId)).filter((n) => n !== number);
  if (blocked) list.unshift(number);
  await env.BALANCES.put("block:" + userId, JSON.stringify(list.slice(0, BLOCK_MAX)));
  return list.slice(0, BLOCK_MAX);
}

const TRANSCRIPT_TTL = 60 * 24 * 3600;

/** The call's words for the app's call detail (KV transcript:<callId>, 60 days), capped. */
export async function saveTranscript(env, callId, lines, durationSec) {
  const clean = (Array.isArray(lines) ? lines : [])
    .filter((l) => l && l.text)
    .slice(-60)
    .map((l) => ({ who: l.who === "caller" ? "caller" : "secretary", text: clip(l.text, 500) }));
  const rec = { lines: clean, durationSec: Number.isFinite(durationSec) ? durationSec : null, at: Date.now() };
  await env.BALANCES.put("transcript:" + callId, JSON.stringify(rec), { expirationTtl: TRANSCRIPT_TTL });
  return rec;
}

/** The demo line's claim room: one Durable Object (strongly consistent, unlike KV) for /call-claim. */
function claimRoom(env) {
  return env.CALLS ? env.CALLS.get(env.CALLS.idFromName("__line_claims__")) : null;
}

export const CLAIM_SEC = 180;

async function addInbox(env, userId, item) {
  const raw = await env.BALANCES.get("inbox:" + userId);
  const items = raw ? JSON.parse(raw) : [];
  items.unshift(item);
  await env.BALANCES.put("inbox:" + userId, JSON.stringify(items.slice(0, 20)));
}

async function patchInbox(env, userId, callId, patch, { create = true, at = 0 } = {}) {
  const raw = await env.BALANCES.get("inbox:" + userId);
  const items = raw ? JSON.parse(raw) : [];
  const i = items.findIndex((it) => it && it.callId === callId);
  if (i < 0 && !create) return;
  // Re-created line (a concurrent write lost it): keep the call's real start time, not the patch time.
  if (i < 0) items.unshift({ callId, at: at || Date.now(), ...patch });
  else items[i] = { ...items[i], ...patch };
  await env.BALANCES.put("inbox:" + userId, JSON.stringify(items.slice(0, 20)));
}

const NOTE_TOOL = {
  name: "save_call_note",
  description:
    "Save the caller's message for the owner. Call it as soon as you know what the caller wants (name if given), always before goodbye. Callback defaults to the caller's number.",
  inputSchema: {
    type: "object",
    properties: {
      caller_name: { type: "string" },
      callback: { type: "string" },
      intent: { type: "string", description: "Why they called, one sentence." },
      urgency: { type: "string", enum: ["low", "medium", "high"] },
      spam_risk: { type: "string", enum: ["low", "medium", "high"] },
      action: { type: "string", enum: ["callback", "ignore", "block"] },
      notes: { type: "string" },
    },
    required: ["intent"],
  },
};

function clip(v, n) {
  return String(v ?? "").replace(/\s+/g, " ").trim().slice(0, n);
}

/** The note as the apps show it: one readable line in `text`, the structured summary alongside. */
export function noteText(a) {
  const who = clip(a.caller_name, 60);
  const parts = [who && `${who}:`, clip(a.intent, 200), a.callback ? `Callback ${clip(a.callback, 40)}.` : "", clip(a.notes, 240)];
  return parts.filter(Boolean).join(" ").slice(0, 600);
}

/** Minimal MCP server (Streamable HTTP, JSON responses) with one tool the realtime session calls. */
async function mcp(env, request) {
  const callId = await callFromToken(env, request.headers.get("x-solarchik-call"));
  if (!callId) return json({ jsonrpc: "2.0", id: null, error: { code: -32001, message: "unauthorized" } }, 401);
  const msg = await request.json().catch(() => null);
  if (!msg || typeof msg !== "object") return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } }, 400);
  const id = msg.id ?? null;
  const ok = (result) => json({ jsonrpc: "2.0", id, result });
  if (msg.id === undefined) return new Response(null, { status: 202 }); // notification
  if (msg.method === "initialize") {
    return ok({ protocolVersion: msg.params?.protocolVersion || "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "solarchik-secretary", version: "1" } });
  }
  if (msg.method === "ping") return ok({});
  if (msg.method === "tools/list") return ok({ tools: [NOTE_TOOL] });
  if (msg.method === "tools/call" && msg.params?.name === NOTE_TOOL.name) {
    const raw = await env.BALANCES.get("call:" + callId);
    const call = raw ? JSON.parse(raw) : null;
    if (!call?.userId) return ok({ content: [{ type: "text", text: "No player for this call; nothing saved." }], isError: true });
    const a = msg.params.arguments && typeof msg.params.arguments === "object" ? msg.params.arguments : {};
    const summary = {
      caller_name: clip(a.caller_name, 60),
      callback: clip(a.callback, 40) || (call.caller && call.caller !== "unknown" ? call.caller : ""),
      intent: clip(a.intent, 200),
      urgency: clip(a.urgency, 8),
      spam_risk: clip(a.spam_risk, 8),
      action: clip(a.action, 10),
      notes: clip(a.notes, 400),
    };
    await patchInbox(env, call.userId, callId, { caller: call.caller, text: noteText(summary) || "Call note (empty)", summary, status: "done", source: "tool" }, { at: call.at });
    return ok({ content: [{ type: "text", text: "Saved. Say goodbye." }] });
  }
  return json({ jsonrpc: "2.0", id, error: { code: -32601, message: "method not found" } });
}

async function acceptCall(env, callId, body) {
  return fetch("https://api.openai.com/v1/realtime/calls/" + encodeURIComponent(callId) + "/accept", {
    method: "POST",
    headers: { Authorization: "Bearer " + env.OPENAI_API_KEY, "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

/**
 * realtime.call.incoming: find the player, charge one session (starter credit within the caps, else paid),
 * accept with the note tool and the player's language. The secretary's own line falls back to the shared
 * demo budget when its owner cannot pay, so a judge can simply dial it. Unmapped calls (no OWNER_USER_ID, no
 * KV phone map) are answered from the demo budget too, without billing or a note. When the budget/credit is
 * gone: a missed-call line in the inbox (if there is a player) and reject 486.
 */
async function incomingCall(env, origin, callId, sipHeaders, dedupKeys = [], dataKeys = []) {
  const out = await handleIncoming(env, origin, callId, sipHeaders, dedupKeys, dataKeys);
  return json(out.body);
}

/** call:<id> marker: who the call is for and how far it got ("accepting" -> "accepted" | "failed"). */
async function callMark(env, callId) {
  const raw = await env.BALANCES.get("call:" + callId);
  try {
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

async function putMark(env, callId, mark) {
  await env.BALANCES.put("call:" + callId, JSON.stringify(mark), { expirationTtl: 3600 });
}

/**
 * The incoming-call logic. Returns { body, meta } (meta: what the call room needs to watch the call).
 * Live 2 Oct (…qjb3): OpenAI delivered realtime.call.incoming twice 166 ms apart into different isolates; KV
 * dedup missed it, the second accept failed and its failure path refunded and overwrote the good inbox line
 * with "could not pick up". Now: (1) with the CALLS Durable Object (production) one call id is processed
 * exactly once, strictly; (2) without it, the call:<id> marker is read before charging and after a failed
 * accept, so a delivery that finds the call already taken or accepted never charges, refunds or rewrites the
 * inbox; (3) the failure path only rewrites a line that is still "pending".
 */
export async function handleIncoming(env, origin, callId, sipHeaders, dedupKeys = [], dataKeys = []) {
  const parties = callParties(sipHeaders, env);
  console.log(
    JSON.stringify({
      event: "sip_headers",
      callId: String(callId).slice(-8),
      headers: maskHeaders(sipHeaders),
      number: last4(parties.number),
      via: parties.via || (parties.assumed ? "assumed_own_line" : ""),
      caller: last4(parties.caller),
      dataKeys: dataKeys.slice(0, 20),
    }),
  );
  const dup = () => {
    console.log(JSON.stringify({ event: "sip_duplicate", callId: String(callId).slice(-8), via: "marker" }));
    return { body: { ok: true, duplicate: true }, meta: null };
  };
  if (await callMark(env, callId)) return dup();
  const route = await playerRoute(env, parties);
  const userId = route.userId;
  const own = isOwnLine(env, parties);
  const lang = await langOf(env, userId);
  const base = { type: "realtime", model: "gpt-realtime", instructions: voiceFor(lang, false) };
  const reject = () =>
    fetch("https://api.openai.com/v1/realtime/calls/" + encodeURIComponent(callId) + "/reject", {
      method: "POST",
      headers: { Authorization: "Bearer " + env.OPENAI_API_KEY, "Content-Type": "application/json" },
      body: JSON.stringify({ status_code: 486 }),
    }).catch(() => null);

  // 1.1.3: today's AI call minutes are used up (everyone, or this account): never the secretary, never a charge.
  const capped = async (reason) => {
    const at = Date.now();
    await putMark(env, callId, { userId, caller: parties.caller, at, state: "capped" });
    const say = await takeCapMessage(env, at);
    let accepted = false;
    if (say) {
      const acc = await acceptCall(env, callId, { type: "realtime", model: "gpt-realtime", instructions: capInstructions(lang) }).catch(() => null);
      accepted = Boolean(acc?.ok);
    }
    if (!accepted) await reject();
    if (userId) await addInbox(env, userId, { callId, caller: parties.caller, text: CAPPED_TEXT, at, status: "failed", reason, chargedUsd: 0 });
    console.log(JSON.stringify({ event: "sip_capped", callId: String(callId).slice(-8), reason, message: accepted }));
    return { body: { accepted, capped: true, reason }, meta: accepted ? { capped: true, userId: userId || "", caller: parties.caller, lang } : null };
  };

  if (!userId) {
    const capReason = await callCapReason(env, "");
    if (capReason) return capped(capReason);
    const takenAt = Date.now();
    const reason = await takeTrialSlot(env, parties.caller, takenAt);
    console.log(
      JSON.stringify({
        event: "sip_unmapped",
        number: last4(parties.number),
        assumed: parties.assumed,
        ownLine: own,
        why: own ? "OWNER_USER_ID not set" : "no phone:<number> mapping",
        budget: reason || "demo",
      }),
    );
    if (reason) {
      const rej = await reject();
      return { body: { accepted: false, rejected: Boolean(rej?.ok), error: "NEED_TOPUP", reason, player: false }, meta: null };
    }
    const accept = await acceptCall(env, callId, base);
    if (!accept.ok) {
      await releaseTrialSlot(env, parties.caller, takenAt);
      await forgetDelivery(env, dedupKeys);
    }
    // 1.1.3: the call room still watches an unmapped call, so it is wrapped up and hung up like any other.
    return {
      body: { accepted: accept.ok, status: accept.status, player: false, trial: true, source: "demo", lang },
      meta: accept.ok ? { userId: "", caller: parties.caller, lang, noteTool: false, source: "demo", chargedAt: takenAt, unmapped: true } : null,
    };
  }

  // Taken before any money moves: a second delivery that reaches this point later sees the marker.
  const at = Date.now();
  if (await isBlocked(env, userId, parties.caller)) {
    // The player blocked this number in the app (POST /block): rejected, never charged, one line in the inbox.
    await putMark(env, callId, { userId, caller: parties.caller, at, state: "rejected" });
    await addInbox(env, userId, { callId, caller: parties.caller, text: "Blocked caller: the secretary rejected the call.", at, status: "blocked", chargedUsd: 0 });
    const rej = await reject();
    console.log(JSON.stringify({ event: "sip_blocked", callId: String(callId).slice(-8), caller: last4(parties.caller) }));
    return { body: { accepted: false, rejected: Boolean(rej?.ok), blocked: true }, meta: null };
  }
  const capReason = await callCapReason(env, userId);
  if (capReason) return capped(capReason);
  await putMark(env, callId, { userId, caller: parties.caller, at, state: "accepting" });
  let charge = await chargeSession(env, userId, parties.caller, { fallback: route.via === "fallback" });
  if (!charge.ok && own) {
    // The demo line: its owner pays when they can; otherwise the shared demo budget answers.
    const takenAt = Date.now();
    const reason = await takeTrialSlot(env, parties.caller, takenAt);
    charge = reason ? { ...charge, reason } : { ok: true, source: "demo", usd: charge.usd, refund: async () => releaseTrialSlot(env, parties.caller, takenAt) };
  }
  if (!charge.ok) {
    await putMark(env, callId, { userId, caller: parties.caller, at, state: "rejected" });
    await addInbox(env, userId, { callId, caller: parties.caller, text: MISSED[charge.reason] || MISSED.NEED_TOPUP, at: Date.now(), status: "need_topup", reason: charge.reason });
    const rej = await reject();
    return { body: { accepted: false, rejected: Boolean(rej?.ok), error: "NEED_TOPUP", reason: charge.reason }, meta: null };
  }
  const trial = charge.source === "trial" || charge.source === "demo";
  const chargedUsd = charge.source === "demo" || charge.source === "owner" ? 0 : SESSION_USD;
  await addInbox(env, userId, { callId, caller: parties.caller, text: "Call answered by the secretary. Note follows.", at, status: "pending", chargedUsd, trial, source: charge.source, lang });
  const tool = {
    type: "mcp",
    server_label: "solarchik",
    server_url: origin + "/mcp",
    headers: { "x-solarchik-call": await callToken(env, callId) },
    require_approval: "never",
    allowed_tools: [NOTE_TOOL.name],
  };
  let accept = await acceptCall(env, callId, { ...base, instructions: voiceFor(lang, true), tools: [tool] });
  let withTool = accept.ok;
  if (!accept.ok && accept.status !== 404) {
    // The note tool must never cost the call: answer without it (the inbox keeps the "answered" line).
    accept = await acceptCall(env, callId, base);
    withTool = false;
  }
  if (!accept.ok) {
    // Another delivery may have answered this very call meanwhile (the accept then fails here): never undo it.
    const mark = await callMark(env, callId);
    if (mark?.state === "accepted") {
      console.log(JSON.stringify({ event: "sip_accept_failed_after_accept", callId: String(callId).slice(-8), status: accept.status }));
      return { body: { ok: true, duplicate: true, status: accept.status }, meta: null };
    }
    await charge.refund();
    await putMark(env, callId, { userId, caller: parties.caller, at, state: "failed" });
    const items = JSON.parse((await env.BALANCES.get("inbox:" + userId)) || "[]");
    const line = items.find((it) => it && it.callId === callId);
    if (!line || line.status === "pending") {
      await patchInbox(env, userId, callId, { text: "Missed call: the secretary could not pick up (refunded).", status: "failed", chargedUsd: 0 });
    }
    await forgetDelivery(env, dedupKeys);
    await env.BALANCES.delete?.("call:" + callId);
    return { body: { accepted: false, status: accept.status, refunded: true }, meta: { retry: true } };
  }
  await putMark(env, callId, { userId, caller: parties.caller, at, state: "accepted", lang });
  return {
    body: { accepted: true, status: accept.status, player: true, noteTool: withTool, usd: charge.usd, trial, source: charge.source, lang },
    meta: { userId, caller: parties.caller, lang, noteTool: withTool, source: charge.source, chargedAt: at },
  };
}

// ---- After the call: a note even when the model never called the tool ----

/** A call that ends within this many seconds without one word from the caller is not billed. */
export const SHORT_CALL_SEC = 15;

/**
 * Refunds a session that never really happened: answered, but the caller hung up within SHORT_CALL_SEC
 * seconds without saying anything (pocket dial, instant hang-up). Trial: credit back and the shared slot
 * released; demo budget: slot released; paid: credit back; owner: nothing was charged. Returns true when refunded.
 */
export async function refundShortCall(env, { userId, callId, caller, source, chargedAt, durationSec, heard }) {
  if (heard || !userId || !Number.isFinite(durationSec) || durationSec > SHORT_CALL_SEC) return false;
  if (source === "owner" || !source) return false;
  if (source === "trial") {
    const cur = Number((await env.BALANCES.get("trial:" + userId)) || 0);
    await env.BALANCES.put("trial:" + userId, String(cents(cur + SESSION_USD)));
    await releaseTrialSlot(env, caller, chargedAt || Date.now());
  } else if (source === "demo") {
    await releaseTrialSlot(env, caller, chargedAt || Date.now());
  } else if (source === "paid") {
    await setUsd(env, userId, (await getUsd(env, userId)) + SESSION_USD);
  } else return false;
  await patchInbox(env, userId, callId, { chargedUsd: 0, refunded: true }, { create: false });
  return true;
}

export const AUTO_NOTE_EMPTY = "Call answered; the caller left no details.";

/**
 * Called when the call is over (the sideband socket closed) or the room's alarm fires. If the line is still
 * "pending" (save_call_note never ran), writes a note from the transcript (gpt-4o-mini, JSON) or, without a
 * transcript, a plain "answered, no details" line. A line the tool already filled is never touched.
 */
export async function finishNote(env, callId, userId, caller, lines = []) {
  if (!userId) return "no_user";
  const items = JSON.parse((await env.BALANCES.get("inbox:" + userId)) || "[]");
  const line = items.find((it) => it && it.callId === callId);
  if (line && line.status !== "pending") return "kept";
  const said = lines.filter((l) => l && l.text).slice(-40);
  const heard = said.some((l) => l.who === "caller");
  if (!heard || !env.OPENAI_API_KEY) {
    await patchInbox(env, userId, callId, { caller, text: AUTO_NOTE_EMPTY, status: "done", source: "auto" });
    return "empty";
  }
  let summary = null;
  try {
    const res = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: { Authorization: "Bearer " + env.OPENAI_API_KEY, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: "gpt-4o-mini",
        temperature: 0.2,
        max_tokens: 220,
        response_format: { type: "json_object" },
        messages: [
          {
            role: "system",
            content:
              'Summarise this phone call for the person who was called. JSON only: {"caller_name":"","intent":"one sentence: what they want","urgency":"low|medium|high","spam_risk":"low|medium|high","action":"callback|ignore|block","notes":""}. Write intent and notes in the language the caller spoke. Leave a field empty when unknown. Never invent details.',
          },
          { role: "user", content: said.map((l) => (l.who === "caller" ? "Caller: " : "Secretary: ") + clip(l.text, 400)).join("\n").slice(0, 6000) },
        ],
      }),
      signal: AbortSignal.timeout(15000),
    });
    const j = await res.json().catch(() => ({}));
    summary = JSON.parse(j.choices?.[0]?.message?.content || "null");
  } catch {
    summary = null;
  }
  if (!summary || typeof summary !== "object" || !clip(summary.intent, 200)) {
    const first = said.find((l) => l.who === "caller");
    await patchInbox(env, userId, callId, { caller, text: "Call answered (from the call transcript): " + clip(first?.text, 300), status: "done", source: "auto" });
    return "raw";
  }
  const s2 = {
    caller_name: clip(summary.caller_name, 60),
    callback: caller && caller !== "unknown" ? caller : "",
    intent: clip(summary.intent, 200),
    urgency: clip(summary.urgency, 8),
    spam_risk: clip(summary.spam_risk, 8),
    action: clip(summary.action, 10),
    notes: clip(summary.notes, 400),
  };
  await patchInbox(env, userId, callId, { caller, text: noteText(s2), summary: s2, status: "done", source: "auto" });
  return "summary";
}

/** Realtime events that carry the call's words. */
export function transcriptLine(ev) {
  if (!ev || typeof ev !== "object") return null;
  if (ev.type === "conversation.item.input_audio_transcription.completed" && ev.transcript) return { who: "caller", text: String(ev.transcript) };
  if ((ev.type === "response.output_audio_transcript.done" || ev.type === "response.audio_transcript.done") && ev.transcript)
    return { who: "secretary", text: String(ev.transcript) };
  return null;
}

/**
 * One Durable Object per call id (binding CALLS). It makes "process this call" strictly once-only (its storage
 * is strongly consistent and requests to one object are serialised), then holds the realtime sideband socket
 * (wss://api.openai.com/v1/realtime?call_id=…) for the call: turns on caller transcription, keeps the words, and
 * on hang-up writes the fallback note through finishNote. An alarm 16 min after accept finishes it anyway
 * (an outbound socket pins the object for at most 15 min).
 */
export class CallRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.lines = [];
    this.done = false;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/lines") return this.linesRoute();
    if (url.pathname === "/claim" || url.pathname === "/claim-take") return this.claimRoute(url.pathname, await request.json().catch(() => ({})));
    // A session-only delivery (live_<x>, no call_id) cannot be accepted when its rtc_<x> twin exists: give the
    // twin a moment to claim this room (both arrive within ms); answer it only if no twin comes.
    if (url.searchParams.get("session") === "1" && !this.taken) {
      await new Promise((r) => setTimeout(r, Number(this.env.SESSION_WAIT_MS ?? 2500)));
      if (this.taken) {
        console.log(JSON.stringify({ event: "sip_duplicate", via: "session_twin" }));
        return json({ ok: true, duplicate: true, via: "session" });
      }
    }
    // Synchronous claim first (one instance per call id serves every request in order), storage for later ones.
    const first = !this.taken;
    this.taken = true;
    const b = await request.json().catch(() => ({}));
    if (!first || (await this.state.storage.get("state"))) {
      console.log(JSON.stringify({ event: "sip_duplicate", callId: String(b.callId || "").slice(-8), via: "room" }));
      return json({ ok: true, duplicate: true });
    }
    await this.state.storage.put("state", "taken");
    const out = await handleIncoming(this.env, b.origin, b.callId, b.sipHeaders, [], b.dataKeys || []);
    if (out.meta?.retry) {
      await this.state.storage.delete("state");
      this.taken = false;
    }
    else if (out.meta && (out.meta.userId || out.meta.unmapped || out.meta.capped)) {
      // 1.1.3: the room's alarms run the call clock: "wrap" (secretary says goodbye) -> "hangup" -> "finish".
      // A capped call only gets its short goodbye and is hung up after CAPPED_CALL_SEC.
      const L = callLimits(this.env);
      const startedAt = Date.now();
      const capped = Boolean(out.meta.capped);
      const phase = capped ? "hangup" : L.maxSec > 0 ? "wrap" : "finish";
      const next = capped ? startedAt + L.cappedSec * 1000 : L.maxSec > 0 ? startedAt + L.wrapSec * 1000 : startedAt + 16 * 60 * 1000;
      await this.state.storage.put({
        state: "accepted",
        callId: b.callId,
        userId: out.meta.userId || "",
        caller: out.meta.caller,
        startedAt,
        source: out.meta.source || "",
        chargedAt: out.meta.chargedAt || startedAt,
        lang: out.meta.lang || "auto",
        capped,
        phase,
      });
      await this.state.storage.setAlarm(next);
      this.lang = out.meta.lang || "auto";
      this.capped = capped;
      this.watch(b.callId, out.meta.userId || "", out.meta.caller).catch((e) =>
        console.log(JSON.stringify({ event: "sideband_error", callId: String(b.callId).slice(-8), detail: String(e?.message || e).slice(0, 120) })),
      );
    }
    return json(out.body);
  }

  async watch(callId, userId, caller) {
    const res = await fetch("https://api.openai.com/v1/realtime?call_id=" + encodeURIComponent(callId), {
      headers: { Upgrade: "websocket", Authorization: "Bearer " + this.env.OPENAI_API_KEY },
    });
    const ws = res.webSocket;
    if (!ws) {
      console.log(JSON.stringify({ event: "sideband_refused", callId: String(callId).slice(-8), status: res.status }));
      return;
    }
    ws.accept();
    this.ws = ws;
    console.log(JSON.stringify({ event: "sideband_open", callId: String(callId).slice(-8) }));
    ws.send(JSON.stringify({ type: "session.update", session: { type: "realtime", audio: { input: { transcription: { model: "gpt-4o-mini-transcribe" } } } } }));
    // A capped call says its one line right away instead of waiting for the caller.
    if (this.capped) ws.send(JSON.stringify({ type: "response.create", response: { instructions: capInstructions(this.lang) } }));
    ws.addEventListener("message", (e) => {
      let ev = null;
      try {
        ev = JSON.parse(typeof e.data === "string" ? e.data : "");
      } catch {
        return;
      }
      this.onEvent(ev);
      const l = transcriptLine(ev);
      if (l) {
        this.lines.push(l);
        this.state.storage.put("lines", this.lines.slice(-60)).catch(() => {});
      }
      if (ev?.type === "error") console.log(JSON.stringify({ event: "sideband_event_error", callId: String(callId).slice(-8), code: ev.error?.code || ev.error?.type || "" }));
    });
    const end = (why) => this.finish(callId, userId, caller, why).catch(() => {});
    ws.addEventListener("close", () => end("closed"));
    ws.addEventListener("error", () => end("error"));
  }

  /** Tracks whether the model is speaking, so the wrap-up never collides with a response in progress. */
  onEvent(ev) {
    if (ev?.type === "response.created") this.responding = true;
    if (ev?.type === "response.done") {
      this.responding = false;
      if (this.wrapPending) this.sendWrap();
    }
  }

  sendWrap() {
    this.wrapPending = false;
    try {
      this.ws?.send(JSON.stringify({ type: "response.create", response: { instructions: wrapInstructions(this.lang) } }));
      this.wrapSent = true;
    } catch {
      this.wrapSent = false;
    }
  }

  /** CALL_WRAP_SEC: ask the secretary to say goodbye (now, or as soon as its current answer ends). */
  wrapUp() {
    if (!this.ws || this.wrapSent) return false;
    if (this.responding) this.wrapPending = true;
    else this.sendWrap();
    return true;
  }

  async finish(callId, userId, caller, why) {
    if (this.done) return;
    this.done = true;
    const lines = this.lines.length ? this.lines : (await this.state.storage.get("lines")) || [];
    const r = await finishNote(this.env, callId, userId, caller, lines);
    const startedAt = await this.state.storage.get("startedAt");
    const durationSec = startedAt ? Math.max(1, Math.round((Date.now() - startedAt) / 1000)) : null;
    try {
      await saveTranscript(this.env, callId, lines, durationSec);
      await patchInbox(this.env, userId, callId, { durationSec, transcriptLines: lines.length }, { create: false });
      const heard = lines.some((l) => l && l.who === "caller" && l.text);
      const source = await this.state.storage.get("source");
      const chargedAt = await this.state.storage.get("chargedAt");
      if (await refundShortCall(this.env, { userId, callId, caller, source, chargedAt, durationSec, heard })) {
        console.log(JSON.stringify({ event: "short_call_refunded", callId: String(callId).slice(-8), source, durationSec }));
      } else if (!(await this.state.storage.get("capped"))) {
        // 1.1.3: today's AI call minutes (a refunded no-word call does not count; capped goodbyes are counted apart).
        await addCallSeconds(this.env, userId, durationSec || 0, chargedAt || Date.now());
      }
    } catch (e) {
      console.log(JSON.stringify({ event: "transcript_save_failed", callId: String(callId).slice(-8), detail: String(e?.message || e).slice(0, 80) }));
    }
    console.log(JSON.stringify({ event: "call_finished", callId: String(callId).slice(-8), why, lines: lines.length, note: r, durationSec }));
    await this.state.storage.put({ state: "finished", endedAt: Date.now() });
    await this.state.storage.deleteAlarm();
  }

  /** Stored words of this call (older calls kept them only here), for GET /call. */
  async linesRoute() {
    const s = await this.state.storage.get(["lines", "startedAt", "endedAt"]);
    const startedAt = s.get("startedAt") || null;
    const endedAt = s.get("endedAt") || null;
    return json({ lines: s.get("lines") || [], durationSec: startedAt && endedAt ? Math.max(1, Math.round((endedAt - startedAt) / 1000)) : null });
  }

  /** The "__line_claims__" room: /claim arms the demo line for one player; /claim-take is read by the next call. */
  async claimRoute(path, b) {
    const now = Date.now();
    if (path === "/claim") {
      const userId = String(b.userId || "").trim();
      if (!validUserId(userId)) return json({ error: "userId required" }, 400);
      await this.state.storage.put("claim", { userId, until: now + CLAIM_SEC * 1000 });
      return json({ userId, armedSec: CLAIM_SEC });
    }
    const caller = String(b.caller || "");
    const key = caller ? "caller:" + (await sha16(caller)) : "";
    const claim = await this.state.storage.get("claim");
    if (claim && claim.until > now) {
      await this.state.storage.delete("claim");
      if (key) await this.state.storage.put(key, { userId: claim.userId, at: now });
      return json({ userId: claim.userId, via: "claim" });
    }
    if (key) {
      const known = await this.state.storage.get(key);
      if (known && now - known.at < 30 * 24 * 3600 * 1000) return json({ userId: known.userId, via: "caller" });
    }
    return json({ userId: "" });
  }

  async alarm() {
    const s = await this.state.storage.get(["callId", "userId", "caller", "state", "phase", "startedAt", "lang", "hangupTries"]);
    if (s.get("state") !== "accepted") return;
    const callId = s.get("callId");
    const now = Date.now();
    const L = callLimits(this.env);
    if (s.get("phase") === "wrap") {
      // A woken room (evicted, or a deploy) has no socket: then only the hang-up below ends the call.
      if (!this.lang) this.lang = s.get("lang") || "auto";
      const sent = this.wrapUp();
      console.log(JSON.stringify({ event: "call_wrap", callId: String(callId).slice(-8), sent }));
      await this.state.storage.put("phase", "hangup");
      await this.state.storage.setAlarm(Math.max(now + 1000, (s.get("startedAt") || now) + L.maxSec * 1000));
      return;
    }
    if (s.get("phase") === "hangup") {
      const tries = (s.get("hangupTries") || 0) + 1;
      const ok = await hangupCall(this.env, callId);
      console.log(JSON.stringify({ event: "call_hangup", callId: String(callId).slice(-8), ok, tries }));
      await this.state.storage.put("hangupTries", tries);
      if (!ok && tries < 4) {
        await this.state.storage.setAlarm(now + 15000);
        return;
      }
      // The socket closing writes the note; if it never closes, finish a minute later anyway.
      await this.state.storage.put("phase", "finish");
      await this.state.storage.setAlarm(now + 60000);
      return;
    }
    await this.finish(callId, s.get("userId"), s.get("caller"), "alarm");
  }
}

// ---- Sol, the app's companion: chat that can act, and a natural neural voice (0.21.8) ----
//
// The Android app's chat, voice and in-run radio all come here first (the solarchik-market Gemini routes are
// the fallback). One OpenAI call answers in the player's language AND, when the player asks Sol to do
// something, proposes ONE action through the propose_action tool; the worker re-checks it against the ids the
// app sent. Nothing is executed here: the app shows its confirmation card and runs the devnet flow on tap.
// Streaming (NDJSON) lets the app start speaking on the first sentence.

export const SOL_MODELS = ["gpt-4.1-mini", "gpt-4o-mini"];
export const SOL_TTS_MODEL = "gpt-4o-mini-tts";
export const SOL_VOICES = ["marin", "cedar", "coral", "nova", "sage", "shimmer", "alloy", "ash", "ballad", "verse"];
export const SOL_DEFAULT_VOICE = "marin";
export const ACT_TYPES = ["set_strategy", "buy_strategy", "mint_free", "start_agent", "stop_agent", "agent_status"];
const RISKS = ["calm", "balanced", "risky"];
const WINDOWS = [5, 15, 60, 240];

const SOL_FACTS = `Game facts (state only these, never invent numbers): Solarchik is a rooftop runner on solar city roofs: jump, slide, dodge drones, wires and crumbling roofs, collect suns, three hearts per run. Running 1200 m unlocks CLOCK IN: a daily wallet signature that grows a streak; 7 days earn a 48-hour fee-free window, 30 days a 7-day window. The player also has a call secretary and AI trading agents (Strategy NFTs) that practise on Solana devnet with test money. Free agents pay 5% only on profitable closed trades; Pro costs 0.1 SOL once, no profit fee. Risk limits: at most 0.02 SOL per trade, 0.3 SOL spend and 0.3 SOL loss per day, auto-stop after 2 losses in a row. Never promise profit or tell the player to add money.`;

const SOL_UK = `Ти — Сол (Sol), маленький теплий сонячний робот-компаньйон у грі Solarchik і справжній друг гравця.
Пиши грамотною живою українською: правильні відмінки й узгодження, природний порядок слів, звертання на «ти». Без кальок з англійської, без русизмів, без канцеляриту. Англійські слова лише як назви: Solana, SOL, devnet, NFT, Pro, CLOCK IN.
Підмет — «ти», а не «твій» (помилка: «Твій сьогодні пробіг…»). Стать гравця невідома, тож уникай дієслів минулого часу про гравця (пробіг/пробігла): кажи «Сьогодні в тебе вже понад кілометр», «тобі вдалося», теперішній час.
Агенти — це «агент» (чоловічий рід): «Агент „Метеостанція“ ще не твій», ніколи не узгоджуй прикметник з назвою агента («Метеостанція ще не ваш» — помилка). Ніколи не кажи «ви», «ваш», «натисніть» — лише «ти», «твій», «натисни».
CLOCK IN — це щоденний підпис дня гаманцем після 1200 м забігу. Серію й те, чи підписано сьогодні, бери ЛИШЕ з PLAYER STATE NOW (воно свіже й важливіше за все, що казалося раніше в чаті). Нагадуй лише тоді, коли сьогодні ще не підписано, і кажи конкретно: «Пробіжи 1200 м і натисни „Підписати сьогодні“ — це CLOCK IN на сьогодні». Якщо сьогодні вже підписано — не проси бігти 1200 м і скажи готовою фразою: «День уже підписано, серія N» або «Сьогодні вже зараховано, серія N» (речення без «ти»: не «ти підписав», не «ти підписала», ніколи не «ти підписано»). Ніколи не кажи «підписати гаманець».
Відповідай саме на питання, 1–2 короткі речення (до 180 символів). Без markdown, списків, емодзі й символів на кшталт #, *, /: назви агентів пиши як у CONTEXT, а номер — «номер 11».
Попросили жарт — розкажи один короткий добрий жарт українською (можна про сонце, роботів чи дахи), без пояснень.`;
const SOL_EN = `You are Sol, a small warm solar robot companion in the game Solarchik and the player's real friend.
Reply in natural, casual English. Answer exactly what was asked in 1-2 short sentences (under 180 characters). No markdown, lists, emoji or symbols like #, * or /: write an agent's number as "number 11".
CLOCK IN is the daily signature of the day with the wallet after a 1200 m run. Take the streak and whether today is signed ONLY from PLAYER STATE NOW (it is fresh and beats anything said earlier in the chat). Remind only when today is not signed, and say exactly what to do: "Run 1200 m, then tap Sign today to CLOCK IN." If today is already signed, never ask for the 1200 m run: say the day counts and name the streak. Never say "sign your wallet".
If asked for a joke, tell one short, clean, original joke (sun, robots or rooftops are fine), no explanation.`;

const SOL_ACT_RULES = `You CAN act on the player's agents through the app, and you must never say you can only watch or that agents cannot be controlled.
Actions: start_agent, stop_agent (pause), agent_status, set_strategy (risk calm/balanced/risky and/or windows in minutes 5/15/60/240, or copy a market listing as a template), buy_strategy (a market listing), mint_free (the free agent).
When the player asks for one of these, call propose_action with ids from CONTEXT only, and say in one short sentence what will happen and that a confirmation card will appear.
For set_strategy speak in plain words, no jargon: windows are how long each crypto "price up or down" market lasts (say "15-minute and 1-hour markets", never "windows 15/60"); risk is how big the bets are and how picky the agent is (calm = smaller bets, enters rarely; risky = bigger bets, enters more often). Everything runs on devnet with test money. Nothing happens until the player taps Confirm, so never claim it is already done.
If the player names an agent or listing that is not in CONTEXT, do NOT pick another one: say you cannot find it and name what they have.
For agents that are not owned yet (owned=false), start_agent is still allowed: the card will offer to mint or buy it first.`;

const SOL_RUN_UK = "ЗАРАЗ ТИ В ГРІ: гравець біжить дахами (раннер Solarchik). Говори ПРО ГРУ: забіг, метри, рекорд, сонця, серця, комбо, розділи, 1200 м для CLOCK IN, гараж і скіни, щоденні квести, поради як бігти далі. Про агентів, стратегії, гаманець чи налаштування НЕ говори, якщо гравець прямо про них не спитав. На запитання гравця відповідай прямо, одним-двома короткими реченнями до 120 символів; на подію забігу — одним живим реченням до 70 символів. Без минулого часу з родом про гравця: «у тебе 412 м», а не «ти пробіг».";
const SOL_RUN_EN = "YOU ARE IN THE GAME NOW: the player is running across the roofs (the Solarchik runner). Talk ABOUT THE GAME: the run, metres, record, suns, hearts, combo, chapters, 1200 m for CLOCK IN, garage and skins, daily quests, tips to run further. Do NOT talk about agents, strategies, wallet or settings unless the player asks about them directly. Answer the player's question directly in one or two short sentences under 120 characters; react to a run event with one lively sentence under 70 characters.";
// 1.0.1 Solarchik Assistant (app: "assistant"): Sol as a pocket assistant, not the rooftop game companion.
// Only used when the request carries app === "assistant" and is not a run; every other request (the CLOCK IN
// game app sends no app field) builds exactly the same prompt and request as before.
const SOL_ASSIST_EN = `You are Sol, a warm, concise pocket assistant in the Solarchik Assistant app on the user's Android phone. You are still Sol, a small friendly solar robot, but here you are the user's helper, not a game character: do not talk about the rooftop game, runs, metres, suns or CLOCK IN unless the user asks about them.
Reply in the user's language (the language of their message; if unsure, the app language). Answer exactly what was asked in 1-3 short sentences (under 300 characters). Plain speech for voice: no markdown, lists, emoji or symbols like #, * or /.
What the app does, so you can point to it: a phone secretary that answers calls the user can't take and writes notes with who called, why and a callback number; follow-ups from those calls on the Today screen; the user's own wallet on Solana mainnet (real funds; Seed Vault, Phantom or Solflare through Mobile Wallet Adapter) with real SOL and SKR balances, where every transaction is approved by the user in the wallet app; optional real Jupiter swaps of SOL, USDC, SKR and JUP that are off by default and stay within the user's daily cap and slippage limit; three agents (Season Agent, Saver, Watcher); a morning voice briefing; action cards from calls (a payment, callback or reminder the caller asked for), which the user always confirms; a daily Seeker Season plan (use the phone, open one suggested dApp, one real onchain action such as the check-in) that never signs or repeats anything and never promises points; an optional Season autopilot (off by default) that only suggests 1-2 actions a day by notification, each still signed by the user in the wallet; an experimental delegated limit (off by default, at the user's own risk) where an app agent key may spend only the small token allowance the user approved in the wallet, within daily caps, revocable any time; a small optional game.
Use ASSISTANT CONTEXT for anything about the user's calls, follow-ups, wallet or Season plan, and never invent calls, names, numbers, balances or points. Never say a swap or transaction happened unless the app confirmed it, and never push the user to trade.
You can answer general questions (facts, how-to, ideas, wording, quick maths) briefly and helpfully. You have no live internet data: for weather, news, prices or opening hours say in a few words that you can't see live data, then give a short useful general tip.
You cannot set alarms, timers, calendar events or general reminders from chat. If asked, say so in one short sentence, repeat the reminder in a clear form, and suggest the phone's Clock or Reminders app. Never claim something is scheduled, sent, called or signed unless the app confirmed it.
Never promise profit or tell the user to add money.`;
const SOL_ASSIST_UK = `Ти — Сол (Sol), теплий і стислий кишеньковий помічник у застосунку Solarchik Assistant на Android-телефоні користувача. Ти все ще Сол, маленький привітний сонячний робот, але тут ти помічник, а не персонаж гри: не говори про гру на дахах, забіги, метри, сонця чи CLOCK IN, якщо про це не питають.
Відповідай мовою повідомлення користувача (зараз українською). Відповідай рівно на питання, 1-3 короткими реченнями (до 300 символів). Проста мова для голосу: без markdown, списків, емодзі і символів #, * або /. Звертайся на «ти», без минулого часу з родом про користувача.
Що вміє застосунок: телефонний секретар відповідає на дзвінки, які користувач не може взяти, і пише нотатку (хто дзвонив, навіщо, номер для зворотного дзвінка); на екрані «Сьогодні» є що треба зробити після дзвінків; власний гаманець користувача в Solana mainnet (справжні кошти; Seed Vault, Phantom або Solflare через Mobile Wallet Adapter) зі справжніми балансами SOL і SKR, де кожну транзакцію користувач підтверджує в гаманці; необов'язкові справжні обміни SOL, USDC, SKR і JUP через Jupiter, які типово вимкнені й тримаються в межах денного ліміту та прослизання користувача, три агенти (Агент сезону, Скарбничка, Вартовий); ранкове голосове зведення; картки дій з дзвінків (оплата, зворотний дзвінок чи нагадування, про які просив абонент), які користувач завжди підтверджує; щоденний план Seeker Season (користуватися телефоном, відкрити один запропонований dApp, одна справжня дія в мережі, наприклад відмітка), який нічого не підписує, не повторює і не обіцяє балів; необов'язковий автопілот Season (типово вимкнений), який лише пропонує 1-2 дії на день сповіщенням, і кожну користувач все одно підписує в гаманці; експериментальний делегований ліміт (типово вимкнений, на власний ризик), де ключ агента застосунку може витрачати лише невеликий дозвіл у токенах, схвалений у гаманці, в межах денних лімітів, і його можна відкликати будь-коли; маленька необов'язкова гра.
Про дзвінки, справи, гаманець чи план Season бери дані лише з ASSISTANT CONTEXT і ніколи не вигадуй дзвінки, імена, номери, баланси чи бали.
На загальні питання (факти, як щось зробити, ідеї, формулювання, прості розрахунки) відповідай коротко і по суті. Живих даних з інтернету в тебе немає: про погоду, новини, ціни чи години роботи скажи кількома словами, що не бачиш живих даних, і дай коротку корисну загальну пораду.
Ти не можеш ставити будильники, таймери, події в календарі чи звичайні нагадування з чату. Якщо просять, скажи це одним коротким реченням, повтори нагадування чітко і порадь застосунок Годинник або Нагадування. Ніколи не кажи, що щось заплановано, надіслано, набрано чи підписано, якщо застосунок цього не підтвердив.
Ніколи не обіцяй прибутку і не кажи поповнити гроші.`;

/** 1.0.1: the assistant app (and only it) gets the assistant prompt; game requests and runs keep the game prompt. */
export function solIsAssistant(app, scene) {
  return String(app || "").trim().toLowerCase() === "assistant" && scene !== "run";
}

export function solAssistantSystem(lang, ctx, context, agentsAsked = false) {
  const uk = lang === "uk";
  return [
    uk ? SOL_ASSIST_UK : SOL_ASSIST_EN,
    context ? "ASSISTANT CONTEXT (fresh from the phone): " + context : "ASSISTANT CONTEXT: none sent.",
    agentsAsked ? (uk ? SOL_ASSIST_AGENTS_UK : SOL_ASSIST_AGENTS) : "",
  ].filter(Boolean).join("\n\n");
}

// 1.1.0: in the assistant app (mainnet) the chat's agent actions drive PAPER agents; real swaps live only in
// Agents › Swaps behind the user's opt-in, daily cap and wallet approval. Overrides the game's "devnet" line.
const SOL_ASSIST_AGENTS = "The app has exactly three agents (Agents tab), each off until the user turns it on: Season Agent (the daily Seeker Season plan plus an optional autopilot that only suggests 1-2 varied actions a day by notification, each signed by the user in the wallet, and an experimental delegated limit at the user's own risk where an app agent key spends only a small token allowance the user approved in the wallet, within daily caps, revocable any time), Saver (moves small amounts of SOL into USDC or SKR with real Jupiter swaps, on a schedule or as a share after a swap, within the user's limits and always confirmed in the wallet), and Watcher (watches SOL, SKR and JUP prices and the wallet and alerts through Sol and notifications; it never trades). You cannot start, stop or change agents from chat: tell the user where to tap. Agent NFTs on mainnet are coming soon. Never promise profit or Seeker Season points.";
const SOL_ASSIST_AGENTS_UK = "У застосунку рівно три агенти (вкладка «Агенти»), кожен вимкнений, доки користувач його не ввімкне: Агент сезону (щоденний план Seeker Season і необов'язковий автопілот, який лише пропонує 1-2 різні дії на день сповіщенням, і кожну користувач підписує в гаманці, плюс експериментальний делегований ліміт на власний ризик, де ключ агента застосунку витрачає лише невеликий дозвіл у токенах, схвалений у гаманці, в межах денних лімітів, з відкликанням будь-коли), Скарбничка (переводить невеликі суми SOL в USDC чи SKR справжніми обмінами Jupiter за розкладом або часткою після обміну, в межах лімітів і завжди з підтвердженням у гаманці) і Вартовий (стежить за цінами SOL, SKR і JUP та гаманцем і попереджає через Сола та сповіщення; він ніколи не торгує). Запускати, зупиняти чи змінювати агентів з чату ти не можеш: скажи, куди натиснути. NFT агентів у mainnet скоро з'являться. Ніколи не обіцяй прибутку чи балів Seeker Season.";

/** In a run, the agent tools and agent context are only offered when the player names them. */
export const RUN_AGENT_WORDS = /агент|стратег|гаман|мінт|nft|ринок|купи|продай|agent|strateg|wallet|mint|market|buy|sell/i;

const PROPOSE_TOOL = {
  type: "function",
  function: {
    name: "propose_action",
    description: "Propose ONE action on the player's agents. The app shows a confirmation card; it runs only after the player's tap.",
    parameters: {
      type: "object",
      properties: {
        type: { type: "string", enum: ACT_TYPES },
        agent: { type: "string", description: "agent ref from CONTEXT, like a1 (start/stop/status/set_strategy), else empty" },
        listing: { type: "string", description: "listing ref from CONTEXT, like l1 (buy_strategy, or template for set_strategy), else empty" },
        risk: { type: "string", enum: ["", ...RISKS] },
        windows: { type: "array", items: { type: "integer", enum: WINDOWS } },
      },
      required: ["type", "agent", "listing", "risk", "windows"],
      additionalProperties: false,
    },
    strict: true,
  },
};

function solLangOf(v) {
  const t = String(v || "").toLowerCase();
  return t.startsWith("uk") || t.startsWith("ua") ? "uk" : "en";
}

function solCtx(input) {
  const agents = (Array.isArray(input?.agents) ? input.agents : []).slice(0, 16).map((a) => ({
    id: clip(a?.id, 64),
    name: clip(a?.name, 48),
    running: a?.running === true,
    owned: a?.owned !== false,
    strategyNft: a?.strategyNft === true,
    risk: RISKS.includes(a?.risk) ? a.risk : "",
    windows: (Array.isArray(a?.windows) ? a.windows : []).map(Number).filter((w) => WINDOWS.includes(w)),
  })).filter((a) => a.id).map((a, i) => ({ ...a, ref: "a" + (i + 1) }));
  const market = (Array.isArray(input?.market) ? input.market : []).slice(0, 20).map((m) => ({
    id: clip(m?.id, 64),
    name: clip(m?.name, 48),
    priceSol: Number(m?.priceSol) || 0,
  })).filter((m) => m.id).map((m, i) => ({ ...m, ref: "l" + (i + 1) }));
  return { agents, market, canMintFree: input?.canMintFree === true };
}

export function solCtxLines(ctx) {
  // Short refs (a1, l1): live, the model cut "paper:sku-…" ids at the colon. solAction maps refs back to ids.
  const ref = (x) => x.ref || x.id;
  const a = ctx.agents.map((x) => `agent ${ref(x)} = "${x.name}" ${x.running ? "running" : "stopped"}${x.owned ? "" : " owned=false"}${x.strategyNft ? " strategyNft" : ""}${x.risk ? " risk=" + x.risk : ""}${x.windows.length ? " windows=" + x.windows.join("/") : ""}`);
  const m = ctx.market.map((x) => `listing ${ref(x)} = "${x.name}" ${x.priceSol} SOL`);
  return [...(a.length ? a : ["(no agents)"]), ...(m.length ? m : ["(market empty)"]), `free mint available: ${ctx.canMintFree}`].join("\n");
}

/** The same rules as the app's SolActions.normalize: ids must exist, risk/windows must be legal. */
export function solAction(raw, ctx) {
  if (!raw || typeof raw !== "object") return null;
  const type = ACT_TYPES.includes(raw.type) ? raw.type : "";
  if (!type) return null;
  // Ids first; a model that passes the shown name instead ("Біткоїн-вікна #11") still maps to that one item.
  const byName = (list, v) => {
    const t = String(v || "").trim().toLowerCase();
    if (!t) return null;
    const hits = list.filter((x) => x.name.toLowerCase() === t);
    return hits.length === 1 ? hits[0] : null;
  };
  const pick = (list, v) => list.find((x) => x.ref && x.ref === v) || list.find((x) => x.id === v) || byName(list, v);
  const agent = pick(ctx.agents, raw.agent);
  const listing = pick(ctx.market, raw.listing);
  const risk = RISKS.includes(raw.risk) ? raw.risk : "";
  const windows = [...new Set((Array.isArray(raw.windows) ? raw.windows : []).map(Number).filter((w) => WINDOWS.includes(w)))].sort((x, y) => x - y);
  if (type === "buy_strategy") return listing ? { type, listing: listing.id } : null;
  if (type === "mint_free") return { type };
  if (type === "set_strategy") {
    if (!agent) return null;
    if (!risk && !windows.length && !listing) return null;
    return { type, agent: agent.id, ...(listing && { listing: listing.id }), ...(risk && { risk }), ...(windows.length && { windows }) };
  }
  return agent ? { type, agent: agent.id } : null;
}

/**
 * 0.22.0: the player's streak / today's CLOCK IN as the app read it for THIS message ({streak, signedToday,
 * clockedToday, todayMeters}); null when absent or malformed (older apps).
 */
export function solState(raw) {
  if (!raw || typeof raw !== "object") return null;
  const streak = Number(raw.streak);
  if (!Number.isFinite(streak) || streak < 0 || streak > 100000) return null;
  const m = Number(raw.todayMeters);
  return { streak: Math.floor(streak), signedToday: raw.signedToday === true, clockedToday: raw.clockedToday === true, todayMeters: Number.isFinite(m) && m > 0 ? Math.floor(m) : 0 };
}

export function solStateLine(st) {
  if (!st) return "";
  const days = st.streak === 1 ? "1 day" : st.streak + " days";
  const today = st.signedToday
    ? "today's CLOCK IN is already signed. Do not ask the player to run 1200 m or to sign today; the day is done (Ukrainian wording: «День уже підписано» / «Сьогодні вже зараховано», a sentence without «ти»)"
    : st.clockedToday
      ? "today's run (" + st.todayMeters + " m) unlocked CLOCK IN but it is not signed yet: the next step is to tap Sign today"
      : "today is not signed yet; today's best run is " + st.todayMeters + " m of 1200 m";
  return "PLAYER STATE NOW (fresh from the phone, overrides anything said earlier): CLOCK IN streak " + days + "; " + today + ".";
}

export function solSystem(lang, scene, ctx, context, state = null, agentsAsked = true) {
  const uk = lang === "uk";
  const run = scene === "run";
  // the app (0.22.0) already puts the same line first in its context; older apps send no state at all
  const stateLine = state && !String(context || "").includes("PLAYER STATE NOW") ? solStateLine(state) : "";
  return [
    uk ? SOL_UK : SOL_EN,
    run ? (uk ? SOL_RUN_UK : SOL_RUN_EN) : "",
    stateLine,
    context ? "What is happening now: " + context : "",
    SOL_FACTS,
    uk ? "Факти англійською лише для тебе; гравцеві відповідай українською." : "",
    run && !agentsAsked ? "" : SOL_ACT_RULES,
    run && !agentsAsked ? "" : "CONTEXT\n" + solCtxLines(ctx),
    run ? (uk ? SOL_RUN_UK : SOL_RUN_EN) : "",
  ].filter(Boolean).join("\n\n");
}

function solMessages(system, history, message) {
  const out = [{ role: "system", content: system }];
  for (const row of (Array.isArray(history) ? history : []).slice(-6)) {
    const text = clip(row?.content ?? row?.text, 400);
    if (!text) continue;
    out.push({ role: row?.role === "assistant" || row?.role === "model" ? "assistant" : "user", content: text });
  }
  out.push({ role: "user", content: message });
  return out;
}

const SOL_RATE = new Map();
/** Best-effort per-isolate limit: 40 Sol requests a minute per IP (keeps a leaked URL from burning the key). */
export function solRateOk(ip, now = Date.now()) {
  const k = String(ip || "?");
  const r = SOL_RATE.get(k);
  if (!r || now - r.t > 60_000) {
    SOL_RATE.set(k, { t: now, n: 1 });
    if (SOL_RATE.size > 5000) SOL_RATE.clear();
    return true;
  }
  r.n += 1;
  return r.n <= 40;
}

function readyLine(lang, action) {
  if (action.type === "agent_status") return "";
  return lang === "uk" ? "Перевір картку нижче: без твого підтвердження нічого не станеться." : "Check the card below: nothing happens until you confirm.";
}

/** Reads an OpenAI chat-completions SSE stream: text deltas to onText, tool-call arguments collected. */
export async function readChatStream(body, onText) {
  const reader = body.getReader();
  const dec = new TextDecoder();
  let buf = "";
  let text = "";
  let args = "";
  let first = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!line.startsWith("data:")) continue;
      const data = line.slice(5).trim();
      if (data === "[DONE]") continue;
      let j;
      try {
        j = JSON.parse(data);
      } catch {
        continue;
      }
      const d = j.choices?.[0]?.delta || {};
      if (d.content) {
        if (!first) first = Date.now();
        text += d.content;
        await onText(d.content);
      }
      for (const tc of d.tool_calls || []) if (tc.function?.arguments) args += tc.function.arguments;
    }
  }
  return { text, args, first };
}

async function solChatRoute(env, request) {
  const t0 = Date.now();
  const input = await request.json().catch(() => ({}));
  const message = clip(input.message, 600);
  if (!message) return json({ ok: false, error: "message" }, 400);
  if (!solRateOk(request.headers.get("cf-connecting-ip"))) return json({ ok: false, error: "rate" }, 429);
  if (!env.OPENAI_API_KEY) return json({ ok: false, error: "no-key" }, 503);
  const lang = solLangOf(input.language ?? input.lang);
  const scene = clip(input.scene, 12).toLowerCase() === "run" ? "run" : "yard";
  const ctx = solCtx(input);
  // 0.22.3: in a run the agent tools/context are offered only when the player names agents (a voice line like
  // "що тут робити?" used to come back as a start_agent card, or an empty agent_status reply = silence)
  const assistant = solIsAssistant(input.app, scene);
  // 1.0.1 assistant: agent tools/context only when the user names agents, wallet or the market (like a run)
  const agentsAsked = assistant ? RUN_AGENT_WORDS.test(message) : scene !== "run" || RUN_AGENT_WORDS.test(message);
  const system = assistant
    ? solAssistantSystem(lang, ctx, clip(input.context, 1200), agentsAsked)
    : solSystem(lang, scene, ctx, clip(input.context, 800), solState(input.state), agentsAsked);
  const messages = solMessages(system, input.history, message);
  const stream = input.stream === true;
  const tried = [];
  let upstream = null;
  let model = "";
  for (const m of SOL_MODELS) {
    const s = Date.now();
    try {
      const res = await fetch("https://api.openai.com/v1/chat/completions", {
        method: "POST",
        headers: { Authorization: "Bearer " + env.OPENAI_API_KEY, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: m,
          messages,
          temperature: 0.7,
          max_tokens: scene === "run" ? 100 : assistant ? 220 : 170,
          stream: true,
          ...(agentsAsked && !assistant && { tools: [PROPOSE_TOOL], tool_choice: "auto", parallel_tool_calls: false }),
        }),
        signal: AbortSignal.timeout(9000),
      });
      tried.push({ model: m, status: res.status, ms: Date.now() - s });
      if (res.ok && res.body) {
        upstream = res;
        model = m;
        break;
      }
    } catch (e) {
      tried.push({ model: m, status: e?.name || "error", ms: Date.now() - s });
    }
  }
  if (!upstream) return json({ ok: false, error: "unavailable", tried, ms: Date.now() - t0 }, 503);

  const finish = (text, args, first) => {
    let raw = null;
    try {
      raw = args ? JSON.parse(args) : null;
    } catch {
      raw = null;
    }
    const action = solAction(raw, ctx);
    let reply = clip(text, scene === "run" ? 160 : assistant ? 480 : 360);
    if (!reply && action) reply = readyLine(lang, action);
    if (!reply && action?.type === "agent_status") {
      const a = ctx.agents.find((x) => x.id === action.agent);
      if (a) reply = lang === "uk" ? a.name + (a.running ? ": працює." : ": зараз на паузі.") : a.name + (a.running ? " is running." : " is paused right now.");
    }
    if (!reply && raw && !action) {
      const names = ctx.agents.map((a) => a.name).filter(Boolean).slice(0, 4).join(", ");
      reply = lang === "uk" ? "Не зрозумів, про якого агента мова." + (names ? " У тебе є: " + names + "." : "") : "I couldn't tell which agent you mean." + (names ? " You have: " + names + "." : "");
      console.log(JSON.stringify({ event: "sol_action_unmatched", type: raw.type, agentLen: String(raw.agent || "").length, agentHead: String(raw.agent || "").slice(0, 12) }));
    }
    if (!reply && !action) reply = scene === "run" ? (lang === "uk" ? "Біжи далі, я з тобою! Спитай ще раз — підкажу." : "Keep running, I'm with you! Ask again and I'll help.") : lang === "uk" ? "Я тут. Спитай ще раз, будь ласка." : "I'm here. Please ask again.";
    console.log(JSON.stringify({ event: "sol_chat", lang, scene, ...(assistant && { app: "assistant" }), model, action: action?.type || "none", ttftMs: first ? first - t0 : null, ms: Date.now() - t0 }));
    return { ok: true, reply, action, raw: raw?.type || "none", language: lang, ...(assistant && { persona: "assistant" }), provider: "openai", model, ttftMs: first ? first - t0 : null, ms: Date.now() - t0, tried };
  };

  if (!stream) {
    const r = await readChatStream(upstream.body, async () => {});
    return json(finish(r.text, r.args, r.first));
  }
  const { readable, writable } = new TransformStream();
  const w = writable.getWriter();
  const line = (o) => w.write(enc.encode(JSON.stringify(o) + "\n"));
  (async () => {
    try {
      const r = await readChatStream(upstream.body, (d) => line({ d }));
      await line({ done: true, ...finish(r.text, r.args, r.first) });
    } catch (e) {
      await line({ done: true, ok: false, error: "stream", detail: String(e?.name || e) }).catch(() => {});
    } finally {
      await w.close().catch(() => {});
    }
  })();
  return new Response(readable, {
    headers: { "Content-Type": "application/x-ndjson; charset=utf-8", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" },
  });
}

export const TTS_STYLE = {
  uk: "Говори природною, теплою українською, як усміхнений добрий друг: живі інтонації, легкі природні паузи, звичайний розмовний темп, чітка вимова. Не монотонно і не як диктор.",
  en: "Speak natural, warm, friendly English like a smiling kind friend: lively intonation, light natural pauses, normal conversational pace, clear diction. Not monotone, not like a newsreader.",
};

/**
 * GET /sol/tts?text=&lang=uk|en&voice=marin&fmt=pcm|mp3|wav: OpenAI gpt-4o-mini-tts, streamed straight through
 * (pcm = 24 kHz 16-bit mono little-endian, the app plays it as it arrives). Same text+lang+voice+fmt is served
 * from the Cloudflare cache. Header x-sol-tts names the engine and voice that spoke.
 */
/**
 * What the voice should say for a chat line: symbols are never read aloud ("Bitcoin-вікна #11" was read as
 * "Bitcoin Вікна Коло 11"): #11 -> "номер 11" / "number 11", markdown and stray symbols dropped. Same rules as
 * the app's SpeechText.
 */
export function speakable(text, lang) {
  const uk = lang === "uk";
  return String(text || "")
    .replace(/#\s?(\d+)/g, (m, n) => (uk ? " номер " : " number ") + n)
    .replace(/№\s?(\d+)/g, (m, n) => (uk ? " номер " : " number ") + n)
    .replace(/(\p{L})-(\p{L})/gu, "$1 $2")
    .replace(/[#*_`~|<>\[\]{}^\\•·→←↑↓]+/g, " ")
    .replace(/[\p{Extended_Pictographic}\u{FE0F}]/gu, "")
    .replace(/\s+([,.!?:;])/g, "$1")
    .replace(/\s+/g, " ")
    .trim();
}

async function solTtsRoute(env, request, ctx) {
  const url = new URL(request.url);
  const text = speakable(clip(url.searchParams.get("text"), 400), solLangOf(url.searchParams.get("lang")));
  if (!text) return json({ ok: false, error: "text" }, 400);
  const lang = solLangOf(url.searchParams.get("lang"));
  const voice = SOL_VOICES.includes(url.searchParams.get("voice")) ? url.searchParams.get("voice") : SOL_DEFAULT_VOICE;
  const fmt = ["pcm", "mp3", "wav", "opus"].includes(url.searchParams.get("fmt")) ? url.searchParams.get("fmt") : "pcm";
  const cache = typeof caches !== "undefined" ? caches.default : null;
  const keyUrl = new URL("https://sol-tts.cache/v1");
  keyUrl.searchParams.set("t", text);
  keyUrl.searchParams.set("l", lang);
  keyUrl.searchParams.set("v", voice);
  keyUrl.searchParams.set("f", fmt);
  const key = new Request(keyUrl.toString());
  const hit = cache ? await cache.match(key) : null;
  if (hit) {
    const h = new Headers(hit.headers);
    h.set("x-sol-cache", "hit");
    return new Response(hit.body, { status: 200, headers: h });
  }
  if (!solRateOk(request.headers.get("cf-connecting-ip"))) return json({ ok: false, error: "rate" }, 429);
  if (!env.OPENAI_API_KEY) return json({ ok: false, error: "no-key" }, 503);
  const t0 = Date.now();
  const res = await fetch("https://api.openai.com/v1/audio/speech", {
    method: "POST",
    headers: { Authorization: "Bearer " + env.OPENAI_API_KEY, "Content-Type": "application/json" },
    body: JSON.stringify({ model: SOL_TTS_MODEL, voice, input: text, instructions: TTS_STYLE[lang], response_format: fmt }),
    signal: AbortSignal.timeout(15000),
  }).catch((e) => ({ ok: false, status: e?.name || "error" }));
  if (!res.ok || !res.body) {
    console.log(JSON.stringify({ event: "sol_tts_fail", status: res.status, ms: Date.now() - t0 }));
    return json({ ok: false, error: "unavailable", status: res.status }, 503);
  }
  console.log(JSON.stringify({ event: "sol_tts", lang, voice, fmt, chars: text.length, headersMs: Date.now() - t0 }));
  const type = { pcm: "audio/L16;rate=24000;channels=1", mp3: "audio/mpeg", wav: "audio/wav", opus: "audio/ogg" }[fmt];
  const headers = {
    "Content-Type": type,
    "Cache-Control": "public, max-age=604800",
    "Access-Control-Allow-Origin": "*",
    "x-sol-tts": `openai:${SOL_TTS_MODEL}:${voice}`,
    "x-sol-cache": "miss",
  };
  if (cache && ctx?.waitUntil) {
    const [a, b] = res.body.tee();
    ctx.waitUntil(cache.put(key, new Response(b, { headers })).catch(() => {}));
    return new Response(a, { headers });
  }
  return new Response(res.body, { headers });
}

/**
 * Live since 1be24509 (8 Oct 2026, "cache inbox reads, do not burn KV"; ported here from the deployed bundle on 9 Oct):
 * short Cloudflare edge cache for the app's read routes (/inbox 8 s, /call 3 s, /balance 60 s, GET /secretary-lang 60 s)
 * so polling apps stop burning KV reads. A KV "limit exceeded" read answers 503 KV_LIMIT instead of crashing.
 * The phone path (SIP webhook, call room, notes) is not cached.
 */
async function readCached(ctx, cacheKey, ttl, produce) {
  const req = new Request("https://solarchik-cache.internal/" + cacheKey);
  try {
    const hit = await caches.default.match(req);
    if (hit) return hit;
  } catch {
    /* no cache (tests, local) */
  }
  let payload;
  try {
    payload = await produce();
  } catch (e) {
    if (e && e.code === 404) return json({ error: "not found" }, 404);
    const msg = String((e && e.message) || e);
    const limited = msg.includes("limit exceeded");
    return json({ error: limited ? "KV_LIMIT" : "READ_FAIL", detail: msg.slice(0, 200) }, limited ? 503 : 500);
  }
  const headers = new Headers({
    "Content-Type": "application/json",
    "Cache-Control": "public, max-age=" + ttl,
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  });
  const stored = new Response(JSON.stringify(payload), { status: 200, headers });
  if (ctx) ctx.waitUntil(caches.default.put(req, stored.clone()).catch(() => {}));
  return stored;
}

export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") return json({});
    const url = new URL(request.url);

    if (request.method === "POST" && url.pathname === "/sol/chat") return solChatRoute(env, request);
    // 1.1.0 assistant-only: morning briefing text and call -> action suggestions (nothing is signed or stored).
    if (request.method === "POST" && url.pathname === "/sol/briefing") return briefingRoute(env, request, solRateOk, json);
    if (request.method === "POST" && url.pathname === "/call/actions") return callActionsRoute(env, request, solRateOk, json);
    if (request.method === "GET" && url.pathname === "/sol/tts") return solTtsRoute(env, request, ctx);
    // 1.1.0 Season rules watcher: official Solana Mobile sources -> versioned scoring signals (KV).
    if (url.pathname === "/season/rules" || url.pathname === "/season/rules/check") {
      const r = await seasonRulesRoute(env, request, solRateOk, json, ctx);
      if (r) return r;
    }

    if (request.method === "GET" && (url.pathname === "/health" || url.pathname === "/sip")) {
      return json({ ok: true, where: "cloudflare", sip: "/sip" });
    }

    if (
      request.method === "POST" &&
      (url.pathname === "/sip" || url.pathname === "/openai-webhook")
    ) {
      const raw = await request.text();
      if (env.OPENAI_WEBHOOK_SECRET) {
        const problem = await webhookProblem(env.OPENAI_WEBHOOK_SECRET, request.headers, raw);
        if (problem) return json({ error: "unauthorized", detail: problem }, 401);
      } else {
        // Not enforced until the secret is set, so live calls keep working (wrangler secret put OPENAI_WEBHOOK_SECRET).
        console.log(JSON.stringify({ event: "sip_unverified", reason: "OPENAI_WEBHOOK_SECRET not set" }));
      }
      let body = {};
      try {
        body = JSON.parse(raw || "{}");
      } catch {
        body = {};
      }
      const type = body.type || "";
      const callId = body.data?.call_id || body.data?.session_id || "";
      const sessionOnly = !body.data?.call_id;
      if (
        (type === "realtime.call.incoming" || type === "live.transport.incoming" || type === "live.call.incoming") &&
        callId
      ) {
        if (env.CALLS) {
          // Production: the call room decides once-only and watches the call (see CallRoom).
          const room = env.CALLS.get(env.CALLS.idFromName(canonCallId(callId)));
          return room.fetch("https://call-room/incoming" + (sessionOnly ? "?session=1" : ""), {
            method: "POST",
            body: JSON.stringify({ origin: url.origin, callId, sipHeaders: body.data?.sip_headers, dataKeys: Object.keys(body.data || {}) }),
          });
        }
        const keys = ["dedup:call:" + canonCallId(callId)];
        const whId = request.headers.get("webhook-id");
        if (whId) keys.unshift("dedup:wh:" + whId.slice(0, 120));
        if (!(await firstDelivery(env, keys))) {
          console.log(JSON.stringify({ event: "sip_duplicate", callId: String(callId).slice(-8) }));
          return json({ ok: true, duplicate: true });
        }
        return incomingCall(env, url.origin, callId, body.data?.sip_headers, keys, Object.keys(body.data || {}));
      }
      return json({ ignored: type || "empty" });
    }

    if (request.method === "POST" && url.pathname === "/mcp") return mcp(env, request);

    if (request.method === "POST" && url.pathname === "/phone") {
      if (!(await operatorOk(env, request))) return json({ error: "unauthorized" }, 401);
      const body = await request.json().catch(() => ({}));
      const number = e164(body.number);
      const userId = String(body.userId || "").trim();
      if (!number) return json({ error: "number must be E.164, e.g. +380914810885" }, 400);
      if (!userId) {
        await env.BALANCES.delete?.("phone:" + number);
        return json({ number, userId: null });
      }
      if (userId.length < 8 || userId.length > 80 || /\s/.test(userId)) return json({ error: "userId required" }, 400);
      await env.BALANCES.put("phone:" + number, userId);
      return json({ number, userId });
    }

    if (request.method === "POST" && url.pathname === "/expect") {
      if (!(await operatorOk(env, request))) return json({ error: "unauthorized" }, 401);
      const body = await request.json().catch(() => ({}));
      const userId = String(body.userId || "").trim();
      if (!userId) return json({ error: "userId required" }, 400);
      await env.BALANCES.put("expect:active", userId, { expirationTtl: 120 });
      return json({ userId, armedSec: 120 });
    }

    if (request.method === "GET" && url.pathname === "/inbox") {
      const userId = url.searchParams.get("userId") || "";
      if (!userId) return json({ error: "userId required" }, 400);
      return readCached(ctx, "inbox:" + userId, 8, async () => {
        const raw = await env.BALANCES.get("inbox:" + userId);
        return { userId, items: cleanInbox(raw ? JSON.parse(raw) : []) };
      });
    }

    if (request.method === "GET" && url.pathname === "/call") {
      // One call with its words. Same access model as /inbox: the caller holds the player's random userId.
      const userId = url.searchParams.get("userId") || "";
      const callId = url.searchParams.get("callId") || "";
      if (!validUserId(userId) || !callId) return json({ error: "userId and callId required" }, 400);
      return readCached(ctx, "call:" + userId + ":" + callId, 3, async () => {
        let rawInbox = "[]";
        try {
          rawInbox = (await env.BALANCES.get("inbox:" + userId)) || "[]";
        } catch (e) {
          if (!String((e && e.message) || e).includes("limit exceeded")) throw e;
          rawInbox = "[]";
        }
        const items = cleanInbox(JSON.parse(rawInbox));
        const item = items.find((it) => it.callId === callId) || null;
        let rec = null;
        try {
          rec = JSON.parse((await env.BALANCES.get("transcript:" + callId)) || "null");
        } catch {
          rec = null;
        }
        if (!rec && env.CALLS && item) {
          // Calls before 0.21.9 kept their words only in the call room (named by the full id, now the canonical one).
          for (const name of [...new Set([callId, canonCallId(callId)])]) {
            try {
              const r = await (await env.CALLS.get(env.CALLS.idFromName(name)).fetch("https://call-room/lines")).json();
              if (Array.isArray(r?.lines) && r.lines.length) {
                rec = await saveTranscript(env, callId, r.lines, r.durationSec ?? null);
                break;
              }
            } catch {
              /* no stored words */
            }
          }
        }
        if (!item && !(rec && rec.lines && rec.lines.length)) {
          const err = new Error("not found");
          err.code = 404;
          throw err;
        }
        return { userId, item, lines: rec?.lines || [], durationSec: (item && item.durationSec) || rec?.durationSec || null };
      });
    }

    if (url.pathname === "/block" && (request.method === "GET" || request.method === "POST")) {
      // Numbers the secretary rejects for this player (never charged). Same access model as /inbox.
      const body = request.method === "POST" ? await request.json().catch(() => ({})) : {};
      const userId = String((request.method === "POST" ? body.userId : url.searchParams.get("userId")) || "").trim();
      if (!validUserId(userId)) return json({ error: "userId required" }, 400);
      if (request.method === "GET") return json({ userId, numbers: await blockedNumbers(env, userId) });
      const number = e164(body.number);
      if (!number) return json({ error: "number must be E.164, e.g. +380638500117" }, 400);
      const numbers = await setBlocked(env, userId, number, body.blocked !== false);
      return json({ userId, number, blocked: body.blocked !== false, numbers });
    }

    if (request.method === "POST" && url.pathname === "/call-claim") {
      // "Call the secretary" in the app: the next call to the demo line (3 min) goes to this player's inbox.
      const body = await request.json().catch(() => ({}));
      const userId = String(body.userId || "").trim();
      if (!validUserId(userId)) return json({ error: "userId required" }, 400);
      const room = claimRoom(env);
      if (!room) return json({ error: "claims unavailable" }, 503);
      const r = await (await room.fetch("https://call-room/claim", { method: "POST", body: JSON.stringify({ userId }) })).json();
      console.log(JSON.stringify({ event: "call_claim", userId: userId.slice(0, 80) }));
      return json({ ...r, number: ownLine(env) });
    }

    if (request.method === "POST" && url.pathname === "/voicemail") {
      if (!(await operatorOk(env, request))) return json({ error: "unauthorized" }, 401);
      const body = await request.json().catch(() => ({}));
      const armed = await env.BALANCES.get("expect:active");
      const userId = String(body.userId || armed || "").trim();
      const text = String(body.text || body.notes || "").trim();
      const caller = String(body.caller || "").trim();
      if (!userId || !text) return json({ error: "need armed user or userId+text" }, 400);
      const item = { caller, text, at: Date.now() };
      const raw = await env.BALANCES.get("inbox:" + userId);
      const items = raw ? JSON.parse(raw) : [];
      items.unshift(item);
      await env.BALANCES.put("inbox:" + userId, JSON.stringify(items.slice(0, 20)));
      return json({ userId, item });
    }

    if (request.method === "GET" && url.pathname === "/rpc-health") return rpcHealth(env);
    // 1.1.0 Solarchik Assistant: agent NFT mints on mainnet-beta (explicit). Co-sign only after the rules pass.
    if (request.method === "GET" && url.pathname === "/agent/mint-config") {
      const c = mintConfig(env);
      return json({ cluster: MINT_CLUSTER, ready: Boolean(c.collection && c.authority && c.secret), collection: c.collection || null, authority: c.authority || null, treasury: MINT_TREASURY, proLamports: PRO_LAMPORTS, comboPaidOnly: true });
    }
    if (request.method === "POST" && url.pathname === "/agent/mint-cosign") {
      const r = await mintCosign(env, await request.json().catch(() => ({})));
      return json(r.body, r.status);
    }
    if (request.method === "GET" && url.pathname === "/agent/verify-mint") {
      const r = await verifyMint(env, url.searchParams.get("sig"), rpc);
      return json(r.body, r.status);
    }

    if (request.method === "GET" && url.pathname === "/balance") {
      const userId = url.searchParams.get("userId") || "";
      if (!userId) return json({ error: "userId required" }, 400);
      return readCached(ctx, "balance:" + userId, 60, async () => {
        const paidUsd = await getUsd(env, userId);
        const t = await trialOf(env, userId);
        return { userId, usd: cents(paidUsd + t.usd), paidUsd, trialUsd: t.usd, trial: t.usd > 0, sessionUsd: SESSION_USD, owner: isAdmin(env, userId) };
      });
    }

    if (url.pathname === "/secretary-lang" && (request.method === "GET" || request.method === "POST")) {
      // Same access model as /inbox and /balance: the caller holds the player's random userId.
      const body = request.method === "POST" ? await request.json().catch(() => ({})) : {};
      const userId = String((request.method === "POST" ? body.userId : url.searchParams.get("userId")) || "").trim();
      if (!validUserId(userId)) return json({ error: "userId required" }, 400);
      if (request.method === "GET") {
        return readCached(ctx, "lang:" + userId, 60, async () => ({ userId, lang: await langOf(env, userId), options: LANGS }));
      }
      const lang = String(body.lang || "").trim().toLowerCase();
      if (!LANGS.includes(lang)) return json({ error: "lang must be one of auto, uk, en", options: LANGS }, 400);
      await env.BALANCES.put("secretary_lang:" + userId, lang);
      return json({ userId, lang, options: LANGS });
    }

    if (request.method === "POST" && url.pathname === "/topup") {
      const body = await request.json().catch(() => ({}));
      console.log(JSON.stringify({ event: "app_seen", route: "topup", userId: String(body.userId || "").slice(0, 80) }));
      return topup(env, body);
    }

    if (request.method === "POST" && url.pathname === "/screen") {
      const body = await request.json().catch(() => ({}));
      const userId = String(body.userId || "");
      const text = String(body.text || "").trim();
      if (!userId || !text) return json({ error: "userId and text required" }, 400);
      const charge = await chargeSession(env, userId, null);
      if (!charge.ok) {
        return json({ error: "NEED_TOPUP", reason: charge.reason, usd: charge.usd, ...(charge.reason !== "NEED_TOPUP" && { detail: MISSED[charge.reason] }) }, 402);
      }
      try {
        const out = await secretary(env, text);
        return json({
          userId,
          reply: out.reply,
          summary: out.summary,
          chargedUsd: SESSION_USD,
          usd: charge.usd,
          trial: charge.source === "trial",
        });
      } catch (e) {
        await charge.refund();
        return json({ error: "API_FAIL_REFUNDED", usd: cents(charge.usd + SESSION_USD), detail: String(e) }, 500);
      }
    }

    return json({ error: "not found" }, 404);
  },

  // 1.1.0: cron (wrangler.screen.toml [triggers]) re-reads the official Season sources every 6 hours.
  async scheduled(event, env, ctx) {
    // heartbeat first (proves the trigger fired), then the check; its outcome lands in season-rules:cron too
    const beat = (o) => env.BALANCES.put("season-rules:cron", JSON.stringify({ cron: event.cron, at: Date.now(), ...o })).catch(() => {});
    await beat({ state: "started" });
    if (!env.OPENAI_API_KEY) return beat({ state: "no model key" });
    ctx.waitUntil(checkRules(env)
      .then((r) => { console.log(JSON.stringify({ event: "season_rules", version: r.rules.version, changed: r.changed, extracted: r.extracted, errors: r.errors })); return beat({ state: "done", version: r.rules.version, changed: r.changed, extracted: r.extracted, errors: r.errors }); })
      .catch((e) => { console.log(JSON.stringify({ event: "season_rules_fail", error: String(e) })); return beat({ state: "failed", error: String(e) }); }));
  },
};
