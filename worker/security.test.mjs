import test from "node:test";
import assert from "node:assert/strict";
import worker, { CallRoom, claimOnce, creditUsd, isOwnLine, callParties, startVerify, SECRETARY_LINES } from "./solarchik-screen.js";
import { claimMint } from "./seeker-verify.js";

function kv() {
  const m = new Map();
  return { m, get: async (k) => (m.has(k) ? m.get(k) : null), put: async (k, v) => void m.set(k, String(v)), delete: async (k) => void m.delete(k) };
}
/** A CALLS namespace backed by real CallRoom objects with a serialising in-memory storage (like a DO input gate). */
function calls(env) {
  const rooms = new Map();
  let chain = Promise.resolve();
  return {
    idFromName: (n) => n,
    get: (n) => {
      if (!rooms.has(n)) {
        const m = new Map();
        const storage = { get: async (k) => m.get(k), put: async (k, v) => void m.set(k, v), delete: async (k) => void m.delete(k) };
        rooms.set(n, new CallRoom({ storage }, env));
      }
      const room = rooms.get(n);
      return { fetch: (u, init) => (chain = chain.then(() => room.fetch(new Request(u, init)))) };
    },
  };
}
globalThis.caches ??= { default: { match: async () => undefined, put: async () => {} } };
const A = "0f8e2c1a-1111-4a2b-9c3d-abcdefabcdef";
const B = "1a2b3c4d-2222-4a2b-9c3d-abcdefabcdef";

test("claimOnce: concurrent claims of one payment signature, exactly one wins", async () => {
  const env = { BALANCES: kv() };
  env.CALLS = calls(env);
  const r = await Promise.all(Array.from({ length: 10 }, (_, i) => claimOnce(env, "paid:SIG", i % 2 ? A : B)));
  assert.equal(r.filter((x) => x.first).length, 1);
  assert.ok(r.every((x) => x.value === r.find((y) => y.first).value));
});

test("creditUsd: concurrent credits never lose an update", async () => {
  const env = { BALANCES: kv() };
  env.CALLS = calls(env);
  await Promise.all(Array.from({ length: 20 }, () => creditUsd(env, A, 0.5)));
  assert.equal(Number(await env.BALANCES.get(A)), 10);
});

test("Seeker genesis token: two accounts verifying one mint at once, only one gets it", async () => {
  const env = { BALANCES: kv() };
  env.CALLS = calls(env);
  const once = (k, v) => claimOnce(env, k, v);
  const [x, y] = await Promise.all([claimMint(env.BALANCES, A, "MINT1", "addrA", 1, once), claimMint(env.BALANCES, B, "MINT1", "addrB", 1, once)]);
  assert.equal([x, y].filter((r) => r.verified).length, 1);
  assert.equal([x, y].find((r) => !r.verified).reason, "mint_used");
  assert.equal((await claimMint(env.BALANCES, x.verified ? A : B, "MINT1", "addr", 2, once)).verified, true, "same owner re-verifies");
});

test("/call: a transcript is never returned for a call outside the caller's own inbox (no IDOR)", async () => {
  const env = { BALANCES: kv() };
  await env.BALANCES.put("inbox:" + A, JSON.stringify([{ callId: "rtc_a", caller: "+…1", text: "hi", at: 1 }]));
  await env.BALANCES.put("transcript:rtc_a", JSON.stringify({ lines: [{ who: "caller", text: "secret" }] }));
  const get = (u) => worker.fetch(new Request("https://w" + u), env, { waitUntil() {} });
  assert.equal((await get(`/call?userId=${A}&callId=rtc_a`)).status, 200);
  const r = await get(`/call?userId=${B}&callId=rtc_a`);
  assert.equal(r.status, 404);
  assert.ok(!(await r.text()).includes("secret"));
});

test("/inbox and /balance reject malformed user ids", async () => {
  const env = { BALANCES: kv() };
  for (const u of ["/inbox?userId=x", "/balance?userId=a%20b"]) assert.equal((await worker.fetch(new Request("https://w" + u), env, {})).status, 400);
});

test("operator-only account/number routes refuse requests without the token", async () => {
  const env = { BALANCES: kv(), SECRETARY_TOKEN: "t0ken-secret" };
  const post = (p, b, h = {}) => worker.fetch(new Request("https://w" + p, { method: "POST", body: JSON.stringify(b), headers: h }), env, {});
  assert.equal((await post("/phone", { number: "+380501112233", userId: A })).status, 401);
  assert.equal((await post("/expect", { userId: A })).status, 401);
  assert.equal((await post("/voicemail", { userId: A, text: "x" })).status, 401);
  assert.equal((await post("/phone", { number: "+380501112233", userId: A }, { Authorization: "Bearer wrong" })).status, 401);
  assert.equal(await env.BALANCES.get("phone:+380501112233"), null);
});

test("every Sol line (UA, US, Canada, UK) is the secretary's own line and cannot be claimed as a user phone", async () => {
  const env = { BALANCES: kv() };
  for (const n of SECRETARY_LINES) {
    const p = callParties([{ name: "From", value: "<sip:+15551234567@x>" }, { name: "To", value: `<sip:${n}@sip.api.openai.com>` }]);
    assert.equal(isOwnLine(env, p), true, n);
    assert.equal((await startVerify(env, { userId: A, number: n })).status, 400, n);
  }
});
