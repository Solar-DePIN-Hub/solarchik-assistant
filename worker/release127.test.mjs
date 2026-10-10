import test from "node:test";
import assert from "node:assert/strict";
import { fixThey, tidyItem, noteText, translateSystem } from "./solarchik-screen.js";

test("1.2.7: 'They says they paid' reads name-first", () => {
  assert.equal(fixThey("They says they paid for lunch and asks Vadym to send 0.01 SOL.", "Ira"), "Ira says they paid for lunch and asks Vadym to send 0.01 SOL.");
  assert.equal(fixThey("Ira: They says they paid.", "Ira"), "Ira says they paid.");
  assert.equal(fixThey("Ira says they paid and they wants 0.01 SOL", "Ira"), "Ira says they paid and they want 0.01 SOL");
  assert.equal(fixThey("They says they paid.", ""), "They say they paid.");
  assert.equal(fixThey("Ira: Please send 0.01 SOL.", "Ira"), "Ira: Please send 0.01 SOL.");
  assert.equal(fixThey("Іра каже, що заплатила.", "Іра"), "Іра каже, що заплатила.");
});

test("1.2.7: stored notes and new notes are fixed", () => {
  const it = tidyItem({ text: "Ira: They says they paid.", summary: { caller_name: "Ira", intent: "They says they paid for lunch." } });
  assert.equal(it.text, "Ira says they paid.");
  assert.equal(it.summary.intent, "Ira says they paid for lunch.");
  assert.equal(noteText({ caller_name: "Ira", intent: "They says they paid for lunch", callback: "+380501112233" }), "Ira says they paid for lunch. Callback +380501112233.");
  assert.match(translateSystem("en"), /never "they says"/);
});

import { sanitizeActions, actionsSystem, ACTION_TYPES, ACTIONS_SCHEMA } from "./assistant-extras.js";

test("1.2.7: 'owed' cards (the caller will pay the user) need a real amount and token, never an address", () => {
  assert.ok(ACTION_TYPES.includes("owed"));
  const calls = [{ id: "c1", at: "10:00", date: "2026-10-10", intent: "Andrii will send the 0.5 SOL deposit", notes: "", text: "", callback: "+380501112233" }];
  const raw = { actions: [
    { callId: "c1", type: "owed", amount: 0.5, token: "sol", recipient: "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin", number: "", when: "", day: "", date: "", text: "Andrii owes you 0.5 SOL for the deposit", quote: "I'll send you the 0.5 SOL deposit" },
    { callId: "c1", type: "owed", amount: 0, token: "SOL", recipient: "", number: "", when: "", day: "", date: "", text: "no amount", quote: "" },
    { callId: "c1", type: "owed", amount: 20, token: "dollars", recipient: "", number: "", when: "", day: "", date: "", text: "not a token we can request", quote: "" },
  ] };
  const a = sanitizeActions(raw, calls);
  assert.equal(a.length, 1);
  assert.deepEqual([a[0].type, a[0].amount, a[0].token, a[0].recipient, a[0].address], ["owed", 0.5, "SOL", "", ""]);
  assert.match(actionsSystem("en"), /Type owed/);
  assert.ok(ACTIONS_SCHEMA.schema.properties.actions.items.properties.type.enum.includes("owed"));
});
