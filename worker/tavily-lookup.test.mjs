import test from "node:test";
import assert from "node:assert/strict";
import { addPaymentChecks, lookupQuery } from "./tavily-lookup.js";

const cards = () => [
  { type: "payment", text: "Pay 2 SOL to unlock your delivery parcel", amount: 2, token: "SOL" },
  { type: "callback", text: "Call Anna back at 16:00" },
];

test("no key: cards unchanged and no network", async () => {
  let called = 0;
  const out = await addPaymentChecks({}, cards(), async () => { called++; });
  assert.equal(called, 0);
  assert.equal(out[0].check, undefined);
});

test("with key: only payment cards are checked, pattern query, links sanitised", async () => {
  const seen = [];
  const fake = async (url, init) => {
    seen.push({ url, body: JSON.parse(init.body), auth: init.headers.authorization });
    return { ok: true, json: async () => ({ answer: "Messages asking for crypto to release a parcel are a common delivery scam.", results: [{ title: "Delivery scams", url: "https://example.org/a" }, { title: "bad", url: "javascript:alert(1)" }] }) };
  };
  const out = await addPaymentChecks({ TAVILY_API_KEY: "k" }, cards(), fake);
  assert.equal(seen.length, 1);
  assert.equal(seen[0].url, "https://api.tavily.com/search");
  assert.match(seen[0].body.query, /^Is this a scam: "Pay 2 SOL/);
  assert.equal(seen[0].auth, "Bearer k");
  assert.equal(out[0].check.source, "tavily");
  assert.deepEqual(out[0].check.links, [{ title: "Delivery scams", url: "https://example.org/a" }]);
  assert.equal(out[1].check, undefined);
});

test("errors never break the cards", async () => {
  const out = await addPaymentChecks({ TAVILY_API_KEY: "k" }, cards(), async () => { throw new Error("down"); });
  assert.equal(out.length, 2);
  assert.equal(out[0].check, undefined);
  assert.equal(lookupQuery({ text: "" }), "");
});
