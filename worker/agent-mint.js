// 1.1.0 Solarchik Assistant: agent NFT mints on Solana MAINNET (cluster is explicit: mainnet-beta).
//
// Mints go into one Metaplex Core collection ("Solarchik Agents"). Adding an asset to a collection needs the
// collection's update authority to sign, so the server co-signs a mint ONLY after checking the transaction:
//  - exactly one Core CreateV1 into the configured collection, with the configured authority;
//  - only allowed programs (Core, ComputeBudget, System transfer to the treasury);
//  - Pro and Combo carry a >= 0.1 SOL transfer from the payer to the treasury in the same transaction;
//  - Combo is paid-only (Vadym's rule): a Combo without the payment is refused, whatever the client claims.
// The tier comes from the asset NAME inside the CreateV1 data, not from the request.
// Until Vadym funds and creates the collection, MAINNET_COLLECTION / COLLECTION_AUTHORITY_SECRET are unset and
// /agent/mint-cosign answers 503 MINT_NOT_READY (the app shows "coming soon").

export const MINT_CLUSTER = "mainnet-beta";
export const MPL_CORE = "CoREENxT6tW1HoK8ypY1SxRMZTcVPm7R94rH4PZNhX7d";
export const SYSTEM_PROGRAM = "11111111111111111111111111111111";
export const COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111";
export const PRO_LAMPORTS = 100_000_000; // 0.1 SOL
export const MINT_TREASURY = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic";

const ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

export function b58encode(bytes) {
  const b = Array.from(bytes);
  let zeros = 0;
  while (zeros < b.length && b[zeros] === 0) zeros++;
  const digits = [];
  for (let i = zeros; i < b.length; i++) {
    let carry = b[i];
    for (let j = 0; j < digits.length; j++) {
      carry += digits[j] << 8;
      digits[j] = carry % 58;
      carry = (carry / 58) | 0;
    }
    while (carry > 0) { digits.push(carry % 58); carry = (carry / 58) | 0; }
  }
  return "1".repeat(zeros) + digits.reverse().map((d) => ALPHABET[d]).join("");
}

export function b58decode(s) {
  const str = String(s || "");
  let zeros = 0;
  while (zeros < str.length && str[zeros] === "1") zeros++;
  const bytes = [];
  for (let i = zeros; i < str.length; i++) {
    let carry = ALPHABET.indexOf(str[i]);
    if (carry < 0) throw new Error("bad base58");
    for (let j = 0; j < bytes.length; j++) {
      carry += bytes[j] * 58;
      bytes[j] = carry & 0xff;
      carry >>= 8;
    }
    while (carry > 0) { bytes.push(carry & 0xff); carry >>= 8; }
  }
  return Uint8Array.from([...new Array(zeros).fill(0), ...bytes.reverse()]);
}

function compactU16(b, at) {
  let v = 0, i = 0;
  for (;;) {
    const x = b[at + i];
    v |= (x & 0x7f) << (7 * i);
    i++;
    if ((x & 0x80) === 0 || i === 3) break;
  }
  return [v, i];
}

/** Legacy (non-versioned) transaction: signatures, account keys, instructions. The app's mint txs are legacy. */
export function parseLegacyTx(bytes) {
  const b = bytes instanceof Uint8Array ? bytes : Uint8Array.from(bytes);
  const [nSig, l1] = compactU16(b, 0);
  let p = l1;
  const signatures = [];
  for (let i = 0; i < nSig; i++) { signatures.push(b.slice(p, p + 64)); p += 64; }
  const messageStart = p;
  if (b[p] & 0x80) throw new Error("versioned tx not supported for mints");
  const header = { required: b[p], roSigned: b[p + 1], roUnsigned: b[p + 2] };
  p += 3;
  const [nKeys, l2] = compactU16(b, p);
  p += l2;
  const keys = [];
  for (let i = 0; i < nKeys; i++) { keys.push(b58encode(b.slice(p, p + 32))); p += 32; }
  const blockhash = b58encode(b.slice(p, p + 32));
  p += 32;
  const [nIx, l3] = compactU16(b, p);
  p += l3;
  const instructions = [];
  for (let i = 0; i < nIx; i++) {
    const program = keys[b[p]];
    p += 1;
    const [nAcc, la] = compactU16(b, p);
    p += la;
    const accounts = [];
    for (let j = 0; j < nAcc; j++) accounts.push(keys[b[p + j]]);
    p += nAcc;
    const [nData, ld] = compactU16(b, p);
    p += ld;
    instructions.push({ program, accounts, data: b.slice(p, p + nData) });
    p += nData;
  }
  if (p !== b.length) throw new Error("trailing bytes");
  return { signatures, header, keys, blockhash, instructions, message: b.slice(messageStart) };
}

function u32(d, at) { return d[at] | (d[at + 1] << 8) | (d[at + 2] << 16) | (d[at + 3] << 24 >>> 0); }
function u64(d, at) { let v = 0n; for (let i = 7; i >= 0; i--) v = (v << 8n) | BigInt(d[at + i]); return v; }
function borshString(d, at) {
  const len = u32(d, at) >>> 0;
  return [new TextDecoder().decode(d.slice(at + 4, at + 4 + len)), at + 4 + len];
}

/** Core CreateV1 (discriminator 0): name and uri. Null for any other Core instruction. */
export function coreCreateV1(data) {
  if (!data || data.length < 10 || data[0] !== 0) return null;
  const [name, at] = borshString(data, 2);
  const [uri] = borshString(data, at);
  return { name, uri };
}

/** System Program Transfer (index 2): { from, to, lamports } or null. */
export function systemTransfer(ix) {
  if (ix.program !== SYSTEM_PROGRAM || !ix.data || ix.data.length !== 12 || u32(ix.data, 0) !== 2) return null;
  return { from: ix.accounts[0], to: ix.accounts[1], lamports: u64(ix.data, 4) };
}

export function tierOfName(name) {
  const n = String(name || "");
  if (/combo/i.test(n)) return "combo";
  if (/\bpro\b/i.test(n)) return "pro";
  return "free";
}

/**
 * The server-side mint rules on normalized instructions [{program, accounts, data?, transfer?}].
 * Returns { ok, reason?, tier, paid, payer, asset, name }.
 */
export function mintProblem(ixs, feePayer, { collection, authority, treasury = MINT_TREASURY } = {}) {
  const creates = ixs.filter((ix) => ix.program === MPL_CORE);
  if (creates.length !== 1) return { ok: false, reason: "NOT_ONE_MINT" };
  const create = creates[0];
  const meta = create.create || coreCreateV1(create.data);
  if (!meta) return { ok: false, reason: "NOT_CREATE_V1" };
  const [asset, coll, auth, payer] = create.accounts;
  if (payer !== feePayer) return { ok: false, reason: "PAYER_MISMATCH" };
  if (collection && coll !== collection) return { ok: false, reason: "WRONG_COLLECTION" };
  if (authority && auth !== authority) return { ok: false, reason: "WRONG_AUTHORITY" };
  let paid = 0n;
  for (const ix of ixs) {
    if (ix.program === MPL_CORE || ix.program === COMPUTE_BUDGET) continue;
    const t = ix.transfer || systemTransfer(ix);
    if (!t) return { ok: false, reason: "UNEXPECTED_INSTRUCTION" };
    if (t.to !== treasury || t.from !== feePayer) return { ok: false, reason: "UNEXPECTED_TRANSFER" };
    paid += BigInt(t.lamports);
  }
  const tier = tierOfName(meta.name);
  const isPaid = paid >= BigInt(PRO_LAMPORTS);
  const base = { tier, paid: isPaid, payer, asset, name: meta.name };
  if (tier === "combo" && !isPaid) return { ok: false, reason: "COMBO_PAID_ONLY", ...base };
  if (tier === "pro" && !isPaid) return { ok: false, reason: "PRO_UNPAID", ...base };
  if (tier === "free" && paid > 0n) return { ok: false, reason: "UNEXPECTED_PAYMENT", ...base };
  return { ok: true, ...base };
}

export function mintConfig(env) {
  return {
    collection: String(env?.MAINNET_COLLECTION || ""),
    authority: String(env?.MAINNET_COLLECTION_AUTHORITY || ""),
    secret: String(env?.COLLECTION_AUTHORITY_SECRET || ""),
  };
}

/** PKCS#8 wrapper for a raw 32-byte Ed25519 seed (WebCrypto has no raw private import). */
function pkcs8(seed) {
  const prefix = Uint8Array.from([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20]);
  const out = new Uint8Array(48);
  out.set(prefix, 0);
  out.set(seed, 16);
  return out;
}

/** Secret = base58 of the 64-byte Solana keypair (seed + public key) or of the 32-byte seed. */
export async function signWithSecret(secret, message) {
  const raw = b58decode(secret);
  if (raw.length !== 64 && raw.length !== 32) throw new Error("bad authority secret");
  const key = await crypto.subtle.importKey("pkcs8", pkcs8(raw.slice(0, 32)), { name: "Ed25519" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, key, message));
}

/** POST /agent/mint-cosign { tx: base64 legacy tx } -> { cluster, signature, authority, tier } or an error. */
export async function mintCosign(env, body) {
  const cfg = mintConfig(env);
  if (!cfg.collection || !cfg.authority || !cfg.secret) {
    return { status: 503, body: { error: "MINT_NOT_READY", cluster: MINT_CLUSTER, detail: "mainnet collection not created yet" } };
  }
  let tx;
  try {
    tx = parseLegacyTx(Uint8Array.from(atob(String(body?.tx || "")), (c) => c.charCodeAt(0)));
  } catch (e) {
    return { status: 400, body: { error: "BAD_TX", detail: String(e?.message || e) } };
  }
  const authIndex = tx.keys.indexOf(cfg.authority);
  if (authIndex < 0 || authIndex >= tx.header.required) return { status: 400, body: { error: "AUTHORITY_NOT_SIGNER" } };
  const check = mintProblem(tx.instructions, tx.keys[0], cfg);
  if (!check.ok) return { status: 403, body: { error: check.reason, cluster: MINT_CLUSTER, tier: check.tier || "" } };
  const sig = await signWithSecret(cfg.secret, tx.message);
  return { status: 200, body: { cluster: MINT_CLUSTER, signature: b58encode(sig), authority: cfg.authority, index: authIndex, tier: check.tier, asset: check.asset } };
}

/** jsonParsed getTransaction -> normalized instructions (top level), for after-the-fact verification. */
export function ixsFromParsed(tx) {
  const msg = tx?.transaction?.message;
  const keys = (msg?.accountKeys || []).map((k) => (typeof k === "string" ? k : k?.pubkey || ""));
  const ixs = (msg?.instructions || []).map((ix) => {
    if (ix.programId === SYSTEM_PROGRAM && ix.parsed?.type === "transfer") {
      const i = ix.parsed.info || {};
      return { program: SYSTEM_PROGRAM, accounts: [i.source, i.destination], transfer: { from: i.source, to: i.destination, lamports: BigInt(i.lamports || 0) } };
    }
    let data = null;
    try { data = typeof ix.data === "string" ? b58decode(ix.data) : null; } catch { data = null; }
    return { program: ix.programId, accounts: ix.accounts || [], data };
  });
  return { feePayer: keys[0] || "", ixs };
}

/** GET /agent/verify-mint?sig=… on mainnet: did this confirmed tx mint a Solarchik agent under the rules? */
export async function verifyMint(env, sig, rpcCall) {
  if (!/^[1-9A-HJ-NP-Za-km-z]{64,90}$/.test(String(sig || ""))) return { status: 400, body: { error: "BAD_SIGNATURE" } };
  let tx;
  try {
    tx = await rpcCall(env, "getTransaction", [sig, { encoding: "jsonParsed", maxSupportedTransactionVersion: 0, commitment: "confirmed" }]);
  } catch (e) {
    return { status: 503, body: { error: "RPC_UNAVAILABLE", cluster: MINT_CLUSTER, detail: String(e?.message || e) } };
  }
  if (!tx) return { status: 404, body: { error: "NOT_FOUND", cluster: MINT_CLUSTER } };
  if (tx.meta?.err) return { status: 200, body: { ok: false, cluster: MINT_CLUSTER, reason: "TX_FAILED" } };
  const cfg = mintConfig(env);
  const { feePayer, ixs } = ixsFromParsed(tx);
  const r = mintProblem(ixs, feePayer, { collection: cfg.collection, authority: cfg.authority });
  return { status: 200, body: { ...r, cluster: MINT_CLUSTER, signature: sig, collection: cfg.collection || null } };
}
