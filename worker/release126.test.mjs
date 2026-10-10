import test, { afterEach } from "node:test";
import assert from "node:assert/strict";
import { handleIncoming, startVerify, phoneStatus, forwardExpect, callerHash, playerRoute, callParties } from "./solarchik-screen.js";

function kv() {
  const m = new Map();
  return { m, get: async (k) => (m.has(k) ? m.get(k) : null), put: async (k, v) => void m.set(k, String(v)), delete: async (k) => void m.delete(k) };
}
const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });
function openai() {
  const calls = [];
  globalThis.fetch = async (url, init) => { calls.push({ url: String(url), body: init?.body }); return new Response("{}", { status: 200 }); };
  return calls;
}
const USER = "0f8e2c1a-1111-4a2b-9c3d-abcdefabcdef";
const LINE = "+380914810885";
const ME = "+380637443792";
const FRIEND = "+380501112233";
const direct = (from) => [
  { name: "From", value: `<sip:${from}@pbx.zadarma.com>;tag=1` },
  { name: "To", value: "<sip:proj_123@sip.api.openai.com>" },
  { name: "P-Called-Party-ID", value: `<sip:${LINE}@pbx.zadarma.com>` },
];

test("verify by calling the line from that SIM: linked, rejected, never charged", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk" };
  assert.equal((await startVerify(env, { userId: USER, number: "063 744 37 92" })).body.number, ME, "a Ukrainian local number is normalised");
  assert.equal((await startVerify(env, { userId: USER, number: LINE })).status, 400, "not the secretary line itself");
  assert.equal((await phoneStatus(env, USER)).verified, false);
  const calls = openai();
  const r = await handleIncoming(env, "https://w", "rtc_verify", direct(ME));
  assert.equal(r.body.verified, true);
  assert.ok(calls.some((c) => c.url.endsWith("/reject")));
  assert.ok(!calls.some((c) => c.url.endsWith("/accept")), "not answered");
  assert.deepEqual(await phoneStatus(env, USER), { userId: USER, number: ME, verified: true, line: LINE });
  assert.equal(await env.BALANCES.get("inbox:" + USER), null, "nothing in the inbox, nothing charged");
});

test("a forwarded call with Diversion = the verified number goes to that user", async () => {
  const env = { BALANCES: kv() };
  await env.BALANCES.put("phone:" + ME, USER);
  const p = callParties([...direct(FRIEND), { name: "Diversion", value: `<tel:${ME}>;reason=no-answer` }], env);
  assert.equal((await playerRoute(env, p)).userId, USER);
  assert.equal((await playerRoute(env, p)).via, "phone");
});

test("no Diversion: the user's phone screened that caller a moment ago (hash only), the call goes to them", async () => {
  const env = { BALANCES: kv(), OWNER_USER_ID: "owner-demo-account-0001" };
  const h = await callerHash(FRIEND);
  assert.equal((await forwardExpect(env, { userId: USER, h })).status, 403, "only a verified phone may claim callers");
  await env.BALANCES.put("phone:" + ME, USER);
  await env.BALANCES.put("phoneof:" + USER, ME);
  assert.equal((await forwardExpect(env, { userId: USER, h })).status, 200);
  const r = await playerRoute(env, callParties(direct(FRIEND), env));
  assert.deepEqual(r, { userId: USER, via: "screened" });
  // a different caller is not theirs: the demo line's own routing applies
  assert.notEqual((await playerRoute(env, callParties(direct("+380671234567"), env))).via, "screened");
  assert.equal(h, await callerHash("+380 50 111 22 33"), "same number, same hash");
});
