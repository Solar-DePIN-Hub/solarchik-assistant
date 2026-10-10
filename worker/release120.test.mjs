import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { findSgt, isSgtMint, verifySiws, signInPayload, claimMint, messageMatches, SGT_GROUP } from "./seeker-verify.js";
import { b58encode } from "./agent-mint.js";
import { checkDrops, dropsView, sanitizeDrops, xEnabled, xPosts, curatedItems, SOURCES_TEXT, live } from "./season-drops.js";
import { CURATED } from "./season-curated.js";
import { PAY_TOKENS, sanitizeActions, briefingFacts, briefingSystem } from "./assistant-extras.js";
import { addCallSeconds, callCapReason } from "./solarchik-screen.js";

const fx = JSON.parse(readFileSync(new URL("./fixtures/sgt-holder.json", import.meta.url)));

function kv() {
  const m = new Map();
  return {
    m,
    get: async (k, t) => { const v = m.get(k); return v == null ? null : t === "json" ? JSON.parse(v) : v; },
    put: async (k, v) => { m.set(k, String(v)); },
    delete: async (k) => { m.delete(k); },
  };
}
const fixtureRpc = async (method, params) => {
  if (method === "getTokenAccountsByOwner") return params[0] === fx.owner ? fx.getTokenAccountsByOwner : { value: [] };
  if (method === "getMultipleAccounts") return { value: params[0].map((m) => fx.getMultipleAccounts.value[fx.mints.indexOf(m)] ?? null) };
  throw new Error(method);
};

test("1.2.0 SGT: the known holder fixture (real mainnet data) has a Seeker Genesis Token", async () => {
  assert.equal(await findSgt(fixtureRpc, fx.owner), fx.sgtMint);
});

test("1.2.0 SGT: an emptied account, a wallet without one and a look-alike mint do not count", async () => {
  assert.equal(await findSgt(fixtureRpc, "11111111111111111111111111111111"), null);
  const emptied = JSON.parse(JSON.stringify(fx.getTokenAccountsByOwner));
  for (const a of emptied.value) a.account.data.parsed.info.tokenAmount.amount = "0";
  assert.equal(await findSgt(async (m, p) => (m === "getTokenAccountsByOwner" ? emptied : fixtureRpc(m, p)), fx.owner), null);
  const info = fx.getMultipleAccounts.value[fx.mints.indexOf(fx.sgtMint)].data.parsed.info;
  assert.equal(isSgtMint(info), true);
  const fake = JSON.parse(JSON.stringify(info));
  fake.extensions.find((e) => e.extension === "tokenGroupMember").state.group = "So11111111111111111111111111111111111111112";
  assert.equal(isSgtMint(fake), false);
  assert.equal(SGT_GROUP, "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te");
});

async function signed(store, userId, tamper = {}) {
  const { publicKey, privateKey } = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const pk = new Uint8Array(await crypto.subtle.exportKey("raw", publicKey));
  const addr = b58encode(pk);
  const nonce = "0123456789abcdef0123456789abcdef";
  const p = signInPayload(nonce);
  await store.put("siws:" + nonce, JSON.stringify({ userId, payload: p }));
  const msg = (tamper.domain || p.domain) + " wants you to sign in with your Solana account:\n" + addr + "\n\n" + p.statement + "\n\nURI: " + p.uri + "\nVersion: 1\nChain ID: mainnet\nNonce: " + (tamper.nonce || nonce) + "\nIssued At: " + p.issuedAt + "\nExpiration Time: " + p.expirationTime;
  const mb = new TextEncoder().encode(msg);
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, privateKey, mb));
  if (tamper.flip) sig[0] ^= 1;
  const B = (u) => Buffer.from(u).toString("base64");
  return { addr, input: { nonce, address: B(pk), signedMessage: B(mb), signature: B(sig) } };
}

test("1.2.0 SIWS: a good sign-in verifies once; replay, other user, wrong domain and bad signature fail", async () => {
  const s = kv();
  const ok = await signed(s, "user-abc-123");
  assert.deepEqual(await verifySiws(s, "user-abc-123", ok.input), { address: ok.addr });
  assert.equal((await verifySiws(s, "user-abc-123", ok.input)).error, "nonce", "nonce is single use");
  const other = await signed(s, "user-abc-123");
  assert.equal((await verifySiws(s, "someone-else-1", other.input)).error, "nonce");
  const dom = await signed(s, "user-abc-123", { domain: "evil.example" });
  assert.equal((await verifySiws(s, "user-abc-123", dom.input)).error, "message_domain");
  const bad = await signed(s, "user-abc-123", { flip: true });
  assert.equal((await verifySiws(s, "user-abc-123", bad.input)).error, "signature");
  assert.equal(messageMatches("x", signInPayload("ab"), "y"), "domain");
});

test("1.2.0 SGT: one mint = one perk", async () => {
  const s = kv();
  assert.equal((await claimMint(s, "user-a-0001", fx.sgtMint, fx.owner)).verified, true);
  assert.equal((await claimMint(s, "user-a-0001", fx.sgtMint, fx.owner)).verified, true, "same account again is fine");
  assert.deepEqual(await claimMint(s, "user-b-0002", fx.sgtMint, "Other"), { verified: false, reason: "mint_used" });
});

test("1.2.0: a verified Seeker gets the bonus secretary minutes, others hit the normal cap", async () => {
  const s = kv();
  const env = { BALANCES: s, CALL_DAILY_MIN_GLOBAL: "1000", CALL_DAILY_MIN_ACCOUNT: "20" };
  await addCallSeconds(env, "plain-user-1", 21 * 60);
  await addCallSeconds(env, "seeker-user-1", 21 * 60);
  await s.put("seeker_user:seeker-user-1", JSON.stringify({ mint: fx.sgtMint }));
  assert.equal(await callCapReason(env, "plain-user-1"), "CALL_MINUTES_ACCOUNT");
  assert.equal(await callCapReason(env, "seeker-user-1"), "", "verified Seeker has 10 more minutes");
  await addCallSeconds(env, "seeker-user-1", 10 * 60);
  assert.equal(await callCapReason(env, "seeker-user-1"), "CALL_MINUTES_ACCOUNT");
});

test("1.2.0 Season drops: curated list is dated, sourced on X, no points promises", async () => {
  assert.ok(CURATED.length >= 5);
  for (const c of CURATED) {
    assert.match(c.sourceUrl, /^https:\/\/x\.com\/\w+\/status\/\d+$/);
    assert.match(c.sourceDate, /^2026-\d\d-\d\d$/);
    assert.ok(c.perk && c.perk_uk);
    assert.doesNotMatch(c.perk + c.perk_uk, /guarantee|гарант/i);
  }
  const names = CURATED.map((c) => c.app);
  for (const n of ["TapTapTap", "DiversiFi", "Mentioned", "MattleFun", "One Arena"]) assert.ok(names.includes(n), n);
  const items = await curatedItems();
  assert.equal(new Set(items.map((i) => i.id)).size, items.length);
});

test("1.2.0 Season drops: X is off by default and says so", async () => {
  assert.equal(xEnabled({}), false);
  assert.equal(xEnabled({ SEASON_X: "1" }), false, "flag without a key stays off");
  assert.equal(xEnabled({ SEASON_X: "1", X_BEARER_TOKEN: "t" }), true);
  let called = 0;
  assert.deepEqual(await xPosts({}, async () => { called++; }), []);
  assert.equal(called, 0, "no X request while disabled");
  const v = dropsView({ items: await curatedItems(), checkedAt: 1 }, "en");
  assert.equal(v.x, false);
  assert.equal(v.sourcesText, "Reads the official Solana Mobile blog and docs. X support is built in and turns on with the paid API.");
  assert.match(SOURCES_TEXT.uk, /платним API/);
  const uk = dropsView({ items: await curatedItems() }, "uk");
  assert.match(uk.items[0].perk, /[а-яії]/i);
});

test("1.2.0 Season drops: X adapter (when switched on) reads @solanamobile Season posts", async () => {
  const env = { SEASON_X: "1", X_BEARER_TOKEN: "t" };
  const out = await xPosts(env, async (u, o) => {
    assert.match(u, /api\.x\.com\/2\/users\/1536816010375974913\/tweets/);
    assert.equal(o.headers.Authorization, "Bearer t");
    return new Response(JSON.stringify({ data: [{ id: "1", text: "Seeker Season taps in with @x. Seekers get a badge.", created_at: "2026-10-09T10:00:00Z" }, { id: "2", text: "gm" }] }));
  });
  assert.equal(out.length, 1);
  assert.equal(out[0].url, "https://x.com/solanamobile/status/1");
});

test("1.2.0 Season drops: model drops must quote the page; expired drops disappear; a page is read once", async () => {
  const text = "Seeker Season brings DemoApp. Seekers get a free badge and 2x points until the end of the month. Available now.";
  const ok = sanitizeDrops({ drops: [{ app: "DemoApp", perk: "Free badge", perk_uk: "Бейдж", deadline: "2026-10-01", link: "", quote: "Seekers get a free badge and 2x points", current: true, seeker_perk: true }, { app: "Ghost", perk: "x", perk_uk: "", deadline: "", link: "", quote: "this is not in the text at all", current: true, seeker_perk: true }, { app: "Old", perk: "Past quest", perk_uk: "", deadline: "", link: "", quote: "Seeker Season brings DemoApp.", current: false, seeker_perk: true }] }, text);
  assert.equal(ok.drops.length, 1);
  assert.equal(ok.dropped, 2, "unquoted and past drops are dropped");
  assert.equal(live({ deadline: "2026-10-01" }, "2026-10-10"), false);
  assert.equal(live({ deadline: "" }, "2026-10-10"), true);

  const store = kv();
  const html = "<html><title>Post</title><article>" + text.repeat(5) + " Oct 3, 2026</article></html>";
  const sitemap = "<urlset><url><loc>https://solanamobile.com/blog/demo</loc></url></urlset>";
  const fetcher = async (u) => new Response(u.endsWith("sitemap.xml") ? sitemap : html);
  let calls = 0;
  const extractor = async () => { calls++; return { drops: [{ app: "DemoApp", perk: "Free badge", perk_uk: "Бейдж", deadline: "", link: "", quote: "Seekers get a free badge" }] }; };
  const r1 = await checkDrops({ BALANCES: store }, { fetcher, extractor, now: Date.parse("2026-10-10T08:00:00Z") });
  assert.ok(r1.drops.items.some((i) => i.app === "DemoApp" && i.origin === "blog"));
  assert.ok(r1.drops.items.some((i) => i.origin === "curated"));
  const before = calls;
  const r2 = await checkDrops({ BALANCES: store }, { fetcher, extractor, now: Date.parse("2026-10-10T14:00:00Z") });
  assert.equal(calls, before, "unchanged pages are not re-read by the model");
  assert.equal(r2.newItems, 0);
  assert.equal(r2.drops.x, false);
});

test("1.2.0 SKR: call payment cards accept SKR", () => {
  assert.ok(PAY_TOKENS.includes("SKR"));
  const call = { id: "c1", lang: "en", intent: "", notes: "", text: "Ira: please send me 50 SKR for the tickets", callback: "" };
  const out = sanitizeActions({ actions: [{ callId: "c1", type: "payment", amount: 50, token: "skr", recipient: "Ira", number: "", when: "", day: "", date: "", text: "Pay Ira 50 SKR for the tickets", quote: "send me 50 SKR" }] }, [call], "en");
  const pay = out.find((a) => a.type === "payment");
  assert.ok(pay, JSON.stringify(out));
  assert.equal(pay.token, "SKR");
  assert.equal(pay.amount, 50);
  assert.equal(pay.address, "", "the address is never taken from the call unless said");
});

test("1.2.0 briefing: season tasks pass through, clipped, and the prompt forbids points promises", () => {
  const f = briefingFacts({ facts: { seasonTasks: ["MattleFun: Turn One Up event", "x".repeat(400), "a", "b"] } });
  assert.equal(f.seasonTasks.length, 3);
  assert.ok(f.seasonTasks[1].length <= 160);
  assert.match(briefingSystem("en"), /never promise points/);
});
