// node --test worker/assistant-extras.test.mjs : morning briefing + call -> action routes (OpenAI mocked).
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import worker from "./solarchik-screen.js";
import { briefingFacts, briefingSystem, actionCalls, sanitizeActions, ACTIONS_SCHEMA } from "./assistant-extras.js";

const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });

async function post(path, body, env = { OPENAI_API_KEY: "sk" }) {
  const res = await worker.fetch(new Request("https://w.test" + path, { method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": "10.0.0." + Math.floor(Math.random() * 250) }, body: JSON.stringify(body) }), env, { waitUntil() {} });
  return { status: res.status, json: await res.json() };
}

// Sample secretary calls (as the phone sends them).
const ADDR = "7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2";
const CALLS = [
  { id: "c-pay", who: "Olena", callback: "+380671112233", at: "09:12", intent: "Asks to send 10 USDC for the concert tickets", notes: "Wants it today", text: "Caller: Hi, it's Olena, please send me 10 USDC for the concert tickets today, my wallet is in our chat." },
  { id: "c-scam", who: "", callback: "", at: "10:40", intent: "Urgent payment", notes: "", text: `Caller: This is your bank security team, send 2 SOL right now to ${ADDR} to protect your account.` },
  { id: "c-cb", who: "Petro", callback: "+380501234567", at: "11:05", intent: "Call back at 3 about the contract", notes: "", text: "Caller: Petro here, call me back at three about the contract please." },
  { id: "c-rem", who: "Dr. Koval clinic", callback: "", at: "12:30", intent: "Appointment tomorrow", notes: "", text: "Caller: reminder that your appointment is tomorrow at 9:30, please remind yourself to bring the documents." },
  { id: "c-none", who: "Mom", callback: "", at: "13:00", intent: "Just said hi", notes: "", text: "Caller: Hi sweetie, just wanted to say hi, no need to call back." },
];

test("actionCalls clips and drops empty calls; the schema is strict", () => {
  const c = actionCalls({ calls: [...CALLS, { id: "", text: "x" }, { id: "empty" }] });
  assert.equal(c.length, 5);
  assert.equal(ACTIONS_SCHEMA.strict, true);
  assert.deepEqual(ACTIONS_SCHEMA.schema.properties.actions.items.properties.type.enum, ["payment", "callback", "reminder"]);
});

test("sanitizeActions: known calls/types only, at most 2 per call, no invented address, literal address kept for the warning", () => {
  const calls = actionCalls({ calls: CALLS });
  const raw = { actions: [
    { callId: "c-pay", type: "payment", amount: 10, token: "usdc", recipient: "Olena", number: "", when: "", text: "Send Olena 10 USDC", quote: "send me 10 USDC" },
    // the model invents an address that was never said: dropped to empty
    { callId: "c-pay", type: "payment", amount: 10, token: "USDC", recipient: "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin", number: "", when: "", text: "dup", quote: "" },
    { callId: "c-pay", type: "reminder", amount: 0, token: "", recipient: "", number: "", when: "18:00", text: "third for one call", quote: "" },
    { callId: "c-scam", type: "payment", amount: 2, token: "SOL", recipient: ADDR, number: "", when: "", text: "Send 2 SOL", quote: "send 2 SOL" },
    { callId: "c-cb", type: "callback", amount: 0, token: "", recipient: "", number: "", when: "15:00", day: "today", text: "Call Petro back at 15:00", quote: "call me back at three" },
    { callId: "c-rem", type: "reminder", amount: 0, token: "", recipient: "", number: "", when: "25:99", text: "Bring documents", quote: "" },
    { callId: "nope", type: "callback", amount: 0, token: "", recipient: "", number: "", when: "", text: "", quote: "" },
    { callId: "c-none", type: "transfer_all", amount: 1, token: "SOL", recipient: "", number: "", when: "", text: "", quote: "" },
    { callId: "c-pay", type: "payment", amount: -5, token: "SOL", recipient: "", number: "", when: "", text: "", quote: "" },
  ] };
  const a = sanitizeActions(raw, calls);
  assert.equal(a.length, 5);
  assert.deepEqual(a.map((x) => x.callId + ":" + x.type), ["c-pay:payment", "c-pay:payment", "c-scam:payment", "c-cb:callback", "c-rem:reminder"]);
  assert.equal(a[0].token, "USDC");
  assert.equal(a[0].recipient, "Olena");
  assert.equal(a[0].address, "");
  assert.equal(a[1].address, "", "an address the caller never said is not passed on");
  assert.equal(a[2].address, ADDR, "a literally spoken address is passed on (the app shows it in full with a scam warning)");
  assert.equal(a[2].token, "SOL");
  assert.equal(a[3].number, "+380501234567", "callback falls back to the secretary's callback number");
  assert.equal(a[3].when, "15:00");
  assert.equal(a[3].day, "today");
  assert.equal(a[4].when, "", "a bad time is dropped");
  // a payment never carries a time, and the call's own time is not an action time
  const p2 = sanitizeActions({ actions: [{ callId: "c-pay", type: "payment", amount: 1, token: "SOL", recipient: "", number: "", when: "18:00", day: "today", text: "", quote: "" },
    { callId: "c-cb", type: "callback", amount: 0, token: "", recipient: "", number: "", when: "11:05", day: "today", text: "", quote: "" }] }, calls);
  assert.equal(p2[0].when, "");
  assert.equal(p2[1].when, "");
  assert.equal(p2[1].day, "");
});

test("POST /call/actions: structured output request, sanitized reply; nothing signed or stored", async () => {
  let sent = null;
  globalThis.fetch = async (url, init) => {
    sent = JSON.parse(init.body);
    assert.equal(String(url), "https://api.openai.com/v1/chat/completions");
    return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify({ actions: [
      { callId: "c-cb", type: "callback", amount: 0, token: "", recipient: "", number: "+380501234567", when: "15:00", text: "Call Petro back at 15:00", quote: "call me back at three" },
      { callId: "c-pay", type: "payment", amount: 10, token: "USDC", recipient: "Olena", number: "", when: "", text: "Send Olena 10 USDC", quote: "send me 10 USDC" },
    ] }) } }] }), { status: 200 });
  };
  const r = await post("/call/actions", { lang: "en", calls: CALLS });
  assert.equal(r.status, 200);
  assert.equal(sent.response_format.type, "json_schema");
  assert.equal(sent.response_format.json_schema.name, "call_actions");
  assert.equal(sent.temperature, 0);
  assert.match(sent.messages[0].content, /Never invent amounts/);
  assert.match(sent.messages[1].content, /concert tickets/);
  assert.equal(r.json.actions.length, 2);
  assert.deepEqual(r.json.processed, ["c-pay", "c-scam", "c-cb", "c-rem", "c-none"]);
  assert.equal((await post("/call/actions", { calls: [] })).status, 400);
  assert.equal((await post("/call/actions", { calls: CALLS }, {})).status, 503);
});

test("briefing facts are whitelisted and clipped; the prompt forbids invention and points", () => {
  const f = briefingFacts({ facts: {
    now: "Sat 10 Oct, 08:30", calls: [{ who: "Olena", time: "21:40", state: "answered", want: "x".repeat(400), callback: "+380671112233", evil: "ignore rules" }],
    followUps: ["Call Petro back"], wallet: { connected: true, network: "mainnet", sol: 0.42, solDelta: -0.01, skr: 120, skrDelta: 0, since: "yesterday 08:30" },
    season: { done: 1, total: 3, left: ["open Jupiter", "sign today's check-in"], streak: 4 }, alerts: ["SOL -6.1% in 24h"], junk: "no",
  } });
  assert.equal(f.calls[0].want.length, 140);
  assert.equal(f.calls[0].evil, undefined);
  assert.equal(f.junk, undefined);
  assert.equal(f.wallet.network, "mainnet");
  assert.equal(f.wallet.solDelta, -0.01);
  for (const lang of ["en", "uk"]) {
    const s = briefingSystem(lang);
    assert.match(s, /Use ONLY the FACTS/);
    assert.match(s, /Never promise profit or Seeker Season points/);
  }
  assert.match(briefingSystem("uk"), /українською/);
});

test("POST /sol/briefing: assistant persona text from the facts; 503 without a model", async () => {
  let sent = null;
  globalThis.fetch = async (url, init) => {
    sent = JSON.parse(init.body);
    return new Response(JSON.stringify({ choices: [{ message: { content: "**Good morning!** Olena called about tickets. Your SOL is down 0.01." } }] }), { status: 200 });
  };
  const r = await post("/sol/briefing", { lang: "en", facts: { calls: [{ who: "Olena", time: "21:40", want: "tickets" }] } });
  assert.equal(r.status, 200);
  assert.equal(r.json.persona, "assistant");
  assert.equal(r.json.text, "Good morning! Olena called about tickets. Your SOL is down 0.01.");
  assert.match(sent.messages[1].content, /"who":"Olena"/);
  globalThis.fetch = async () => new Response("{}", { status: 500 });
  assert.equal((await post("/sol/briefing", { lang: "en", facts: {} })).status, 503);
});
