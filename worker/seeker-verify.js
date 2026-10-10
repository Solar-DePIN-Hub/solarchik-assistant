/**
 * 1.2.0 Verified Seeker via the Seeker Genesis Token (SGT), per
 * https://docs.solanamobile.com/recipes/general/detecting-seeker-users :
 *  1. SIWS: the worker issues the sign-in payload (single-use nonce, 10 min), the app signs it through Mobile
 *     Wallet Adapter, the worker checks the Ed25519 signature, the domain, the address and the nonce in the
 *     signed message, then consumes the nonce.
 *  2. SGT: Token-2022 accounts of that wallet with a NON-ZERO balance; a mint counts only when its Metadata
 *     Pointer and Token Group Member both point at GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te.
 *  3. Anti-sybil: one SGT mint = one perk. The mint is stored with the first account that used it.
 * Perk: a Verified badge and SEEKER_BONUS_MIN extra secretary minutes a day. Nothing else is gated on it.
 */
import { b58decode, b58encode } from "./agent-mint.js";

export const SGT_METADATA = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te";
export const SGT_GROUP = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te";
export const TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";
export const SIWS_DOMAIN = "solardepin.net";
export const SIWS_URI = "https://solardepin.net";
export const SIWS_STATEMENT = "Sign in to verify Seeker ownership for Solarchik";
export const NONCE_TTL_SEC = 600;
/** Extra AI secretary minutes a day for a verified Seeker account (env SEEKER_BONUS_MIN). */
export const SEEKER_BONUS_MIN = 10;

export function bonusMin(env) {
  const n = Number(env?.SEEKER_BONUS_MIN);
  return Number.isFinite(n) && n >= 0 ? n : SEEKER_BONUS_MIN;
}

/** A parsed (jsonParsed) Token-2022 mint is an SGT when both extensions point at the SGT metadata / group. */
export function isSgtMint(info) {
  const ext = Array.isArray(info?.extensions) ? info.extensions : [];
  const meta = ext.find((e) => e?.extension === "metadataPointer")?.state?.metadataAddress;
  const group = ext.find((e) => e?.extension === "tokenGroupMember")?.state?.group;
  return meta === SGT_METADATA && group === SGT_GROUP;
}

/** The wallet's current SGT mint address, or null. `rpc(method, params)` returns the JSON-RPC result. */
export async function findSgt(rpc, owner) {
  const res = await rpc("getTokenAccountsByOwner", [owner, { programId: TOKEN_2022 }, { encoding: "jsonParsed" }]);
  const mints = [...new Set((res?.value || [])
    .map((a) => a?.account?.data?.parsed?.info)
    .filter((i) => i?.mint && String(i?.tokenAmount?.amount ?? "0") !== "0") // an emptied account is not ownership
    .map((i) => i.mint))];
  for (let i = 0; i < mints.length; i += 100) {
    const batch = mints.slice(i, i + 100);
    const accs = await rpc("getMultipleAccounts", [batch, { encoding: "jsonParsed" }]);
    for (let k = 0; k < batch.length; k++) {
      const a = accs?.value?.[k];
      if (a && a.owner === TOKEN_2022 && a.data?.parsed?.type === "mint" && isSgtMint(a.data.parsed.info)) return batch[k];
    }
  }
  return null;
}

function hex(bytes) {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}
const b64 = (s) => Uint8Array.from(atob(String(s || "")), (c) => c.charCodeAt(0));

export function signInPayload(nonce, now = Date.now()) {
  return {
    domain: SIWS_DOMAIN,
    uri: SIWS_URI,
    statement: SIWS_STATEMENT,
    version: "1",
    chainId: "mainnet",
    nonce,
    issuedAt: new Date(now).toISOString(),
    expirationTime: new Date(now + NONCE_TTL_SEC * 1000).toISOString(),
  };
}

/** Field check of a SIWS message (the ABNF of the Phantom sign-in-with-solana spec), as verifySignIn does. */
export function messageMatches(text, payload, address) {
  const lines = String(text).split("\n");
  if (lines[0] !== `${payload.domain} wants you to sign in with your Solana account:`) return "domain";
  if (lines[1] !== address) return "address";
  const field = (name) => lines.find((l) => l.startsWith(name + ": "))?.slice(name.length + 2);
  if (field("Nonce") !== payload.nonce) return "nonce";
  if (field("URI") && field("URI") !== payload.uri) return "uri";
  const exp = field("Expiration Time");
  if (exp && Date.parse(exp) < Date.now()) return "expired";
  return "";
}

export async function ed25519Ok(pk, msg, sig) {
  try {
    const key = await crypto.subtle.importKey("raw", pk, { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "Ed25519" }, key, sig, msg);
  } catch {
    return false;
  }
}

/**
 * Verifies a SIWS result for an issued nonce. Returns { address } or { error }.
 * `input` = { nonce, address, signedMessage, signature } (base64, as MWA's sign_in_result gives them).
 */
export async function verifySiws(kv, userId, input) {
  const nonce = String(input?.nonce || "");
  if (!/^[0-9a-f]{32}$/.test(nonce)) return { error: "nonce" };
  const stored = await kv.get("siws:" + nonce, "json").catch(() => null);
  if (!stored) return { error: "nonce" };
  await kv.delete("siws:" + nonce).catch(() => {}); // single use, even when the rest fails
  if (stored.userId !== userId) return { error: "nonce" };
  let pk, msg, sig;
  try { pk = b64(input.address); msg = b64(input.signedMessage); sig = b64(input.signature); } catch { return { error: "format" }; }
  if (pk.length !== 32 || sig.length !== 64 || !msg.length) return { error: "format" };
  const address = b58encode(pk);
  const bad = messageMatches(new TextDecoder().decode(msg), stored.payload, address);
  if (bad) return { error: "message_" + bad };
  if (!(await ed25519Ok(pk, msg, sig))) return { error: "signature" };
  return { address };
}

export async function seekerStatus(kv, userId) {
  if (!userId) return null;
  return kv.get("seeker_user:" + userId, "json").catch(() => null);
}

/** Records a verified SGT for this account; one mint = one account. Returns { verified, reason? }. */
export async function claimMint(kv, userId, mint, address, now = Date.now()) {
  const owner = await kv.get("sgt_mint:" + mint).catch(() => null);
  if (owner && owner !== userId) return { verified: false, reason: "mint_used" };
  await kv.put("sgt_mint:" + mint, userId);
  const rec = { mint, address, at: now };
  await kv.put("seeker_user:" + userId, JSON.stringify(rec));
  return { verified: true, ...rec };
}

const USER = /^[A-Za-z0-9_-]{8,64}$/;

/** /seeker/challenge, /seeker/verify, /seeker/status. `rpc(method, params)` is the worker's failover RPC. */
export async function seekerRoute(env, request, json, rpc, rateOk) {
  const url = new URL(request.url);
  if (!url.pathname.startsWith("/seeker/")) return null;
  const kv = env.BALANCES;
  if (request.method === "GET" && url.pathname === "/seeker/status") {
    const userId = url.searchParams.get("userId") || "";
    if (!USER.test(userId)) return json({ ok: false, error: "userId" }, 400);
    const s = await seekerStatus(kv, userId);
    return json({ ok: true, verified: !!s, mint: s?.mint || "", address: s?.address || "", at: s?.at || 0, bonusMin: bonusMin(env) });
  }
  if (request.method !== "POST") return null;
  const body = await request.json().catch(() => ({}));
  const userId = String(body.userId || "");
  if (!USER.test(userId)) return json({ ok: false, error: "userId" }, 400);
  if (rateOk && !rateOk(request.headers.get("cf-connecting-ip"))) return json({ ok: false, error: "rate" }, 429);
  if (url.pathname === "/seeker/challenge") {
    const nonce = hex(crypto.getRandomValues(new Uint8Array(16)));
    const payload = signInPayload(nonce);
    await kv.put("siws:" + nonce, JSON.stringify({ userId, payload }), { expirationTtl: NONCE_TTL_SEC });
    return json({ ok: true, payload });
  }
  if (url.pathname === "/seeker/verify") {
    const v = await verifySiws(kv, userId, body);
    if (v.error) return json({ ok: false, verified: false, error: v.error }, 400);
    let mint = null;
    try { mint = await findSgt(rpc, v.address); } catch (e) { return json({ ok: false, verified: false, error: "rpc", detail: String(e?.message || e).slice(0, 80) }, 502); }
    if (!mint) return json({ ok: true, verified: false, reason: "no_sgt", address: v.address, bonusMin: bonusMin(env) });
    const r = await claimMint(kv, userId, mint, v.address);
    console.log(JSON.stringify({ event: "seeker_verify", verified: r.verified, reason: r.reason || "", mint: mint.slice(0, 6) }));
    return json({ ok: true, ...r, address: v.address, bonusMin: bonusMin(env) });
  }
  return null;
}

export { b58decode };
