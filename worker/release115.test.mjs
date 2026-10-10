// node --test worker/release115.test.mjs : 1.1.5 transcript order, secretary script, money requests without an amount.
import { test } from "node:test";
import assert from "node:assert/strict";
import { transcriptLine, itemAdded, addLine, orderLines, transcriptionFor, voiceFor } from "./solarchik-screen.js";
import { sanitizeActions, actionCalls, actionsSystem, callIdOf, withTranscripts } from "./assistant-extras.js";

test("transcript: the caller's late transcription sorts before the secretary's reply (live call 10 Oct)", () => {
  const order = {};
  let seq = 0;
  const lines = [];
  const events = [
    { type: "conversation.item.added", item: { id: "a1", role: "assistant" } },
    { type: "response.output_audio_transcript.done", item_id: "a1", transcript: "Вітаю! Як вас звати і що ви хотіли передати?" },
    { type: "conversation.item.added", item: { id: "u1", role: "user" } },
    { type: "response.output_item.added", item: { id: "a2" } },
    { type: "conversation.item.added", item: { id: "a2", role: "assistant" } },
    { type: "response.output_audio_transcript.done", item_id: "a2", transcript: "Приємно познайомитись, Вадим." },
    { type: "conversation.item.input_audio_transcription.completed", item_id: "u1", transcript: "Мене звати Вадим." },
    // a doubled event for one item never doubles the line
    { type: "response.output_audio_transcript.done", item_id: "a1", transcript: "Вітаю! Як вас звати і що ви хотіли передати?" },
  ];
  for (const ev of events) {
    const id = itemAdded(ev);
    if (id && !(id in order)) order[id] = seq++;
    const l = transcriptLine(ev);
    if (l) addLine(lines, l);
  }
  assert.deepEqual(orderLines(lines, order), [
    { who: "secretary", text: "Вітаю! Як вас звати і що ви хотіли передати?" },
    { who: "caller", text: "Мене звати Вадим." },
    { who: "secretary", text: "Приємно познайомитись, Вадим." },
  ]);
});

test("transcript: lines without item ids keep arrival order; only who/text leave the room", () => {
  assert.deepEqual(orderLines([{ who: "caller", text: "a" }, { who: "secretary", text: "b" }, { who: "caller", text: "" }]), [
    { who: "caller", text: "a" },
    { who: "secretary", text: "b" },
  ]);
});

test("caller transcription: fixed language when set, a bilingual hint for auto", () => {
  assert.deepEqual(transcriptionFor("uk"), { model: "gpt-4o-mini-transcribe", language: "uk" });
  assert.deepEqual(transcriptionFor("en"), { model: "gpt-4o-mini-transcribe", language: "en" });
  assert.equal(transcriptionFor("auto").language, undefined);
  assert.match(transcriptionFor("auto").prompt, /українською/);
});

test("secretary script: always takes the message, money requests are passed on with amount, purpose and callback time", () => {
  const v = voiceFor("auto", true);
  assert.match(v, /ALWAYS take the message/);
  assert.match(v, /how much, what it is for, and when to call back/);
  assert.match(v, /Never send, promise, confirm or refuse a payment/);
  assert.match(v, /no jokes, no filler/);
  assert.doesNotMatch(v, /cheeky/);
  assert.doesNotMatch(v, /If spam or scam, refuse/);
});

test("actions: a money request without an amount becomes a follow-up, never a payment with an invented sum", () => {
  const calls = actionCalls({ calls: [{ id: "u|rtc_abcdefgh12", who: "Вадим", callback: "+380638500117", intent: "Просить скинути грошей", at: "03:11", date: "2026-10-10" }] });
  const out = sanitizeActions({ actions: [
    { callId: "u|rtc_abcdefgh12", type: "payment", amount: 0, token: "", recipient: "Вадим", number: "", when: "", day: "", date: "", text: "Вадим просить надіслати гроші (суму не названо)", quote: "скинули грошиків" },
    { callId: "u|rtc_abcdefgh12", type: "callback", amount: 0, token: "", recipient: "", number: "", when: "", day: "", date: "", text: "Передзвонити Вадиму", quote: "" },
  ] }, calls);
  assert.equal(out.length, 2);
  assert.equal(out[0].type, "reminder");
  assert.equal(out[0].amount, 0);
  assert.equal(out[0].payment, "no_amount");
  assert.match(out[0].text, /суму не названо/);
  assert.equal(out[1].type, "callback");
  assert.equal(out[1].number, "+380638500117");
  assert.match(actionsSystem("uk"), /without an amount/);
  assert.match(actionsSystem("en"), /transcript/);
});

test("actions: the call's own transcript is read next to the note", async () => {
  assert.equal(callIdOf("06e7|rtc_u2_EXEyYp3oMZazoxS7vFuJWFEHPVJplPTa"), "rtc_u2_EXEyYp3oMZazoxS7vFuJWFEHPVJplPTa");
  assert.equal(callIdOf("06e7|vm:123"), "");
  const kv = { "transcript:rtc_abcdefgh12": JSON.stringify({ lines: [{ who: "caller", text: "Хочу, щоб мені скинули грошиків." }, { who: "secretary", text: "Передам." }] }) };
  const env = { BALANCES: { get: async (k) => kv[k] ?? null } };
  const calls = actionCalls({ calls: [{ id: "u|rtc_abcdefgh12", intent: "привіт" }, { id: "u|vm:1", intent: "x" }] });
  await withTranscripts(env, calls);
  assert.equal(calls[0].transcript, "Caller: Хочу, щоб мені скинули грошиків.\nSecretary: Передам.");
  assert.equal(calls[1].transcript, undefined);
});

test("actions: a weekday the call never mentioned is dropped (the model copied the call's own Saturday)", async () => {
  const { daySaid } = await import("./assistant-extras.js");
  assert.equal(daySaid("saturday", "Хотів передати привіт"), false);
  assert.equal(daySaid("monday", "нагадай про зустріч у понеділок"), true);
  assert.equal(daySaid("sunday", "у понеділок"), false);
  assert.equal(daySaid("tomorrow", "call me tomorrow"), true);
  assert.equal(daySaid("today", "at three"), true);
  const calls = actionCalls({ calls: [{ id: "u|c1", who: "V", callback: "+380638500117", intent: "Передзвонити", at: "03:11", date: "2026-10-10" }] });
  const out = sanitizeActions({ actions: [{ callId: "u|c1", type: "callback", amount: 0, token: "", recipient: "", number: "", when: "", day: "saturday", date: "", text: "Передзвонити", quote: "" }] }, calls);
  assert.equal(out[0].date, "");
});
