import test from "node:test";
import assert from "node:assert/strict";
import { needsLang, applyNoteLang, noteLangOf, translateSystem, localizeItems } from "./solarchik-screen.js";
import { noAmountText, briefingSystem } from "./assistant-extras.js";

test("1.1.7: which notes need translating for the UI language", () => {
  assert.equal(needsLang("Vadim, пожалуйста, отправь мне 0.01 SOL", "en"), true);
  assert.equal(needsLang("Ira asks you to call back at 3 PM.", "en"), false);
  assert.equal(needsLang("Vadim, пожалуйста, отправь мне 0.01 SOL", "uk"), true);
  assert.equal(needsLang("Іра просить передзвонити о 15:00.", "uk"), false);
  assert.equal(noteLangOf("en-US"), "en");
  assert.equal(noteLangOf("ru"), "");
  assert.match(translateSystem("uk"), /never Russian/);
  assert.match(translateSystem("en"), /they/);
});

test("1.1.7: a translated summary rebuilds the note text", () => {
  const it = { callId: "c1", text: "Іра: просить 0.01 SOL", summary: { caller_name: "Іра", intent: "просить 0.01 SOL", notes: "" } };
  const out = applyNoteLang(it, { intent: "asks you to send 0.01 SOL", notes: "" });
  assert.equal(out.summary.intent, "asks you to send 0.01 SOL");
  assert.match(out.text, /asks you to send 0\.01 SOL/);
});

test("1.1.7: cached translation applied without a model call", async () => {
  const kv = new Map([["note_lang3:c1:en", JSON.stringify({ text: "Vadim, please send me 0.01 SOL." })]]);
  const env = { BALANCES: { get: async (k) => kv.get(k) ?? null, put: async () => {} } };
  const items = await localizeItems(env, [{ callId: "c1", status: "done", text: "Vadim, пожалуйста, отправь мне 0.01 SOL." }], "en");
  assert.equal(items[0].text, "Vadim, please send me 0.01 SOL.");
  const same = await localizeItems(env, [{ callId: "c2", status: "done", text: "All English." }], "en");
  assert.equal(same[0].text, "All English.");
});

test("1.1.7: English app gets an English no-amount line even for a Cyrillic call", () => {
  assert.equal(noAmountText("Вадим просить гроші", "en"), "Вадим просить гроші (no amount said)");
  assert.match(noAmountText("", "en"), /no amount said/);
  assert.match(briefingSystem("en"), /translating call notes/);
});
