// node --test worker/nebius-paypal.test.mjs : Nemotron routing + PayPal invoice (all network mocked).
import test from "node:test";
import assert from "node:assert/strict";
import { chat } from "./assistant-extras.js";
import { providerChain, cleanText } from "./llm-provider.js";
import { paypalInvoiceRoute, invoiceInput, paypalStatusRoute, cardState } from "./paypal-invoice.js";
import { sanitizeActions, fiatOf } from "./assistant-extras.js";

const json = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "content-type": "application/json" } });

test("nebius first, openai fallback", () => {
  const c = providerChain({ LLM_PROVIDER: "nebius", NEBIUS_API_KEY: "n", OPENAI_API_KEY: "o" }, ["gpt-4.1-mini"]);
  assert.equal(c[0].provider, "nebius");
  assert.equal(c[0].model, "nvidia/nemotron-3-super-120b-a12b");
  assert.equal(c[1].provider, "openai");
  assert.equal(providerChain({ OPENAI_API_KEY: "o" }, ["m"])[0].provider, "openai");
});

test("chat calls Token Factory and strips think tags", async () => {
  const orig = globalThis.fetch;
  let url = "";
  globalThis.fetch = async (u) => { url = String(u); return json({ choices: [{ message: { content: "<think>x</think>```json\n{\"actions\":[]}\n```" } }] }); };
  try {
    const r = await chat({ LLM_PROVIDER: "nebius", NEBIUS_API_KEY: "n" }, { messages: [] }, 1000);
    assert.equal(url, "https://api.tokenfactory.nebius.com/v1/chat/completions");
    assert.equal(r.text, '{"actions":[]}');
    assert.equal(r.provider, "nebius");
  } finally { globalThis.fetch = orig; }
});

test("cleanText keeps plain text", () => assert.equal(cleanText("hi"), "hi"));

test("paypal invoice disabled by default, validates amount, creates sandbox invoice", async () => {
  const req = (b) => new Request("https://x/paypal/invoice", { method: "POST", body: JSON.stringify(b) });
  const ok = () => true;
  assert.equal((await paypalInvoiceRoute({}, req({ amount: 5 }), ok, json)).status, 404);
  assert.equal(invoiceInput({ amount: 0 }), null);
  const env = { PAYPAL_ENABLED: "1", PAYPAL_CLIENT_ID: "id", PAYPAL_CLIENT_SECRET: "sec" };
  const urls = [];
  const f = async (u) => {
    urls.push(String(u));
    if (String(u).endsWith("/oauth2/token")) return json({ access_token: "t" });
    if (String(u).endsWith("/send")) return json({ href: "https://www.sandbox.paypal.com/invoice/p/#INV2-X" });
    return json({ id: "INV2-X" }, 201);
  };
  const r = await (await paypalInvoiceRoute(env, req({ confirmed: true, amount: 200, text: "Andrii owes you $200" }), ok, json, f)).json();
  assert.equal(r.ok, true);
  assert.equal(r.payLink, "https://www.sandbox.paypal.com/invoice/p/#INV2-X");
  assert.ok(urls.every((u) => u.startsWith("https://api-m.sandbox.paypal.com")));
});

test("invoice needs the user's confirmation; status maps to card state; fiat owed only on opt-in", async () => {
  const env = { PAYPAL_ENABLED: "1", PAYPAL_CLIENT_ID: "id", PAYPAL_CLIENT_SECRET: "sec" };
  const r = await paypalInvoiceRoute(env, new Request("https://x", { method: "POST", body: JSON.stringify({ amount: 5 }) }), () => true, json);
  assert.equal((await r.json()).error, "confirm");
  assert.equal(cardState("PAID"), "paid");
  assert.equal(cardState("SENT"), "sent");
  const f = async (u) => (String(u).endsWith("/oauth2/token") ? json({ access_token: "t" }) : json({ status: "PAID", payments: { paid_amount: { value: "20.00", currency_code: "USD" } } }));
  const s = await (await paypalStatusRoute(env, new Request("https://x/paypal/invoice/status?id=INV2-AAAA-BBBB-CCCC-DDDD"), () => true, json, f)).json();
  assert.deepEqual([s.state, s.paid], ["paid", "20.00 USD"]);
  assert.equal(fiatOf("dollars"), "USD");
  const calls = [{ id: "c1", at: "10:00", date: "2026-10-22", intent: "x", notes: "", text: "" }];
  const raw = { actions: [{ callId: "c1", type: "owed", amount: 150, token: "dollars", recipient: "", number: "", when: "", day: "", date: "", text: "Andrii owes you $150", quote: "" }] };
  assert.equal(sanitizeActions(raw, calls).length, 0);
  const a = sanitizeActions(raw, calls, "en", { fiat: true });
  assert.deepEqual([a[0].token, a[0].fiat, a[0].usdSuggested], ["USD", true, 150]);
});
