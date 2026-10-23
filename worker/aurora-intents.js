/**
 * Encode Solana Hackathon (Oct 2026): "Pay from any chain" for Sol's Circle requests, on Aurora Intents
 * (powered by NEAR Intents). https://docs.intents.aurora.dev
 *
 * Sol already turns "you owe me 5 USDC" into a Solana Pay link. That only works if the friend has a Solana wallet.
 * This module gives every request a web page where the friend can pay from ~30 other chains (USDC on Base, USDT on
 * Tron, ETH, BTC, NEAR, ...). It uses the Aurora Intents Swap API with swapType EXACT_OUTPUT, so the requester
 * always receives exactly the requested USDC or SOL on Solana mainnet; the friend's side is quoted, and anything
 * that goes wrong is refunded to the friend's own address on the chain they paid from.
 *
 *   GET  /anychain/sources                 origin assets a friend can pay with (from GET /api/tokens)
 *   POST /anychain/request                 the app registers a request {to, amount, token, who, note, reference}
 *   GET  /anychain/request/:id             public summary + settlement (the app polls this for Paid ✓)
 *   POST /anychain/quote                   {id, origin, refundTo} -> deposit address + exact amount (POST /api/quote)
 *   POST /anychain/submit                  {id, depositAddress, txHash} -> POST /api/deposit/submit (faster detection)
 *   GET  /anychain/status?id=..            polls GET /api/status for the request's live deposit addresses
 *   GET  /r/:id                            the pay page (anychain-page.js)
 *
 * The worker never holds funds or keys. The Aurora API key is a public integrator key (docs: "not confidential"),
 * kept in env.AURORA_API_KEY so it can be rotated without a code change.
 */

export const AURORA_API = "https://intents-api.aurora.dev";

/** What a request can ask for on Solana (USDC mint and native SOL). SKR is not listed by Aurora. */
export const DEST = {
  USDC: { assetId: "nep141:sol-5ce3bf3a31af18be40ba30f721101b4341690186.omft.near", decimals: 6, mint: "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" },
  SOL: { assetId: "nep141:sol.omft.near", decimals: 9, mint: null },
};
export const MAX_AMOUNT = { USDC: 1000, SOL: 10 };

/** "2026-10-23T11:32:31.199Z" -> "Oct 23, 11:32 UTC" (payer-neutral; the page also shows local time). */
export function fmtUtc(iso) {
  const d = new Date(iso);
  if (isNaN(d)) return String(iso);
  const M = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][d.getUTCMonth()];
  return `${M} ${d.getUTCDate()}, ${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")} UTC`;
}

export const CHAIN_NAMES = {
  eth: "Ethereum", base: "Base", arb: "Arbitrum", op: "Optimism", pol: "Polygon", bsc: "BNB Chain", avax: "Avalanche",
  gnosis: "Gnosis", monad: "Monad", bera: "Berachain", scroll: "Scroll", xlayer: "X Layer", plasma: "Plasma",
  btc: "Bitcoin", ltc: "Litecoin", doge: "Dogecoin", bch: "Bitcoin Cash", zec: "Zcash", tron: "Tron", ton: "TON",
  near: "NEAR", sui: "Sui", aptos: "Aptos", stellar: "Stellar", xrp: "XRP Ledger", sol: "Solana", cardano: "Cardano",
};
const EVM = new Set(["eth", "base", "arb", "op", "pol", "bsc", "avax", "gnosis", "monad", "bera", "scroll", "xlayer", "plasma"]);
/** Symbols offered to payers (well-known assets only, so nobody picks a look-alike token). */
const SYMBOLS = new Set(["USDC", "USDT", "ETH", "BTC", "NEAR", "TON", "TRX", "XRP", "DOGE", "LTC", "ZEC", "BNB", "POL", "AVAX", "SUI", "APT", "XLM", "BCH", "ADA", "SOL", "XDAI", "MON", "BERA"]);
/** Slow-finality chains get a longer window before refunds start. */
const SLOW = new Set(["btc", "ltc", "doge", "bch", "zec", "cardano"]);
export const FINAL = new Set(["SUCCESS", "REFUNDED", "FAILED"]);

const B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
export function b58decode(s) {
  if (typeof s !== "string" || !s) return null;
  let n = 0n;
  for (const c of s) { const i = B58.indexOf(c); if (i < 0) return null; n = n * 58n + BigInt(i); }
  const bytes = [];
  while (n > 0n) { bytes.unshift(Number(n & 0xffn)); n >>= 8n; }
  for (const c of s) { if (c !== "1") break; bytes.unshift(0); }
  return Uint8Array.from(bytes);
}
export const isSolanaAddress = (a) => { const b = b58decode(a); return !!b && b.length === 32; };

/**
 * Format check of the payer's refund address on the chain they pay from. It only catches typos and wrong-chain
 * pastes before a quote is reserved; the chain itself is the final judge.
 */
export function validRefund(chain, a) {
  if (typeof a !== "string") return false;
  a = a.trim();
  if (EVM.has(chain)) return /^0x[0-9a-fA-F]{40}$/.test(a);
  switch (chain) {
    case "sol": return isSolanaAddress(a);
    case "btc": return /^(bc1[02-9ac-hj-np-z]{11,71}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$/.test(a);
    case "ltc": return /^(ltc1[02-9ac-hj-np-z]{11,71}|[LM3][1-9A-HJ-NP-Za-km-z]{25,34})$/.test(a);
    case "doge": return /^[DA9][1-9A-HJ-NP-Za-km-z]{25,34}$/.test(a);
    case "bch": return /^((bitcoincash:)?[qp][02-9ac-hj-np-z]{41}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$/.test(a);
    case "zec": return /^t[13][1-9A-HJ-NP-Za-km-z]{33}$/.test(a);
    case "tron": return /^T[1-9A-HJ-NP-Za-km-z]{33}$/.test(a);
    case "ton": return /^(EQ|UQ|kQ|0Q)[A-Za-z0-9_-]{46}$/.test(a) || /^-?[01]:[0-9a-fA-F]{64}$/.test(a);
    case "near": return /^[0-9a-f]{64}$/.test(a) || /^0x[0-9a-fA-F]{40}$/.test(a) || /^(([a-z\d]+[-_])*[a-z\d]+\.)*([a-z\d]+[-_])*[a-z\d]+$/.test(a) && a.length >= 2 && a.length <= 64;
    case "sui": case "aptos": return /^0x[0-9a-fA-F]{1,64}$/.test(a);
    case "stellar": return /^G[A-Z2-7]{55}$/.test(a);
    case "xrp": return /^r[1-9A-HJ-NP-Za-km-z]{24,34}$/.test(a);
    case "cardano": return /^addr1[02-9ac-hj-np-z]{50,120}$/.test(a);
    default: return false;
  }
}

/** Decimal string -> smallest units (no floats). "5" USDC -> "5000000". */
export function toUnits(amount, decimals) {
  const s = typeof amount === "number" ? amount.toFixed(decimals) : String(amount ?? "").trim();
  if (!/^\d+(\.\d+)?$/.test(s)) throw new Error("bad amount");
  const [i, f = ""] = s.split(".");
  if (f.replace(/0+$/, "").length > decimals) throw new Error("too many decimals");
  const units = (BigInt(i) * 10n ** BigInt(decimals) + BigInt((f + "0".repeat(decimals)).slice(0, decimals) || "0")).toString();
  if (units === "0") throw new Error("amount must be positive");
  return units;
}
export function fromUnits(units, decimals) {
  const n = BigInt(units);
  const s = n.toString().padStart(decimals + 1, "0");
  const f = s.slice(-decimals).replace(/0+$/, "");
  return s.slice(0, -decimals) + (f ? "." + f : "");
}

/** The payer-facing source list from GET /api/tokens: well-known symbols on chains we can validate. */
export function pickSources(tokens) {
  const list = Array.isArray(tokens) ? tokens : tokens?.tokens || [];
  const seen = new Set();
  const out = [];
  for (const t of list) {
    if (!t?.assetId || !SYMBOLS.has(t.symbol) || !CHAIN_NAMES[t.blockchain]) continue;
    const key = t.blockchain + ":" + t.symbol;
    if (seen.has(key)) continue; // one entry per chain+symbol: the first (canonical) listing
    seen.add(key);
    out.push({ assetId: t.assetId, chain: t.blockchain, chainName: CHAIN_NAMES[t.blockchain], symbol: t.symbol, decimals: t.decimals, price: t.price ?? null });
  }
  const rank = (s) => (s.symbol === "USDC" ? 0 : s.symbol === "USDT" ? 1 : 2);
  return out.sort((a, b) => rank(a) - rank(b) || a.chainName.localeCompare(b.chainName) || a.symbol.localeCompare(b.symbol));
}

/** Request body for POST /api/quote (EXACT_OUTPUT: the requester gets exactly the asked amount on Solana). */
export function quoteBody(req, source, refundTo, now = Date.now(), dry = false) {
  const dest = DEST[req.token];
  const minutes = SLOW.has(source.chain) ? 90 : 30;
  return {
    dry,
    swapType: "EXACT_OUTPUT",
    depositType: "ORIGIN_CHAIN",
    depositMode: source.chain === "stellar" ? "MEMO" : "SIMPLE",
    originAsset: source.assetId,
    destinationAsset: dest.assetId,
    amount: toUnits(req.amount, dest.decimals),
    slippageTolerance: 100,
    refundTo: refundTo.trim(),
    refundType: "ORIGIN_CHAIN",
    recipient: req.to,
    recipientType: "DESTINATION_CHAIN",
    deadline: new Date(now + minutes * 60_000).toISOString(),
    referral: "sol-solardepin",
  };
}

/** What the payer is told for each Swap API status. Refunds always go to their own refund address. */
export function describe(status, d = {}, token = "USDC") {
  switch (status) {
    case "PENDING_DEPOSIT": return { tone: "wait", title: "Waiting for your transfer", body: "Send the exact amount shown, from the network shown, before the time shown." };
    case "KNOWN_DEPOSIT_TX": return { tone: "wait", title: "Transfer seen", body: "Waiting for network confirmations." };
    case "PROCESSING": return { tone: "wait", title: "Converting", body: `Your deposit arrived. Turning it into ${token} on Solana.` };
    case "INCOMPLETE_DEPOSIT": return { tone: "warn", title: "Not enough received", body: `Received ${d.depositedAmountFormatted || "less than needed"}. Send the rest to the same address before the deadline, or the deposit is refunded to your address.` };
    case "SUCCESS": return { tone: "ok", title: "Paid ✓", body: `${d.amountOutFormatted || ""} ${token} arrived on Solana.`.trim() };
    case "REFUNDED": return { tone: "warn", title: "Refunded", body: `${d.refundedAmountFormatted || "Your deposit"} went back to your refund address${d.refundReason ? " (" + d.refundReason + ")" : ""}.` };
    case "FAILED": return { tone: "bad", title: "Failed", body: "The swap failed. Keep this page; the deposit address below is what support needs." };
    default: return { tone: "wait", title: "Checking…", body: "" };
  }
}

/** Settlement facts from a SUCCESS status: the Solana tx(s) that delivered the money. */
export function settlementOf(st) {
  const d = st?.swapDetails || {};
  const txs = (d.destinationChainTxHashes || []).map((t) => (typeof t === "string" ? t : t?.hash)).filter(Boolean);
  return { solanaTxs: txs, amountOut: d.amountOut || null, amountOutFormatted: d.amountOutFormatted || null, originTxs: (d.originChainTxHashes || []).map((t) => (typeof t === "string" ? t : t?.hash)).filter(Boolean) };
}

// ---------------------------------------------------------------- Aurora client

export class AuroraError extends Error { constructor(msg, status) { super(msg); this.status = status; } }

export async function aurora(env, path, init = {}, fetchImpl = fetch) {
  const key = env.AURORA_API_KEY;
  if (!key) throw new AuroraError("AURORA_NOT_CONFIGURED", 503);
  const [p, q] = path.split("?");
  const url = `${AURORA_API}${p}/${encodeURIComponent(key)}${q ? "?" + q : ""}`;
  const r = await fetchImpl(url, { ...init, headers: { "content-type": "application/json", ...(init.headers || {}) } });
  const text = await r.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = { message: text.slice(0, 200) }; }
  if (!r.ok) throw new AuroraError(body?.message || `aurora ${r.status}`, r.status);
  return body;
}

// ---------------------------------------------------------------- storage

const REQ_TTL = 30 * 24 * 3600;
const kvKey = (id) => "ac:req:" + id;
export function newId(rnd = crypto.getRandomValues.bind(crypto)) {
  const b = rnd(new Uint8Array(10));
  const A = "abcdefghjkmnpqrstuvwxyz23456789";
  return Array.from(b, (x) => A[x % A.length]).join("");
}
async function load(env, id) {
  if (!/^[a-z0-9]{6,20}$/.test(id || "")) return null;
  const v = await env.ANYCHAIN.get(kvKey(id));
  return v ? JSON.parse(v) : null;
}
const save = (env, r) => env.ANYCHAIN.put(kvKey(r.id), JSON.stringify(r), { expirationTtl: REQ_TTL });

/** Best-effort per-IP limiter (KV is eventually consistent; this is abuse damping, not accounting). */
async function limited(env, request, bucket, max) {
  const ip = request.headers.get("cf-connecting-ip") || "local";
  const k = `ac:rl:${bucket}:${ip}:${Math.floor(Date.now() / 3600_000)}`;
  const n = Number((await env.ANYCHAIN.get(k)) || 0);
  if (n >= max) return true;
  await env.ANYCHAIN.put(k, String(n + 1), { expirationTtl: 3700 });
  return false;
}

/** What anyone with the link may see: no refund addresses, no IPs. */
export function publicView(r) {
  return {
    id: r.id, to: r.to, token: r.token, amount: r.amount, who: r.who, from: r.from, note: r.note, createdAt: r.createdAt,
    solanaPay: r.reference ? solanaPayUrl(r) : null,
    status: r.status, paid: r.paid || null,
    deposits: (r.deposits || []).map((d) => ({ depositAddress: d.depositAddress, depositMemo: d.depositMemo || null, chain: d.chain, chainName: CHAIN_NAMES[d.chain], symbol: d.symbol, amountIn: d.amountIn, amountInFormatted: d.amountInFormatted, amountInUsd: d.amountInUsd, deadline: d.deadline, timeEstimate: d.timeEstimate, refundFee: d.refundFee || null, status: d.status || "PENDING_DEPOSIT", refunded: d.refunded || null })),
  };
}

export function solanaPayUrl(r) {
  const q = [`amount=${encodeURIComponent(String(r.amount))}`];
  if (DEST[r.token]?.mint) q.push(`spl-token=${DEST[r.token].mint}`);
  if (r.reference) q.push(`reference=${r.reference}`);
  q.push("label=Sol");
  if (r.note) q.push("message=" + encodeURIComponent(r.note));
  return `solana:${r.to}?${q.join("&")}`;
}

// ---------------------------------------------------------------- routes

let tokenCache = { at: 0, list: null };
export async function sources(env, fetchImpl = fetch, now = Date.now()) {
  if (tokenCache.list && now - tokenCache.at < 10 * 60_000) return tokenCache.list;
  const body = await aurora(env, "/api/tokens", {}, fetchImpl);
  tokenCache = { at: now, list: pickSources(body) };
  return tokenCache.list;
}
export function _resetCache() { tokenCache = { at: 0, list: null }; }

const clean = (s, n) => String(s ?? "").replace(/[\u0000-\u001f<>]/g, "").trim().slice(0, n);

export async function handleAnychain(request, env, url, json, fetchImpl = fetch) {
  const p = url.pathname;
  try {
    if (request.method === "GET" && p === "/anychain/sources") {
      return json({ sources: await sources(env, fetchImpl) }, 200, { "cache-control": "public, max-age=300" });
    }

    if (request.method === "POST" && p === "/anychain/request") {
      if (await limited(env, request, "req", 30)) return json({ error: "RATE_LIMITED" }, 429);
      const b = await request.json().catch(() => ({}));
      const token = String(b.token || "").toUpperCase();
      if (!DEST[token]) return json({ error: "TOKEN_NOT_SUPPORTED", hint: "USDC or SOL" }, 400);
      if (!isSolanaAddress(b.to)) return json({ error: "BAD_RECIPIENT" }, 400);
      if (b.reference && !isSolanaAddress(b.reference)) return json({ error: "BAD_REFERENCE" }, 400);
      let units;
      try { units = toUnits(b.amount, DEST[token].decimals); } catch (e) { return json({ error: "BAD_AMOUNT", detail: e.message }, 400); }
      if (Number(b.amount) > MAX_AMOUNT[token]) return json({ error: "AMOUNT_TOO_LARGE", max: MAX_AMOUNT[token] }, 400);
      const r = {
        id: newId(), to: b.to, token, amount: fromUnits(units, DEST[token].decimals), units,
        who: clean(b.who, 40), from: clean(b.from, 40), note: clean(b.note, 80), reference: b.reference || null,
        createdAt: new Date().toISOString(), status: "open", deposits: [],
      };
      await save(env, r);
      return json({ id: r.id, url: `${url.origin}/r/${r.id}`, request: publicView(r) });
    }

    const m = p.match(/^\/anychain\/request\/([a-z0-9]+)$/);
    if (request.method === "GET" && m) {
      const r = await load(env, m[1]);
      if (!r) return json({ error: "NOT_FOUND" }, 404);
      if (r.status !== "paid") await refresh(env, r, fetchImpl);
      return json(publicView(r));
    }

    if (request.method === "POST" && p === "/anychain/quote") {
      if (await limited(env, request, "quote", 40)) return json({ error: "RATE_LIMITED" }, 429);
      const b = await request.json().catch(() => ({}));
      const r = await load(env, b.id);
      if (!r) return json({ error: "NOT_FOUND" }, 404);
      if (r.status === "paid") return json({ error: "ALREADY_PAID", request: publicView(r) }, 409);
      const src = (await sources(env, fetchImpl)).find((s) => s.assetId === b.origin);
      if (!src) return json({ error: "SOURCE_NOT_SUPPORTED" }, 400);
      if (src.chain === "sol" && src.symbol === r.token) return json({ error: "USE_SOLANA_PAY", solanaPay: solanaPayUrl(r) }, 400);
      if (!validRefund(src.chain, b.refundTo)) return json({ error: "BAD_REFUND_ADDRESS", chain: src.chain, chainName: src.chainName }, 400);
      if (liveQuotes(r).length >= MAX_LIVE_QUOTES) return json({ error: "TOO_MANY_QUOTES", detail: "Use one of the quotes already shown, or try again after they expire." }, 429);
      const q = await aurora(env, "/api/quote", { method: "POST", body: JSON.stringify(quoteBody(r, src, b.refundTo)) }, fetchImpl);
      const qq = q?.quote || {};
      if (!qq.depositAddress) return json({ error: "NO_DEPOSIT_ADDRESS" }, 502);
      const dep = {
        depositAddress: qq.depositAddress, depositMemo: qq.depositMemo || null, chain: src.chain, symbol: src.symbol, originAsset: src.assetId,
        amountIn: qq.amountIn, amountInFormatted: qq.amountInFormatted, amountInUsd: qq.amountInUsd, minAmountIn: qq.minAmountIn, maxAmountIn: qq.maxAmountIn || null,
        amountOut: qq.amountOut, deadline: qq.deadline || q?.quoteRequest?.deadline, timeEstimate: qq.timeEstimate, refundFee: qq.refundFee || null,
        refundTo: b.refundTo.trim(), status: "PENDING_DEPOSIT", createdAt: new Date().toISOString(),
      };
      // Never drop a deposit that could still be paid or settle: only quotes that expired over an hour ago unused.
      const now = Date.now();
      r.deposits = [...liveQuotes(r), dep];
      await save(env, r);
      return json({ deposit: publicView(r).deposits.at(-1), requirements: requirements(src, dep, r) });
    }

    if (request.method === "POST" && p === "/anychain/submit") {
      const b = await request.json().catch(() => ({}));
      const r = await load(env, b.id);
      const dep = r?.deposits?.find((d) => d.depositAddress === b.depositAddress);
      if (!dep) return json({ error: "NOT_FOUND" }, 404);
      const tx = clean(b.txHash, 140);
      if (!/^[A-Za-z0-9:_-]{20,140}$/.test(tx)) return json({ error: "BAD_TX_HASH" }, 400);
      const body = { txHash: tx, depositAddress: dep.depositAddress };
      if (dep.depositMemo) body.memo = dep.depositMemo;
      const st = await aurora(env, "/api/deposit/submit", { method: "POST", body: JSON.stringify(body) }, fetchImpl);
      dep.txHash = tx; dep.status = st?.status || dep.status;
      await save(env, r);
      return json({ status: dep.status, info: describe(dep.status, st?.swapDetails, r.token) });
    }

    if (request.method === "GET" && p === "/anychain/status") {
      const r = await load(env, url.searchParams.get("id"));
      if (!r) return json({ error: "NOT_FOUND" }, 404);
      await refresh(env, r, fetchImpl);
      const live = (r.deposits || []).at(-1);
      return json({ request: publicView(r), info: live ? describe(live.status, live.details, r.token) : null });
    }

    if (request.method === "GET" && p === "/anychain/incidents") {
      const body = await aurora(env, "/api/incidents", {}, fetchImpl);
      const chain = url.searchParams.get("chain");
      const list = (body?.incidents || []).filter((i) => i.status === "active" && (!chain || String(i.scopeValue || "").includes(chain)));
      return json({ incidents: list.map((i) => ({ scope: i.scopeType, value: i.scopeValue, text: i.publicDescription || null })) });
    }
  } catch (e) {
    if (e instanceof AuroraError) return json({ error: e.message === "AURORA_NOT_CONFIGURED" ? e.message : "AURORA_ERROR", detail: e.message, upstream: e.status }, e.status === 503 ? 503 : 502);
    return json({ error: "SERVER_ERROR" }, 500);
  }
  return null;
}

/** The transfer-requirements block (Aurora UX guidance: network + asset always next to the address). */
export function requirements(src, dep, r) {
  const lines = [`Send only ${src.symbol} from the ${src.chainName} network. 1 asset is supported for this address.`];
  lines.push(`Send exactly ${dep.amountInFormatted} ${src.symbol}. Less than ${dep.minAmountIn ? fromUnits(dep.minAmountIn, src.decimals) : dep.amountInFormatted} is refunded; any extra above the quote is refunded to you after the payment.`);
  if (dep.depositMemo) lines.push(`Include the memo ${dep.depositMemo}, or the deposit is not credited.`);
  lines.push(`Send before ${fmtUtc(dep.deadline)} (the "Send before" time above, in your time zone). After that, do not use this address; get a new quote.`);
  lines.push(`Sending another asset or using another network may result in loss of funds.`);
  lines.push(`${r.from || "The requester"} receives exactly ${r.amount} ${r.token} on Solana, at ${r.to.slice(0, 4)}…${r.to.slice(-4)}.`);
  return lines;
}

/** Polls Aurora for every non-final deposit; the first SUCCESS marks the request paid. */
/** Quotes worth keeping: everything except unused quotes whose deadline passed over an hour ago. */
export const MAX_LIVE_QUOTES = 12;
export function liveQuotes(r, now = Date.now()) {
  return (r.deposits || []).filter((d) => !(d.status === "PENDING_DEPOSIT" && !d.txHash && Date.parse(d.deadline) < now - 3600_000));
}

export async function refresh(env, r, fetchImpl = fetch, now = Date.now()) {
  let changed = false;
  for (const d of r.deposits || []) {
    if (FINAL.has(d.status)) continue;
    // an untouched quote 6 h past its deadline will never settle; stop asking Aurora about it
    if (d.status === "PENDING_DEPOSIT" && !d.txHash && Date.parse(d.deadline) < now - 6 * 3600_000) continue;
    const q = `depositAddress=${encodeURIComponent(d.depositAddress)}${d.depositMemo ? "&depositMemo=" + encodeURIComponent(d.depositMemo) : ""}`;
    let st;
    try { st = await aurora(env, "/api/status?" + q, {}, fetchImpl); } catch { continue; }
    if (!st?.status) continue;
    if (st.status !== d.status) changed = true;
    d.status = st.status;
    d.details = { depositedAmountFormatted: st.swapDetails?.depositedAmountFormatted, amountOutFormatted: st.swapDetails?.amountOutFormatted, refundedAmountFormatted: st.swapDetails?.refundedAmountFormatted, refundReason: st.swapDetails?.refundReason };
    if (st.status === "REFUNDED") d.refunded = { amount: st.swapDetails?.refundedAmountFormatted || null, reason: st.swapDetails?.refundReason || null };
    if (st.status === "SUCCESS" && r.status !== "paid") {
      const s = settlementOf(st);
      r.status = "paid";
      r.paid = { via: "aurora-intents", chain: d.chain, chainName: CHAIN_NAMES[d.chain] || d.chain, symbol: d.symbol, depositAddress: d.depositAddress, solanaTxs: s.solanaTxs, originTxs: s.originTxs, amountOut: s.amountOut, amountOutFormatted: s.amountOutFormatted, at: st.updatedAt || new Date().toISOString() };
    }
  }
  if (changed) await save(env, r);
  return r;
}
