// 1.1.0 mainnet agent mints: the worker's checks on transactions built by the Android app (fixtures from
// MainnetTest.collectionMintTxsForTheWorkerChecks), the co-sign route and on-chain verification.
// Run: node --test worker/agent-mint.test.mjs
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import worker from "./solarchik-screen.js";
import {
  parseLegacyTx, mintProblem, mintCosign, verifyMint, ixsFromParsed, b58encode, b58decode, coreCreateV1, tierOfName,
  MINT_CLUSTER, MINT_TREASURY, PRO_LAMPORTS, MPL_CORE, SYSTEM_PROGRAM,
} from "./agent-mint.js";

const fx = JSON.parse(readFileSync(new URL("./fixtures/mint-txs.json", import.meta.url)));
const bytes = (b64) => Uint8Array.from(Buffer.from(b64, "base64"));
const cfg = { collection: fx.collection, authority: fx.authority };
const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });

test("parses the app's legacy mint txs (Core CreateV1 into the collection)", () => {
  const t = parseLegacyTx(bytes(fx.pro));
  assert.equal(t.keys[0], fx.payer);
  assert.equal(t.header.required, 3);
  const create = t.instructions.find((i) => i.program === MPL_CORE);
  assert.equal(coreCreateV1(create.data).name, fx.proName);
  assert.equal(create.accounts[1], fx.collection);
  assert.equal(create.accounts[2], fx.authority);
  assert.equal(create.accounts[3], fx.payer);
});

test("rules: free ok, pro paid ok, combo paid ok, combo unpaid refused (paid-only, server side)", () => {
  const check = (k) => { const t = parseLegacyTx(bytes(fx[k])); return mintProblem(t.instructions, t.keys[0], cfg); };
  assert.deepEqual([check("free").ok, check("free").tier, check("free").paid], [true, "free", false]);
  assert.deepEqual([check("pro").ok, check("pro").tier, check("pro").paid], [true, "pro", true]);
  assert.deepEqual([check("combo").ok, check("combo").tier], [true, "combo"]);
  const forged = check("comboUnpaid");
  assert.equal(forged.ok, false);
  assert.equal(forged.reason, "COMBO_PAID_ONLY");
  assert.equal(tierOfName(fx.comboName), "combo");
  // wrong collection / authority
  const t = parseLegacyTx(bytes(fx.free));
  assert.equal(mintProblem(t.instructions, t.keys[0], { ...cfg, collection: MINT_TREASURY }).reason, "WRONG_COLLECTION");
  assert.equal(mintProblem(t.instructions, t.keys[0], { ...cfg, authority: MINT_TREASURY }).reason, "WRONG_AUTHORITY");
});

test("rules: underpaid Pro, a transfer elsewhere and extra instructions are refused", () => {
  const t = parseLegacyTx(bytes(fx.pro));
  const pay = t.instructions.find((i) => i.program === SYSTEM_PROGRAM);
  const less = { ...pay, transfer: { from: fx.payer, to: MINT_TREASURY, lamports: BigInt(PRO_LAMPORTS - 1) } };
  const others = t.instructions.filter((i) => i !== pay);
  assert.equal(mintProblem([less, ...others], fx.payer, cfg).reason, "PRO_UNPAID");
  const away = { ...pay, transfer: { from: fx.payer, to: fx.collection, lamports: BigInt(PRO_LAMPORTS) } };
  assert.equal(mintProblem([away, ...others], fx.payer, cfg).reason, "UNEXPECTED_TRANSFER");
  assert.equal(mintProblem([...t.instructions, { program: "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", accounts: [], data: new Uint8Array([3]) }], fx.payer, cfg).reason, "UNEXPECTED_INSTRUCTION");
  assert.equal(mintProblem(t.instructions, fx.collection, cfg).reason, "PAYER_MISMATCH");
});

async function keypair() {
  const k = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const pk = new Uint8Array(await crypto.subtle.exportKey("raw", k.publicKey));
  const jwk = await crypto.subtle.exportKey("jwk", k.privateKey);
  const seed = Uint8Array.from(Buffer.from(jwk.d, "base64url"));
  const secret = new Uint8Array(64);
  secret.set(seed, 0);
  secret.set(pk, 32);
  return { pub: b58encode(pk), secret: b58encode(secret), publicKey: k.publicKey };
}

test("cosign: 503 until the collection exists; then signs good mints only, signature verifies", async () => {
  const off = await mintCosign({}, { tx: fx.free });
  assert.equal(off.status, 503);
  assert.equal(off.body.error, "MINT_NOT_READY");
  assert.equal(off.body.cluster, "mainnet-beta");
  // fixtures were built for a fixed test authority; a fresh key that is NOT in the tx is refused as non-signer
  const kp = await keypair();
  const env = { MAINNET_COLLECTION: fx.collection, MAINNET_COLLECTION_AUTHORITY: kp.pub, COLLECTION_AUTHORITY_SECRET: kp.secret };
  assert.equal((await mintCosign(env, { tx: fx.free })).body.error, "AUTHORITY_NOT_SIGNER");
});

test("cosign signs the exact message with the authority secret (Ed25519 via WebCrypto)", async () => {
  // the Kotlin test's authority seed is bytes 50..81: rebuild its 64-byte secret here
  const seed = Uint8Array.from({ length: 32 }, (_, i) => i + 50);
  const jwkPriv = { kty: "OKP", crv: "Ed25519", d: Buffer.from(seed).toString("base64url"), x: Buffer.from(b58decode(fx.authority)).toString("base64url") };
  const pubKey = await crypto.subtle.importKey("jwk", { kty: "OKP", crv: "Ed25519", x: jwkPriv.x }, { name: "Ed25519" }, true, ["verify"]);
  const secret = new Uint8Array(64);
  secret.set(seed, 0);
  secret.set(b58decode(fx.authority), 32);
  const env = { MAINNET_COLLECTION: fx.collection, MAINNET_COLLECTION_AUTHORITY: fx.authority, COLLECTION_AUTHORITY_SECRET: b58encode(secret) };
  for (const k of ["free", "pro", "combo"]) {
    const r = await mintCosign(env, { tx: fx[k] });
    assert.equal(r.status, 200, k + " " + JSON.stringify(r.body));
    const msg = parseLegacyTx(bytes(fx[k])).message;
    assert.equal(await crypto.subtle.verify({ name: "Ed25519" }, pubKey, b58decode(r.body.signature), msg), true);
  }
  const forged = await mintCosign(env, { tx: fx.comboUnpaid });
  assert.equal(forged.status, 403);
  assert.equal(forged.body.error, "COMBO_PAID_ONLY");
  const res = await worker.fetch(new Request("https://w.example/agent/mint-cosign", { method: "POST", body: JSON.stringify({ tx: fx.comboUnpaid }) }), env, {});
  assert.equal(res.status, 403);
});

test("verify-mint reads mainnet getTransaction (jsonParsed) and applies the same rules", async () => {
  const t = parseLegacyTx(bytes(fx.combo));
  const create = t.instructions.find((i) => i.program === MPL_CORE);
  const parsed = (paid) => ({
    meta: { err: null },
    transaction: { message: {
      accountKeys: t.keys.map((k) => ({ pubkey: k })),
      instructions: [
        ...(paid ? [{ programId: SYSTEM_PROGRAM, program: "system", parsed: { type: "transfer", info: { source: fx.payer, destination: MINT_TREASURY, lamports: PRO_LAMPORTS } } }] : []),
        { programId: MPL_CORE, accounts: create.accounts, data: b58encode(create.data) },
      ],
    } },
  });
  const env = { MAINNET_COLLECTION: fx.collection, MAINNET_COLLECTION_AUTHORITY: fx.authority };
  const sig = "5".repeat(88);
  let seen = null;
  const ok = await verifyMint(env, sig, async (_e, method, params) => { seen = [method, params]; return parsed(true); });
  assert.equal(seen[0], "getTransaction");
  assert.deepEqual([ok.status, ok.body.ok, ok.body.tier, ok.body.paid, ok.body.cluster], [200, true, "combo", true, MINT_CLUSTER]);
  const bad = await verifyMint(env, sig, async () => parsed(false));
  assert.equal(bad.body.reason, "COMBO_PAID_ONLY");
  assert.equal((await verifyMint(env, "nope", async () => null)).status, 400);
  assert.equal((await verifyMint(env, sig, async () => null)).status, 404);
  assert.equal(ixsFromParsed(parsed(true)).feePayer, fx.payer);
});

test("GET /agent/mint-config is explicit about the cluster and not ready yet", async () => {
  const res = await worker.fetch(new Request("https://w.example/agent/mint-config"), {}, {});
  const j = await res.json();
  assert.deepEqual([j.cluster, j.ready, j.treasury, j.proLamports, j.comboPaidOnly], ["mainnet-beta", false, MINT_TREASURY, 100000000, true]);
});
