// Tests for the 0.21.8 worker changes: RPC choice for top-ups, once-only incoming calls (call room + markers),
// the after-call note, and the Sol chat/voice routes. Run: node --test worker/solarchik-screen.test.mjs
import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import worker, {
  RPCS, rpcUrls, handleIncoming, finishNote, transcriptLine, CallRoom, AUTO_NOTE_EMPTY, NOTE_FIRST, voiceFor,
  solAction, solCtxLines, solSystem, readChatStream, resetDedupMemory, SOL_DEFAULT_VOICE, solRateOk,
  canonCallId, cleanInbox, isBlocked, saveTranscript, speakable,
  solState, solStateLine, solIsAssistant, solAssistantSystem,
  chargeSession, takeTrialSlot, releaseTrialSlot, refundShortCall, isAdmin, ADMIN_USER_IDS, SHORT_CALL_SEC,
} from "./solarchik-screen.js";

const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
  resetDedupMemory();
});

function kv() {
  const m = new Map();
  return { m, get: async (k) => (m.has(k) ? m.get(k) : null), put: async (k, v) => void m.set(k, String(v)), delete: async (k) => void m.delete(k) };
}
async function call(env, path, body, method = "POST", headers = {}, ctx) {
  const res = await worker.fetch(
    new Request("https://solarchik-screen.example" + path, {
      method,
      headers: { "content-type": "application/json", ...headers },
      body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
    }),
    env,
    ctx,
  );
  const text = await res.text();
  let j = null;
  try {
    j = JSON.parse(text);
  } catch {
    j = null;
  }
  return { status: res.status, json: j, text, headers: res.headers };
}

const USER = "0f8e2c1a-1111-4a2b-9c3d-abcdefabcdef";
const OWNER = "owner-demo-account-0001";
const SIG = "5".repeat(87);
const REF = "Ref1111111111111111111111111111111111111111";
const ZADARMA = [
  { name: "From", value: '"Dana" <sip:+380638500117@pbx.zadarma.com>;tag=1' },
  { name: "To", value: "<sip:proj_123@sip.api.openai.com>" },
  { name: "P-Called-Party-ID", value: "<sip:+380914810885@pbx.zadarma.com>" },
];

// ---------------- top-up RPC ----------------

test("RPC list: SOLANA_RPC first, every public fallback after mainnet-beta (403 from Workers)", () => {
  assert.equal(RPCS[0], "https://api.mainnet-beta.solana.com");
  assert.ok(RPCS.includes("https://solana-rpc.publicnode.com"));
  assert.ok(RPCS.includes("https://public.rpc.solanavibestation.com"));
  assert.deepEqual(rpcUrls({ SOLANA_RPC: "https://my.rpc/x" })[0], "https://my.rpc/x");
  assert.equal(new Set(rpcUrls({ SOLANA_RPC: RPCS[1] })).size, RPCS.length, "no duplicates");
});

test("topup by reference: a 403 host and a pruned node ([] history) fall through to the node that has the payment", async () => {
  const env = { BALANCES: kv() };
  const PAY = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic";
  const USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
  const memo = USER.slice(0, 32);
  const tx = {
    blockTime: Math.floor(Date.now() / 1000) - 60,
    meta: {
      err: null,
      preTokenBalances: [{ mint: USDC, owner: PAY, uiTokenAmount: { amount: "0" } }],
      postTokenBalances: [{ mint: USDC, owner: PAY, uiTokenAmount: { amount: "3000000" } }],
      innerInstructions: [],
      logMessages: [],
    },
    transaction: { message: { accountKeys: [{ pubkey: REF }], instructions: [{ program: "spl-memo", parsed: memo }] } },
  };
  const seen = [];
  globalThis.fetch = async (url, init) => {
    const { method } = JSON.parse(init.body);
    seen.push(new URL(url).host + ":" + method);
    if (String(url).includes("mainnet-beta")) return new Response('{"jsonrpc":"2.0","error":{"code":403,"message":"blocked"},"id":1}', { status: 403 });
    if (String(url).includes("publicnode")) return Response.json({ jsonrpc: "2.0", id: 1, result: method === "getTransaction" ? null : [] });
    return Response.json({ jsonrpc: "2.0", id: 1, result: method === "getTransaction" ? tx : [{ signature: SIG, err: null }] });
  };
  const r = await call(env, "/topup", { userId: USER, ref: REF });
  assert.equal(r.status, 200, JSON.stringify(r.json));
  assert.equal(r.json.added, 3);
  assert.deepEqual(seen.slice(0, 3), ["api.mainnet-beta.solana.com:getSignaturesForAddress", "solana-rpc.publicnode.com:getSignaturesForAddress", "public.rpc.solanavibestation.com:getSignaturesForAddress"]);
});

test("topup by reference with no payment yet: every node empty -> PAYMENT_NOT_FOUND (not RPC_UNAVAILABLE)", async () => {
  globalThis.fetch = async (url) =>
    String(url).includes("mainnet-beta") ? new Response("{}", { status: 403 }) : Response.json({ jsonrpc: "2.0", id: 1, result: [] });
  const r = await call({ BALANCES: kv() }, "/topup", { userId: USER, ref: REF });
  assert.equal(r.status, 402);
  assert.equal(r.json.error, "PAYMENT_NOT_FOUND");
});

test("GET /rpc-health lists each RPC with status and history count; SOLANA_RPC host is hidden", async () => {
  globalThis.fetch = async (url) =>
    String(url).includes("mainnet-beta") ? new Response('{"error":{"code":403}}', { status: 403 }) : Response.json({ jsonrpc: "2.0", id: 1, result: [{ signature: "x" }] });
  const r = await call({ BALANCES: kv(), SOLANA_RPC: "https://secret.example/key=abc" }, "/rpc-health", undefined, "GET");
  assert.equal(r.json.rpcs[0].host, "env");
  assert.ok(!r.text.includes("secret.example"));
  assert.equal(r.json.rpcs.find((x) => x.host === "api.mainnet-beta.solana.com").status, "rpc 403");
  assert.equal(r.json.rpcs.find((x) => x.host === "solana-rpc.publicnode.com").history, 1);
});

// ---------------- incoming calls: once-only ----------------

function openai({ acceptStatus = () => 200 } = {}) {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    const body = init?.body ? JSON.parse(init.body) : null;
    calls.push({ url: String(url), body });
    if (String(url).endsWith("/accept")) return new Response("{}", { status: acceptStatus(calls.filter((c) => c.url.endsWith("/accept")).length) });
    return new Response("{}", { status: 200 });
  };
  return calls;
}

test("live bug …qjb3: second delivery after the first was accepted never charges, refunds or rewrites the inbox", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER };
  await env.BALANCES.put(OWNER, "1");
  // The first delivery answers; then the second one's accept would fail (OpenAI: call already accepted).
  const calls = openai({ acceptStatus: (n) => (n === 1 ? 200 : 400) });
  const a = await handleIncoming(env, "https://w", "rtc_5akbqjb3", ZADARMA);
  assert.equal(a.body.accepted, true);
  const b = await handleIncoming(env, "https://w", "rtc_5akbqjb3", ZADARMA);
  assert.deepEqual(b.body, { ok: true, duplicate: true });
  assert.equal(calls.filter((c) => c.url.endsWith("/accept")).length, 1, "the duplicate never calls accept");
  assert.equal(await env.BALANCES.get(OWNER), "0.8", "charged exactly once, no refund");
  const inbox = JSON.parse(await env.BALANCES.get("inbox:" + OWNER));
  assert.equal(inbox.length, 1);
  assert.equal(inbox[0].status, "pending");
  assert.match(inbox[0].text, /answered/);
});

test("a delivery whose accept fails while another already accepted the call does not refund or mark failed", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER };
  await env.BALANCES.put(OWNER, "1");
  // Simulate the race: while this delivery waits on OpenAI, another isolate marks the call accepted.
  globalThis.fetch = async (url) => {
    if (String(url).endsWith("/accept")) {
      await env.BALANCES.put("call:rtc_race", JSON.stringify({ userId: OWNER, state: "accepted" }));
      return new Response('{"error":"already accepted"}', { status: 409 });
    }
    return new Response("{}", { status: 200 });
  };
  const r = await handleIncoming(env, "https://w", "rtc_race", ZADARMA);
  assert.equal(r.body.duplicate, true);
  assert.equal(r.body.refunded, undefined);
  const inbox = JSON.parse(await env.BALANCES.get("inbox:" + OWNER));
  assert.notEqual(inbox[0].status, "failed");
});

test("a real failed accept still refunds, marks the line failed and lets OpenAI retry", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER };
  await env.BALANCES.put(OWNER, "1");
  openai({ acceptStatus: () => 500 });
  const r = await handleIncoming(env, "https://w", "rtc_down", ZADARMA);
  assert.equal(r.body.refunded, true);
  assert.equal(await env.BALANCES.get(OWNER), "1");
  assert.equal(JSON.parse(await env.BALANCES.get("inbox:" + OWNER))[0].status, "failed");
  assert.equal(await env.BALANCES.get("call:rtc_down"), null, "marker cleared for the retry");
  openai();
  assert.equal((await handleIncoming(env, "https://w", "rtc_down", ZADARMA)).body.accepted, true);
});

function roomState() {
  const m = new Map();
  let alarm = null;
  return {
    m,
    get alarm() {
      return alarm;
    },
    storage: {
      get: async (k) => (Array.isArray(k) ? new Map(k.map((x) => [x, m.get(x)])) : m.get(k)),
      put: async (k, v) => {
        if (typeof k === "object") for (const [a, b] of Object.entries(k)) m.set(a, b);
        else m.set(k, v);
      },
      delete: async (k) => void m.delete(k),
      setAlarm: async (t) => void (alarm = t),
      deleteAlarm: async () => void (alarm = null),
    },
  };
}

test("CallRoom: two deliveries of one call (166 ms apart or at once) -> one accept, one charge; alarm set", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER };
  await env.BALANCES.put(OWNER, "1");
  const calls = openai();
  const st = roomState();
  const room = new CallRoom(st, env);
  room.watch = async () => {}; // no sideband socket in tests
  const req = () => new Request("https://call-room/incoming", { method: "POST", body: JSON.stringify({ origin: "https://w", callId: "rtc_room", sipHeaders: ZADARMA }) });
  const [a, b] = await Promise.all([room.fetch(req()), room.fetch(req())]);
  const ja = await a.json();
  const jb = await b.json();
  assert.equal([ja, jb].filter((x) => x.duplicate).length, 1);
  assert.equal([ja, jb].filter((x) => x.accepted).length, 1);
  assert.equal(calls.filter((c) => c.url.endsWith("/accept")).length, 1);
  assert.equal(await env.BALANCES.get(OWNER), "0.8");
  assert.equal(st.m.get("state"), "accepted");
  assert.ok(st.alarm > Date.now() + 15 * 60 * 1000);
});

test("/sip goes through the CALLS room when bound", async () => {
  const seen = [];
  const env = {
    BALANCES: kv(),
    CALLS: {
      idFromName: (n) => "id:" + n,
      get: (id) => ({ fetch: async (u, init) => (seen.push([id, JSON.parse(init.body).callId]), Response.json({ accepted: true, via: "room" })) }),
    },
  };
  const r = await call(env, "/sip", JSON.stringify({ type: "realtime.call.incoming", data: { call_id: "rtc_x", sip_headers: ZADARMA } }));
  assert.equal(r.json.via, "room");
  assert.deepEqual(seen, [["id:x", "rtc_x"]], "room named by the canonical id (rtc_/live_ stripped)");
});

// ---------------- the note ----------------

test("instructions put the note tool first; the tool schema has no company field", async () => {
  const v = voiceFor("uk", true);
  assert.ok(v.includes(NOTE_FIRST));
  assert.ok(v.indexOf(NOTE_FIRST) < v.indexOf("Always speak Ukrainian."));
  assert.ok(!voiceFor("uk", false).includes("save_call_note"));
  assert.ok(!/company/i.test(v.replace(/Never ask for a company/g, "")), "never asks for a company");
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER };
  await env.BALANCES.put(OWNER, "1");
  openai();
  await handleIncoming(env, "https://w", "rtc_t", ZADARMA);
  const { callToken } = await import("./solarchik-screen.js");
  const list = await call(env, "/mcp", { jsonrpc: "2.0", id: 1, method: "tools/list" }, "POST", { "x-solarchik-call": await callToken(env, "rtc_t") });
  assert.equal(list.json.result.tools[0].inputSchema.properties.company, undefined);
  // callback defaults to the caller's number
  const saved = await call(env, "/mcp", { jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "save_call_note", arguments: { caller_name: "Оля", intent: "Передзвонити щодо замовлення" } } }, "POST", { "x-solarchik-call": await callToken(env, "rtc_t") });
  assert.equal(saved.json.result.content[0].text, "Saved. Say goodbye.");
  const line = JSON.parse(await env.BALANCES.get("inbox:" + OWNER))[0];
  assert.equal(line.summary.callback, "+380638500117");
  assert.equal(line.summary.company, undefined);
  assert.equal(line.source, "tool");
});

test("transcriptLine reads caller and secretary words from realtime events", () => {
  assert.deepEqual(transcriptLine({ type: "conversation.item.input_audio_transcription.completed", transcript: "Це Оля" }), { who: "caller", text: "Це Оля" });
  assert.deepEqual(transcriptLine({ type: "response.output_audio_transcript.done", transcript: "Привіт" }), { who: "secretary", text: "Привіт" });
  assert.equal(transcriptLine({ type: "response.done" }), null);
});

test("finishNote: keeps a tool note, writes 'no details' without caller words, else a summary from the transcript", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk" };
  await env.BALANCES.put("inbox:" + OWNER, JSON.stringify([{ callId: "c1", status: "done", text: "tool note" }, { callId: "c2", status: "pending" }, { callId: "c3", status: "pending" }]));
  assert.equal(await finishNote(env, "c1", OWNER, "+380638500117", [{ who: "caller", text: "hi" }]), "kept");
  assert.equal(await finishNote(env, "c2", OWNER, "+380638500117", [{ who: "secretary", text: "Привіт!" }]), "empty");
  let inbox = JSON.parse(await env.BALANCES.get("inbox:" + OWNER));
  assert.equal(inbox.find((x) => x.callId === "c2").text, AUTO_NOTE_EMPTY);
  globalThis.fetch = async (url, init) => {
    assert.equal(String(url), "https://api.openai.com/v1/chat/completions");
    assert.match(JSON.parse(init.body).messages[1].content, /Caller: Це Оля, хочу перенести зустріч/);
    return Response.json({ choices: [{ message: { content: JSON.stringify({ caller_name: "Оля", intent: "Хоче перенести зустріч на завтра", urgency: "medium", spam_risk: "low", action: "callback" }) } }] });
  };
  assert.equal(await finishNote(env, "c3", OWNER, "+380638500117", [{ who: "secretary", text: "Привіт" }, { who: "caller", text: "Це Оля, хочу перенести зустріч" }]), "summary");
  inbox = JSON.parse(await env.BALANCES.get("inbox:" + OWNER));
  const c3 = inbox.find((x) => x.callId === "c3");
  assert.equal(c3.status, "done");
  assert.equal(c3.source, "auto");
  assert.match(c3.text, /^Оля: Хоче перенести зустріч на завтра Callback \+380638500117\./);
  assert.equal(inbox.find((x) => x.callId === "c1").text, "tool note", "untouched");
});

// ---------------- Sol ----------------

const CTX = {
  agents: [
    { id: "7xAbc", name: "aloxa #11", running: true, strategyNft: true, risk: "calm", windows: [60] },
    { id: "paper:sku-pred-alpha", name: "Біткоїн-вікна #11", running: false, owned: false },
  ],
  market: [{ id: "m1", name: "Calm Hourly BTC", priceSol: 0.05 }],
  canMintFree: true,
};

test("solAction: ids must exist, risk/windows legal; never another agent", () => {
  const ctx = { agents: CTX.agents.map((a) => ({ ...a, owned: a.owned !== false, windows: a.windows || [] })), market: CTX.market, canMintFree: true };
  assert.deepEqual(solAction({ type: "stop_agent", agent: "7xAbc" }, ctx), { type: "stop_agent", agent: "7xAbc" });
  assert.equal(solAction({ type: "stop_agent", agent: "nope" }, ctx), null);
  assert.deepEqual(solAction({ type: "set_strategy", agent: "7xAbc", risk: "risky", windows: [5, 7, 5], listing: "" }, ctx), { type: "set_strategy", agent: "7xAbc", risk: "risky", windows: [5] });
  assert.equal(solAction({ type: "set_strategy", agent: "7xAbc", risk: "", windows: [] }, ctx), null);
  assert.deepEqual(solAction({ type: "buy_strategy", listing: "m1" }, ctx), { type: "buy_strategy", listing: "m1" });
  assert.equal(solAction({ type: "launch_rocket" }, ctx), null);
});

test("Sol system prompt: can act, never 'only watch', UK grammar rules, context ids", () => {
  const ctx = { agents: [{ id: "7xAbc", name: "aloxa #11", running: true, owned: true, strategyNft: true, risk: "calm", windows: [60] }], market: [], canMintFree: false };
  const s = solSystem("uk", "yard", ctx, "");
  assert.match(s, /You CAN act on the player's agents/);
  assert.match(s, /never say you can only watch/);
  assert.match(s, /грамотною живою українською/);
  assert.match(s, /жарт/);
  assert.match(solCtxLines(ctx), /agent 7xAbc = "aloxa #11" running strategyNft risk=calm windows=60/);
});

function sse(chunks) {
  const enc = new TextEncoder();
  return new ReadableStream({
    start(c) {
      for (const ch of chunks) c.enqueue(enc.encode("data: " + JSON.stringify(ch) + "\n\n"));
      c.enqueue(enc.encode("data: [DONE]\n\n"));
      c.close();
    },
  });
}

test("POST /sol/chat: a stop request comes back as a validated action (+ reply), streamed as NDJSON", async () => {
  let sent = null;
  globalThis.fetch = async (url, init) => {
    sent = JSON.parse(init.body);
    return new Response(
      sse([
        { choices: [{ delta: { content: "Зупиняю aloxa #11 — " } }] },
        { choices: [{ delta: { content: "підтверди на картці." } }] },
        { choices: [{ delta: { tool_calls: [{ index: 0, function: { name: "propose_action", arguments: '{"type":"stop_agent","agent":"a1",' } }] } }] },
        { choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: '"listing":"","risk":"","windows":[]}' } }] } }] },
      ]),
      { status: 200 },
    );
  };
  const r = await call({ BALANCES: kv(), OPENAI_API_KEY: "sk" }, "/sol/chat", { message: "вимкни агента aloxa #11", language: "uk", stream: true, ...CTX });
  assert.equal(sent.model, "gpt-4.1-mini");
  assert.equal(sent.stream, true);
  assert.equal(sent.tools[0].function.name, "propose_action");
  assert.match(sent.messages[0].content, /agent a1 = "aloxa #11"/);
  assert.match(sent.messages[0].content, /agent a2 = "Біткоїн-вікна #11" stopped owned=false/);
  const lines = r.text.trim().split("\n").map((l) => JSON.parse(l));
  assert.deepEqual(lines.filter((l) => l.d).map((l) => l.d), ["Зупиняю aloxa #11 — ", "підтверди на картці."]);
  const done = lines.at(-1);
  assert.equal(done.done, true);
  assert.deepEqual(done.action, { type: "stop_agent", agent: "7xAbc" });
  assert.equal(done.reply, "Зупиняю aloxa #11 — підтверди на картці.");
  assert.equal(done.provider, "openai");
});

test("POST /sol/chat: plain chat (a joke) has no action; tool-only answer gets a short ready line; model fallback", async () => {
  let n = 0;
  globalThis.fetch = async (url, init) => {
    n++;
    const model = JSON.parse(init.body).model;
    if (model === "gpt-4.1-mini") return new Response("{}", { status: 429 });
    return new Response(sse([{ choices: [{ delta: { content: "Чому сонце не ходить до школи? Воно й так найсвітліше!" } }] }]), { status: 200 });
  };
  const r = await call({ BALANCES: kv(), OPENAI_API_KEY: "sk" }, "/sol/chat", { message: "розкажи жарт", language: "uk" });
  assert.equal(r.json.action, null);
  assert.equal(r.json.model, "gpt-4o-mini");
  assert.equal(n, 2);
  globalThis.fetch = async () =>
    new Response(sse([{ choices: [{ delta: { tool_calls: [{ function: { arguments: '{"type":"buy_strategy","agent":"","listing":"l1","risk":"","windows":[]}' } }] } }] }]), { status: 200 });
  const b = await call({ BALANCES: kv(), OPENAI_API_KEY: "sk" }, "/sol/chat", { message: "buy the Calm Hourly BTC", language: "en", ...CTX });
  assert.deepEqual(b.json.action, { type: "buy_strategy", listing: "m1" });
  assert.match(b.json.reply, /card/);
});

test("GET /sol/tts streams OpenAI gpt-4o-mini-tts audio with the engine header; bad input 400", async () => {
  let body = null;
  globalThis.fetch = async (url, init) => {
    assert.equal(String(url), "https://api.openai.com/v1/audio/speech");
    body = JSON.parse(init.body);
    return new Response(new Uint8Array(4800), { status: 200 });
  };
  const r = await worker.fetch(new Request("https://w/sol/tts?text=" + encodeURIComponent("Привіт, друже!") + "&lang=uk"), { OPENAI_API_KEY: "sk" }, { waitUntil() {} });
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("x-sol-tts"), "openai:gpt-4o-mini-tts:" + SOL_DEFAULT_VOICE);
  assert.match(r.headers.get("content-type"), /audio\/L16;rate=24000/);
  assert.equal((await r.arrayBuffer()).byteLength, 4800);
  assert.equal(body.voice, "marin");
  assert.equal(body.response_format, "pcm");
  assert.match(body.instructions, /українською/);
  assert.equal((await worker.fetch(new Request("https://w/sol/tts"), { OPENAI_API_KEY: "sk" })).status, 400);
});

test("readChatStream handles split SSE lines; rate limit trips after 40/min per IP", async () => {
  const enc = new TextEncoder();
  const s = new ReadableStream({
    start(c) {
      c.enqueue(enc.encode('data: {"choices":[{"delta":{"content":"Hel'));
      c.enqueue(enc.encode('lo"}}]}\n\ndata: [DONE]\n\n'));
      c.close();
    },
  });
  const got = [];
  const r = await readChatStream(s, (d) => got.push(d));
  assert.equal(r.text, "Hello");
  assert.deepEqual(got, ["Hello"]);
  const now = Date.now();
  for (let i = 0; i < 40; i++) assert.equal(solRateOk("1.2.3.4", now), true);
  assert.equal(solRateOk("1.2.3.4", now), false);
  assert.equal(solRateOk("1.2.3.4", now + 61_000), true);
});

test("solAction maps short refs (a2, l1) back to the app's ids; a cut id like 'paper' matches nothing", () => {
  const ctx = {
    agents: [{ id: "7xAbc", name: "aloxa #11", ref: "a1" }, { id: "paper:sku-pred-alpha", name: "Біткоїн-вікна #11", ref: "a2" }],
    market: [{ id: "Mkt111", name: "Calm Hourly BTC", ref: "l1" }],
  };
  assert.deepEqual(solAction({ type: "start_agent", agent: "a2" }, ctx), { type: "start_agent", agent: "paper:sku-pred-alpha" });
  assert.deepEqual(solAction({ type: "buy_strategy", listing: "l1" }, ctx), { type: "buy_strategy", listing: "Mkt111" });
  assert.deepEqual(solAction({ type: "stop_agent", agent: "aloxa #11" }, ctx), { type: "stop_agent", agent: "7xAbc" });
  assert.equal(solAction({ type: "start_agent", agent: "paper" }, ctx), null);
});

// ---------------- 0.21.9: one call = one line, block list, transcript, demo-line claim ----------------

/** A CALLS binding backed by real CallRoom objects (one per name), like production. */
function roomsEnv(env) {
  const rooms = new Map();
  env.CALLS = {
    idFromName: (n) => n,
    get: (n) => {
      if (!rooms.has(n)) {
        const r = new CallRoom(roomState(), env);
        r.watch = async () => {};
        rooms.set(n, r);
      }
      const r = rooms.get(n);
      return { fetch: (u, init) => r.fetch(new Request(u, init)) };
    },
  };
  return rooms;
}

const LIVE = "live_u0_EUfjtp3oN92tzumvMe0LZ6AuayTaC80A";
const RTC = "rtc_u0_EUfjtp3oN92tzumvMe0LZ6AuayTaC80A";

test("canonCallId strips rtc_/live_; cleanInbox drops the failed live_ twin of an answered call (real 3 Oct data)", () => {
  assert.equal(canonCallId(LIVE), canonCallId(RTC));
  assert.equal(canonCallId("abc"), "abc");
  const items = [
    { callId: RTC, status: "done", text: "Вадим: Передати привіт", at: 2 },
    { callId: LIVE, status: "failed", text: "Missed call: the secretary could not pick up (refunded).", at: 1 },
    { callId: "live_u1_other", status: "failed", text: "Missed call", at: 0 },
    { caller: "+1", text: "old voicemail", at: 0 },
  ];
  const out = cleanInbox(items);
  assert.deepEqual(out.map((x) => x.callId || x.text), [RTC, "live_u1_other", "old voicemail"]);
});

test("/sip: live_ (session only) and rtc_ deliveries of one call meet in one room; only rtc_ is accepted, one charge, one line", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER, SESSION_WAIT_MS: 60 };
  await env.BALANCES.put(OWNER, "1");
  roomsEnv(env);
  const calls = openai();
  const [a, b] = await Promise.all([
    call(env, "/sip", JSON.stringify({ type: "live.transport.incoming", data: { session_id: LIVE, sip_headers: ZADARMA } })),
    call(env, "/sip", JSON.stringify({ type: "realtime.call.incoming", data: { call_id: RTC, sip_headers: ZADARMA } })),
  ]);
  assert.equal(a.json.duplicate, true);
  assert.equal(b.json.accepted, true);
  const accepts = calls.filter((c) => c.url.endsWith("/accept"));
  assert.equal(accepts.length, 1);
  assert.ok(accepts[0].url.includes(encodeURIComponent(RTC)));
  assert.equal(await env.BALANCES.get(OWNER), "0.8");
  const inbox = (await call(env, "/inbox?userId=" + OWNER, undefined, "GET")).json.items;
  assert.equal(inbox.length, 1);
  assert.equal(inbox[0].callId, RTC);
});

test("/sip: a session-only delivery with no twin is still answered after the short wait", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER, SESSION_WAIT_MS: 20 };
  await env.BALANCES.put(OWNER, "1");
  roomsEnv(env);
  openai();
  const r = await call(env, "/sip", JSON.stringify({ type: "live.call.incoming", data: { session_id: "live_solo", sip_headers: ZADARMA } }));
  assert.equal(r.json.accepted, true);
});

test("block list: POST/GET /block; a blocked caller is rejected, never charged, and gets a 'blocked' line", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER };
  await env.BALANCES.put(OWNER, "1");
  assert.equal((await call(env, "/block", { userId: OWNER, number: "nope" })).status, 400);
  const set = await call(env, "/block", { userId: OWNER, number: "+380 63 850 0117" });
  assert.deepEqual(set.json.numbers, ["+380638500117"]);
  assert.deepEqual((await call(env, "/block?userId=" + OWNER, undefined, "GET")).json.numbers, ["+380638500117"]);
  assert.equal(await isBlocked(env, OWNER, "+380638500117"), true);
  const calls = openai();
  const r = await handleIncoming(env, "https://w", "rtc_blocked", ZADARMA);
  assert.equal(r.body.blocked, true);
  assert.equal(calls.filter((c) => c.url.endsWith("/accept")).length, 0);
  assert.equal(calls.filter((c) => c.url.endsWith("/reject")).length, 1);
  assert.equal(await env.BALANCES.get(OWNER), "1", "not charged");
  assert.equal(JSON.parse(await env.BALANCES.get("inbox:" + OWNER))[0].status, "blocked");
  // unblock
  assert.deepEqual((await call(env, "/block", { userId: OWNER, number: "+380638500117", blocked: false })).json.numbers, []);
  assert.equal(await isBlocked(env, OWNER, "+380638500117"), false);
});

test("POST /call-claim: the next call to the demo line goes to the claiming player; the caller is remembered after", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER };
  await env.BALANCES.put(USER, "1");
  roomsEnv(env);
  assert.equal((await call(env, "/call-claim", { userId: "x" })).status, 400);
  const c = await call(env, "/call-claim", { userId: USER });
  assert.equal(c.json.armedSec, 180);
  assert.equal(c.json.number, "+380914810885");
  openai();
  const r = await handleIncoming(env, "https://w", "rtc_claimed", ZADARMA);
  assert.equal(r.body.accepted, true);
  assert.equal(JSON.parse(await env.BALANCES.get("inbox:" + USER))[0].callId, "rtc_claimed");
  assert.equal(await env.BALANCES.get("inbox:" + OWNER), null);
  // the claim is used up, but this caller's later calls still reach the same player
  const r2 = await handleIncoming(env, "https://w", "rtc_again", ZADARMA);
  assert.equal(r2.body.accepted, true);
  assert.equal(JSON.parse(await env.BALANCES.get("inbox:" + USER))[0].callId, "rtc_again");
  // another caller without a claim goes to the owner/demo account as before
  const other = ZADARMA.map((h) => (h.name === "From" ? { ...h, value: "<sip:+380501112233@pbx.zadarma.com>" } : h));
  await handleIncoming(env, "https://w", "rtc_other", other);
  assert.equal(JSON.parse(await env.BALANCES.get("inbox:" + OWNER))[0].callId, "rtc_other");
});

test("an expired claim is ignored", async () => {
  const st = roomState();
  const room = new CallRoom(st, {});
  await room.fetch(new Request("https://call-room/claim", { method: "POST", body: JSON.stringify({ userId: USER }) }));
  st.m.set("claim", { userId: USER, until: Date.now() - 1 });
  const j = await (await room.fetch(new Request("https://call-room/claim-take", { method: "POST", body: JSON.stringify({ caller: "+380638500117" }) }))).json();
  assert.equal(j.userId, "");
});

test("call room finish stores the transcript and duration; GET /call returns the line with its words (KV or old room)", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk" };
  await env.BALANCES.put("inbox:" + OWNER, JSON.stringify([{ callId: "rtc_f", status: "done", text: "tool note", at: 1 }, { callId: RTC, status: "done", text: "old", at: 0 }]));
  const st = roomState();
  st.m.set("startedAt", Date.now() - 41_000);
  const room = new CallRoom(st, env);
  room.lines = [{ who: "secretary", text: "Привіт" }, { who: "caller", text: "Це Вадим" }];
  await room.finish("rtc_f", OWNER, "+380638500117", "closed");
  const line = JSON.parse(await env.BALANCES.get("inbox:" + OWNER))[0];
  assert.ok(line.durationSec >= 40 && line.durationSec <= 43);
  assert.equal(line.transcriptLines, 2);
  const r = await call(env, "/call?userId=" + OWNER + "&callId=rtc_f", undefined, "GET");
  assert.equal(r.json.lines.length, 2);
  assert.equal(r.json.lines[1].who, "caller");
  assert.equal(r.json.item.text, "tool note");
  // an older call: words only in its call room (named by the full id)
  const rooms = roomsEnv(env);
  env.CALLS.get(RTC);
  rooms.get(RTC).state.storage.put("lines", [{ who: "caller", text: "Передати привіт" }]);
  const old = await call(env, "/call?userId=" + OWNER + "&callId=" + RTC, undefined, "GET");
  assert.deepEqual(old.json.lines, [{ who: "caller", text: "Передати привіт" }]);
  assert.ok(await env.BALANCES.get("transcript:" + RTC), "cached in KV after the first read");
  assert.equal((await call(env, "/call?userId=" + OWNER + "&callId=nope", undefined, "GET")).status, 404);
});

test("the tool re-creating a lost line keeps the call's start time", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER };
  await env.BALANCES.put(OWNER, "1");
  openai();
  await handleIncoming(env, "https://w", "rtc_lost", ZADARMA);
  const at = JSON.parse(await env.BALANCES.get("inbox:" + OWNER))[0].at;
  await env.BALANCES.put("inbox:" + OWNER, "[]"); // a concurrent write dropped it
  const { callToken } = await import("./solarchik-screen.js");
  await new Promise((r) => setTimeout(r, 15));
  await call(env, "/mcp", { jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "save_call_note", arguments: { intent: "Привіт" } } }, "POST", { "x-solarchik-call": await callToken(env, "rtc_lost") });
  assert.equal(JSON.parse(await env.BALANCES.get("inbox:" + OWNER))[0].at, at);
});

test("speakable: #11 is read as 'номер 11', no symbols read aloud; Sol prompt fixes ti/tviy, agent gender and CLOCK IN wording", () => {
  assert.equal(speakable("Bitcoin-вікна #11 і Метеостанція #3 працюють.", "uk"), "Bitcoin вікна номер 11 і Метеостанція номер 3 працюють.");
  assert.equal(speakable("**Calm** BTC #2 → ready", "en"), "Calm BTC number 2 ready");
  const uk = solSystem("uk", "yard", { agents: [], market: [], canMintFree: false }, "");
  assert.match(uk, /Ніколи не кажи «підписати гаманець»/);
  assert.match(uk, /Агент „Метеостанція“ ще не твій/);
  assert.match(uk, /не «твій»/);
  assert.match(solSystem("en", "yard", { agents: [], market: [], canMintFree: false }, ""), /Never say "sign your wallet"/);
});

test("phone secretary speaks to callers with one consistent polite form in Ukrainian (live call mixed тебе/вас)", () => {
  const v = voiceFor("uk", true);
  assert.match(v, /polite «ви» every time/);
  assert.match(v, /Never switch to «ти»/);
});

// ---- 0.22.0: owner exemption + trial counters that no longer leak on failed / empty calls ----

const ADMIN_PHONE_ID = "06e76c23-f2f9-447c-8a9c-b70d14b1eadd";
const ADMIN_ACCOUNT = "d61556d7-b92a-4a54-aa84-95897565439d";
const today = () => new Date().toISOString().slice(0, 10);

async function exhaust(env, caller = "+380638500117") {
  for (let i = 0; i < 3; i++) await takeTrialSlot(env, caller);
  await env.BALANCES.put("trial_day:" + today(), "30");
}

test("owner ids are admins; env ADMIN_USER_IDS adds more; strangers are not", () => {
  assert.ok(ADMIN_USER_IDS.includes(ADMIN_ACCOUNT) && ADMIN_USER_IDS.includes(ADMIN_PHONE_ID));
  assert.equal(isAdmin({}, ADMIN_PHONE_ID), true);
  assert.equal(isAdmin({}, USER), false);
  assert.equal(isAdmin({ ADMIN_USER_IDS: "x1, " + USER }, USER), true);
  assert.equal(isAdmin({ OWNER_USER_ID: USER }, USER), false, "the demo line's fallback account is not exempt by itself");
});

test("live 3 Oct: the owner's phone id is answered after the caller cap, the daily cap and the trial are all used up", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: ADMIN_ACCOUNT };
  roomsEnv(env);
  await env.BALANCES.put("trial_granted:" + ADMIN_PHONE_ID, "1");
  await env.BALANCES.put("trial:" + ADMIN_PHONE_ID, "0");
  await exhaust(env);
  await call(env, "/call-claim", { userId: ADMIN_PHONE_ID });
  openai();
  const r = await handleIncoming(env, "https://w", "rtc_owner1", ZADARMA);
  assert.equal(r.body.accepted, true);
  assert.equal(r.body.source, "owner");
  assert.equal(r.body.trial, false);
  const line = JSON.parse(await env.BALANCES.get("inbox:" + ADMIN_PHONE_ID))[0];
  assert.equal(line.chargedUsd, 0);
  assert.notEqual(line.status, "need_topup");
  assert.equal(await env.BALANCES.get("trial_caller:" + today() + ":82287c3d48dc6c6d"), "3", "no shared slot used");
  // remembered caller, no claim: still the owner's phone id, still answered
  const r2 = await handleIncoming(env, "https://w", "rtc_owner2", ZADARMA);
  assert.equal(r2.body.accepted, true);
  assert.equal(r2.body.source, "owner");
  const bal = await call(env, "/balance?userId=" + ADMIN_PHONE_ID, undefined, "GET");
  assert.equal(bal.json.owner, true);
  assert.equal((await call(env, "/balance?userId=" + USER, undefined, "GET")).json.owner, false);
});

test("a stranger reaching the owner account only as the demo line fallback keeps the caps (no free unlimited line)", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: ADMIN_ACCOUNT };
  await env.BALANCES.put("trial_granted:" + ADMIN_ACCOUNT, "1");
  await env.BALANCES.put("trial:" + ADMIN_ACCOUNT, "0");
  await exhaust(env);
  openai();
  const r = await handleIncoming(env, "https://w", "rtc_stranger", ZADARMA);
  assert.equal(r.body.accepted, false);
  assert.ok(["TRIAL_CALLER_CAP", "TRIAL_DAILY_CAP"].includes(r.body.reason));
  // the owner's own app session (/screen path, no caller) is never blocked
  const c = await chargeSession(env, ADMIN_ACCOUNT, null);
  assert.equal(c.ok, true);
  assert.equal(c.source, "owner");
});

test("live 2 Oct miscount: failed accepts give the trial slot back, so 2 failures + 1 call use 1 slot, not 3", async () => {
  const env = { BALANCES: kv(), OPENAI_API_KEY: "sk", OWNER_USER_ID: OWNER };
  // each failed call tries accept twice (with the note tool, then without it)
  openai({ acceptStatus: (n) => (n <= 4 ? 500 : 200) });
  await handleIncoming(env, "https://w", "rtc_f1", ZADARMA);
  await handleIncoming(env, "https://w", "rtc_f2", ZADARMA);
  const ok = await handleIncoming(env, "https://w", "rtc_f3", ZADARMA);
  assert.equal(ok.body.accepted, true);
  assert.equal(ok.body.source, "trial");
  assert.equal(await env.BALANCES.get("trial:" + OWNER), "0.4", "exactly one session of the 3 used");
  assert.equal(await env.BALANCES.get("trial_caller:" + today() + ":82287c3d48dc6c6d"), "1");
  assert.equal(await env.BALANCES.get("trial_day:" + today()), "1");
  // the judge trial still runs out after 3 real calls (then the demo budget's caller cap applies)
  await handleIncoming(env, "https://w", "rtc_f4", ZADARMA);
  await handleIncoming(env, "https://w", "rtc_f5", ZADARMA);
  assert.equal(await env.BALANCES.get("trial:" + OWNER), "0");
  const over = await handleIncoming(env, "https://w", "rtc_f6", ZADARMA);
  assert.equal(over.body.accepted, false);
  assert.equal(over.body.reason, "TRIAL_CALLER_CAP");
});

test("an answered call that ends within 15 s with no word from the caller is not billed; a real one is", async () => {
  const env = { BALANCES: kv() };
  const caller = "+380501112233";
  await takeTrialSlot(env, caller);
  await env.BALANCES.put("trial:" + USER, "0.4");
  await env.BALANCES.put("inbox:" + USER, JSON.stringify([{ callId: "rtc_s", status: "done", chargedUsd: 0.2 }]));
  const base = { userId: USER, callId: "rtc_s", caller, source: "trial", chargedAt: Date.now() };
  assert.equal(await refundShortCall(env, { ...base, durationSec: 40, heard: false }), false);
  assert.equal(await refundShortCall(env, { ...base, durationSec: 5, heard: true }), false);
  assert.equal(await refundShortCall(env, { ...base, durationSec: SHORT_CALL_SEC, heard: false }), true);
  assert.equal(await env.BALANCES.get("trial:" + USER), "0.6");
  assert.equal(await env.BALANCES.get("trial_day:" + today()), "0");
  const line = JSON.parse(await env.BALANCES.get("inbox:" + USER))[0];
  assert.equal(line.chargedUsd, 0);
  assert.equal(line.refunded, true);
  await env.BALANCES.put(USER, "1");
  assert.equal(await refundShortCall(env, { ...base, source: "paid", durationSec: 3, heard: false }), true);
  assert.equal(await env.BALANCES.get(USER), "1.2");
  assert.equal(await refundShortCall(env, { ...base, source: "owner", durationSec: 3, heard: false }), false);
  await releaseTrialSlot(env, caller); // never below zero
  assert.equal(await env.BALANCES.get("trial_day:" + today()), "0");
});

test("0.22.0 stale streak: the fresh player state comes right after the persona and says not to ask for 1200 m once signed", () => {
  const st = solState({ streak: 1, signedToday: true, clockedToday: true, todayMeters: 1340 });
  assert.deepEqual(st, { streak: 1, signedToday: true, clockedToday: true, todayMeters: 1340 });
  assert.equal(solState({ streak: "x" }), null);
  assert.equal(solState(null), null);
  const sys = solSystem("uk", "yard", { agents: [], market: [], canMintFree: false }, "Серія: 1 день. Сьогодні підписано.", st);
  const iState = sys.indexOf("PLAYER STATE NOW");
  assert.ok(iState > 0 && iState < sys.indexOf("Game facts"), "state before the facts");
  assert.match(sys, /streak 1 day; today's CLOCK IN is already signed\. Do not ask the player to run 1200 m/);
  assert.match(sys, /Нагадуй лише тоді, коли сьогодні ще не підписано/);
  // the app already sends the line in its context: not repeated
  const line = solStateLine(st);
  const sys2 = solSystem("en", "yard", { agents: [], market: [], canMintFree: false }, line + "\nnote", st);
  assert.equal(sys2.split("PLAYER STATE NOW (fresh").length - 1, 1);
  assert.match(solStateLine({ streak: 0, signedToday: false, clockedToday: false, todayMeters: 300 }), /not signed yet; today's best run is 300 m of 1200 m/);
  assert.match(sys2, /never "windows 15\/60"/);
});

test("0.22.3 run scene: game first; agent rules/context only when the player names agents", async () => {
  const { solSystem: sys, RUN_AGENT_WORDS } = await import("./solarchik-screen.js");
  const ctx = { agents: [{ id: "paper:x", name: "SOL-скальпер", running: false, owned: true, windows: [] }], market: [], canMintFree: false };
  const plain = sys("uk", "run", ctx, "Забіг: 412 м", null, false);
  assert.match(plain, /ЗАРАЗ ТИ В ГРІ/);
  assert.ok(!plain.includes("SOL-скальпер"), "no agent list in a game-only question");
  assert.ok(sys("uk", "run", ctx, "", null, true).includes("SOL-скальпер"));
  assert.ok(!RUN_AGENT_WORDS.test("Що тут робити?") && !RUN_AGENT_WORDS.test("Як побити рекорд?"));
  assert.ok(RUN_AGENT_WORDS.test("запусти мого агента") && RUN_AGENT_WORDS.test("change my strategy"));
});

// ---------------------------------------------------------------- 1.0.1 assistant persona (app: "assistant")

test("assistant flag: assistant prompt for chat, game prompt for runs and for requests without the flag", () => {
  assert.equal(solIsAssistant("assistant", "yard"), true);
  assert.equal(solIsAssistant("Assistant ", "yard"), true);
  assert.equal(solIsAssistant("assistant", "run"), false);
  assert.equal(solIsAssistant(undefined, "yard"), false);
  assert.equal(solIsAssistant("game", "yard"), false);
  const ctx = { agents: [], market: [], canMintFree: false };
  const en = solAssistantSystem("en", ctx, "Calls today: Olena at 10:05 wants to move the meeting.");
  assert.match(en, /pocket assistant/);
  assert.match(en, /do not talk about the rooftop game/);
  assert.match(en, /ASSISTANT CONTEXT \(fresh from the phone\): Calls today: Olena/);
  assert.match(en, /cannot set alarms/);
  assert.doesNotMatch(en, /Game facts/);
  assert.doesNotMatch(en, /You CAN act on the player's agents/);
  const uk = solAssistantSystem("uk", ctx, "", true);
  assert.match(uk, /кишеньковий помічник/);
  assert.match(uk, /ASSISTANT CONTEXT: none sent/);
  assert.match(uk, /You CAN act on the player's agents/);
});

test("POST /sol/chat with app=assistant: assistant system, no agent tools for a general question, persona in the reply", async () => {
  let sent = null;
  globalThis.fetch = async (url, init) => {
    sent = JSON.parse(init.body);
    return new Response(sse([{ choices: [{ delta: { content: "I can't set reminders from chat. Use the Clock app: call Mom at 7 pm." } }] }]), { status: 200 });
  };
  const r = await call({ BALANCES: kv(), OPENAI_API_KEY: "sk" }, "/sol/chat", { message: "remind me to call mom at 7", language: "en", app: "assistant", context: "No calls today." });
  assert.match(sent.messages[0].content, /pocket assistant/);
  assert.match(sent.messages[0].content, /No calls today\./);
  assert.equal(sent.tools, undefined);
  assert.equal(sent.max_tokens, 220);
  assert.equal(r.json.persona, "assistant");
  assert.equal(r.json.action, null);
  // naming the wallet / agents brings the confirm-card tool back
  await call({ BALANCES: kv(), OPENAI_API_KEY: "sk" }, "/sol/chat", { message: "stop my agent aloxa #11", language: "en", app: "assistant", ...CTX });
  assert.equal(sent.tools[0].function.name, "propose_action");
  assert.match(sent.messages[0].content, /agent a1 = "aloxa #11"/);
});

test("POST /sol/chat without the flag: the game request is unchanged (game prompt, tools, 170 tokens, no persona)", async () => {
  let sent = null;
  globalThis.fetch = async (url, init) => {
    sent = JSON.parse(init.body);
    return new Response(sse([{ choices: [{ delta: { content: "Hi!" } }] }]), { status: 200 });
  };
  const r = await call({ BALANCES: kv(), OPENAI_API_KEY: "sk" }, "/sol/chat", { message: "what can you do?", language: "en" });
  assert.equal(sent.messages[0].content, solSystem("en", "yard", { agents: [], market: [], canMintFree: false }, "", null, true));
  assert.match(sent.messages[0].content, /companion in the game Solarchik/);
  assert.equal(sent.tools[0].function.name, "propose_action");
  assert.equal(sent.max_tokens, 170);
  assert.equal(r.json.persona, undefined);
  // a run from the assistant app still gets the game run prompt
  await call({ BALANCES: kv(), OPENAI_API_KEY: "sk" }, "/sol/chat", { message: "how far?", language: "en", scene: "run", app: "assistant" });
  assert.match(sent.messages[0].content, /YOU ARE IN THE GAME NOW/);
  assert.equal(sent.max_tokens, 100);
});
