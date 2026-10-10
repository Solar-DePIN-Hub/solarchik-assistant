import test from "node:test";
import assert from "node:assert/strict";
import { noteText, tidyNote, localizeItems, VOICE } from "./solarchik-screen.js";

test("a note that starts with a verb reads 'Ira says…'", () => {
  assert.equal(noteText({ caller_name: "Ira", intent: "says they paid for lunch yesterday, asks Vadim to send them 0.01 SOL", callback: "+380637443792" }),
    "Ira says they paid for lunch yesterday, asks Vadim to send them 0.01 SOL. Callback +380637443792.");
  assert.equal(noteText({ caller_name: "Ira", intent: "Please send 1 SOL" }), "Ira: Please send 1 SOL.");
  assert.equal(noteText({ intent: "wants a callback" }), "Wants a callback.");
});

test("stored notes are tidied on read, in every language", async () => {
  assert.equal(tidyNote("Ira: says they paid. Callback +380."), "Ira says they paid. Callback +380.");
  assert.equal(tidyNote("Ira: They paid."), "Ira: They paid.");
  const out = await localizeItems({}, [{ callId: "c", status: "done", text: "Ira: says they paid for lunch." }], "en");
  assert.equal(out[0].text, "Ira says they paid for lunch.");
});

test("the secretary asks SOL, SKR or USDC for a bare number, never dollars", () => {
  assert.match(VOICE, /SOL, SKR or USDC\?/);
  assert.match(VOICE, /never ask about dollars/);
});
