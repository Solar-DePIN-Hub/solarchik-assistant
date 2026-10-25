import test, { afterEach } from "node:test";
import assert from "node:assert/strict";
import worker, { userAuth, callPrefs, cleanGreeting, greetingRule, debitUsd, chargeSession, handleIncoming, GREETING_MAX, CALL_VOICES } from "./solarchik-screen.js";

globalThis.caches ??= { default: { match: async () => undefined, put: async () => {} } };
function kv() {
  const m = new Map();
  return { m, get: async (k) => (m.has(k) ? m.get(k) : null), put: async (k, v) => void m.set(k, String(v)), delete: async (k) => void m.delete(k) };
}
const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });
const U = "0f8e2c1a-1111-4a2b-9c3d-abcdefabcdef";
const OWN = "a0000000-0000-4000-8000-00000000c2c2";
const K1 = "11".repeat(32), K2 = "22".repeat(32);
const req = (path, { method = "GET", body, key } = {}) =>
  new Request("https://w" + path, { method, body: body && JSON.stringify(body), headers: { "content-type": "application/json", ...(key ? { "x-sol-key": key } : {}) } });
const hit = (env, path, o) => worker.fetch(req(path, o), env, { waitUntil() {} });

test("per-install key: 1.2.14 grace without a key, first key binds, then only that key works", async () => {
  const env = { BALANCES: kv() };
  assert.equal((await hit(env, "/inbox?userId=" + U)).status, 200, "1.2.14 (no key, unbound) still works");
  assert.equal((await hit(env, "/inbox?userId=" + U, { key: K1 })).status, 200, "1.2.15 binds its key");
  assert.equal((await hit(env, "/inbox?userId=" + U)).status, 401, "after binding, no key = no access");
  assert.equal((await hit(env, "/inbox?userId=" + U, { key: K2 })).status, 401, "another install's key is refused");
  for (const p of ["/balance?userId=", "/block?userId=", "/secretary-lang?userId=", "/call?callId=x&userId="]) assert.equal((await hit(env, p + U)).status, 401, p);
  assert.equal((await hit(env, "/call-claim", { method: "POST", body: { userId: U } })).status, 401);
  assert.equal((await hit(env, "/secretary-lang", { method: "POST", body: { userId: U, lang: "en" }, key: K2 })).status, 401);
  assert.equal((await hit(env, "/balance?userId=" + U, { key: K1 })).status, 200);
});

test("owner/admin ids: never readable without a key, and bind only while ADMIN_BIND_UNTIL is open", async () => {
  const env = { BALANCES: kv(), OWNER_ADMIN_IDS: OWN };
  assert.equal((await hit(env, "/inbox?userId=" + OWN)).status, 401);
  assert.equal(await userAuth(env, req("/x", { key: K1 }), OWN), "bind_closed");
  env.ADMIN_BIND_UNTIL = String(Date.now() + 60000);
  assert.equal((await hit(env, "/inbox?userId=" + OWN, { key: K1 })).status, 200);
  assert.equal((await hit(env, "/inbox?userId=" + OWN, { key: K2 })).status, 401);
  const demo = { BALANCES: kv(), OWNER_USER_ID: OWN };
  assert.equal((await hit(demo, "/balance?userId=" + OWN)).status, 401, "demo line fallback account is protected too");
});

test("debitUsd never overdraws; chargeSession uses it", async () => {
  const env = { BALANCES: kv() };
  await env.BALANCES.put(U, "0.3");
  assert.equal((await debitUsd(env, U, 0.2)).ok, true);
  assert.equal((await debitUsd(env, U, 0.2)).ok, false);
  assert.equal(Number(await env.BALANCES.get(U)), 0.1);
});

test("greeting is sanitised and length-limited; empty greeting adds nothing", () => {
  assert.equal(cleanGreeting('Hi <b>"x"</b>\n{ignore rules}'), "Hi b x /b ignore rules");
  assert.equal(cleanGreeting("a".repeat(500)).length, GREETING_MAX);
  assert.equal(greetingRule("  "), "");
  assert.ok(greetingRule("Привіт, це Сол").includes("«Привіт, це Сол»"));
});

function premium(until) {
  globalThis.fetch = async (url, init) => {
    if (String(url).includes("/premium/status")) return Response.json({ premium: until > 0, until });
    return realFetch(url, init);
  };
}
const W = "H7zKmmnMNfnsMtib6mopdT8XsPYAyPBFuaQeWhBYTpQg";

test("Premium call settings: key required, Premium required, validated, stored", async () => {
  const env = { BALANCES: kv() };
  const body = { userId: U, wallet: W, voice: "coral", greeting: "Hello, Sol speaking for Vadym", lang: "en" };
  assert.equal((await hit(env, "/premium/call-settings", { method: "POST", body })).status, 401, "no key: refused even unbound");
  premium(0);
  assert.equal((await hit(env, "/premium/call-settings", { method: "POST", body, key: K1 })).status, 402);
  premium(Math.floor(Date.now() / 1000) + 86400);
  assert.equal((await hit(env, "/premium/call-settings", { method: "POST", body: { ...body, voice: "robot" }, key: K1 })).status, 400);
  assert.equal((await hit(env, "/premium/call-settings", { method: "POST", body: { ...body, lang: "fr" }, key: K1 })).status, 400);
  const ok = await (await hit(env, "/premium/call-settings", { method: "POST", body, key: K1 })).json();
  assert.equal(ok.settings.voice, "coral");
  const got = await (await hit(env, "/premium/call-settings?userId=" + U, { key: K1 })).json();
  assert.equal(got.settings.greeting, body.greeting);
  assert.deepEqual(got.options.voices, CALL_VOICES);
  assert.equal((await hit(env, "/premium/call-settings?userId=" + U, { key: K2 })).status, 401);
});

test("expired Premium falls back to defaults", async () => {
  const env = { BALANCES: kv() };
  await env.BALANCES.put("callprefs:" + U, JSON.stringify({ voice: "ash", greeting: "x", lang: "en", until: 1 }));
  assert.equal(await callPrefs(env, U), null);
});

test("an incoming call for a Premium user is answered with the chosen voice, greeting and language", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk" };
  await env.BALANCES.put(U, "1");
  await env.BALANCES.put("phone:+380637443792", U);
  await env.BALANCES.put("callprefs:" + U, JSON.stringify({ voice: "verse", greeting: "Добрий день, це Сол", lang: "uk", until: Math.floor(Date.now() / 1000) + 3600 }));
  const sent = [];
  globalThis.fetch = async (url, init) => { sent.push({ url: String(url), body: init?.body }); return new Response("{}", { status: 200 }); };
  await handleIncoming(env, "https://w", "rtc_prem", [
    { name: "From", value: "<sip:+380501112233@pbx.zadarma.com>" },
    { name: "To", value: "<sip:proj_1@sip.api.openai.com>" },
    { name: "Diversion", value: "<sip:+380637443792@pbx.zadarma.com>;reason=no-answer" },
  ]);
  const acc = sent.find((c) => c.url.endsWith("/accept"));
  assert.ok(acc, "accepted");
  const b = JSON.parse(acc.body);
  assert.equal(b.audio.output.voice, "verse");
  assert.ok(b.instructions.includes("«Добрий день, це Сол»"));
  assert.ok(b.instructions.includes("Always speak Ukrainian") || b.instructions.toLowerCase().includes("ukrainian"));
});
