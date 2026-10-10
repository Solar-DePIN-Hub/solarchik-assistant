import test from "node:test";
import assert from "node:assert/strict";
import { recordSettled, settledFor, settledNote } from "./solarchik-screen.js";

function kv() {
  const m = new Map();
  return { get: async (k) => m.get(k) ?? null, put: async (k, v) => { m.set(k, v); }, m };
}
const sig = "5".repeat(88);
const ok = async () => ({ value: [{ confirmationStatus: "confirmed", err: null }] });

test("Circle: a confirmed payment is kept per caller and told to the secretary", async () => {
  const env = { BALANCES: kv() };
  const r = await recordSettled(env, { userId: "3fc4e9e2-87cf-4039-b61c-cdac0660a314", number: "+380637443792", amount: 0.01, token: "SOL", signature: sig }, ok);
  assert.equal(r.status, 200);
  const list = await settledFor(env, "3fc4e9e2-87cf-4039-b61c-cdac0660a314", "+380637443792");
  assert.equal(list.length, 1);
  const note = settledNote(list, "en");
  assert.match(note, /already sent them 0\.01 SOL/);
  assert.match(note, /still take any new message/);
});

test("Circle: an unconfirmed or bad signature is refused, nothing stored", async () => {
  const env = { BALANCES: kv() };
  const no = async () => ({ value: [null] });
  assert.equal((await recordSettled(env, { userId: "3fc4e9e2-87cf-4039-b61c-cdac0660a314", number: "+380637443792", amount: 0.01, token: "SOL", signature: sig }, no)).status, 409);
  assert.equal((await recordSettled(env, { userId: "3fc4e9e2-87cf-4039-b61c-cdac0660a314", number: "+380637443792", amount: 0.01, token: "DOGE", signature: sig }, ok)).status, 400);
  assert.equal(env.BALANCES.m.size, 0);
  assert.equal(settledNote([], "en"), "");
});
