import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  liveQuotes, MAX_LIVE_QUOTES, refresh, earliest,
  validRefund, toUnits, fromUnits, pickSources, quoteBody, describe, settlementOf, handleAnychain, publicView, solanaPayUrl,
  requirements, isSolanaAddress, DEST, _resetCache,
} from "./aurora-intents.js";
import worker, { json } from "./sol-anychain.js";
import { qrSvg, qrPayload, pageHtml } from "./anychain-page.js";

const TOKENS = JSON.parse(readFileSync(new URL("./fixtures/aurora/tokens-2026-10-23.json", import.meta.url), "utf8"));
const WALLET = "8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic";
const REF = "5sH2TB1x5gjYz9ReBfx9DjTjVX1kieYY41zCKjU47peL";
const BASE_USDC = "nep141:base-0x833589fcd6edb6e08f4c7c32d4f71b54bda02913.omft.near";
const EVM_ADDR = "0x1111111111111111111111111111111111111111";

function kvMem() {
  const m = new Map();
  return { m, async get(k) { return m.has(k) ? m.get(k) : null; }, async put(k, v) { m.set(k, v); } };
}
function env() { return { AURORA_API_KEY: "test-key", ANYCHAIN: kvMem() }; }

/** A fake Aurora API: records calls, answers like the documented schema. */
function fakeAurora(opts = {}) {
  const calls = [];
  let status = opts.status || "PENDING_DEPOSIT";
  const f = async (url, init = {}) => {
    const u = new URL(url);
    calls.push({ path: u.pathname, search: u.search, method: init.method || "GET", body: init.body ? JSON.parse(init.body) : null });
    const ok = (o) => new Response(JSON.stringify(o), { status: 200 });
    if (opts.fail && u.pathname.startsWith("/api/quote")) return new Response(JSON.stringify({ message: opts.fail }), { status: 400 });
    if (u.pathname === "/api/tokens/test-key") return ok(TOKENS);
    if (u.pathname === "/api/quote/test-key") {
      const b = JSON.parse(init.body);
      return ok({ timestamp: "t", signature: "s", quoteRequest: b, quote: { depositAddress: "0xdeposit000000000000000000000000000000001", amountIn: "5031000", amountInFormatted: "5.031", amountInUsd: "5.03", minAmountIn: "5031000", maxAmountIn: "5081310", amountOut: b.amount, amountOutFormatted: "5", amountOutUsd: "5", minAmountOut: b.amount, timeEstimate: 40, deadline: b.deadline, refundFee: "20000" } });
    }
    if (u.pathname === "/api/status/test-key") {
      const sd = status === "SUCCESS" ? { amountOut: "5000000", amountOutFormatted: "5", destinationChainTxHashes: [{ hash: "5Ysolanatx", explorerUrl: "" }], originChainTxHashes: [{ hash: "0xorigin" }] }
        : status === "REFUNDED" ? { refundedAmountFormatted: "5.011", refundReason: "deadline passed" } : {};
      return ok({ status, updatedAt: "2026-10-23T12:00:00Z", swapDetails: sd, quoteResponse: {} });
    }
    if (u.pathname === "/api/deposit/submit/test-key") return ok({ status: "KNOWN_DEPOSIT_TX", swapDetails: {} });
    if (u.pathname === "/api/incidents/test-key") return ok({ status: "incidents", incidents: [{ scopeType: "chain", scopeValue: "tron", status: "active", publicDescription: "Tron delays" }, { scopeType: "chain", scopeValue: "btc", status: "resolved" }] });
    return new Response("{}", { status: 404 });
  };
  return { f, calls, set: (s) => { status = s; } };
}

const req = (path, method = "GET", body) => {
  const r = new Request("https://sol-anychain.test" + path, { method, headers: { "content-type": "application/json", "cf-connecting-ip": "1.2.3.4" }, body: body ? JSON.stringify(body) : undefined });
  return [r, new URL(r.url)];
};
async function call(e, fa, path, method, body) {
  const [r, u] = req(path, method, body);
  const res = await handleAnychain(r, e, u, json, fa.f);
  return { status: res.status, body: await res.json() };
}

test("refund addresses are checked per chain before a quote is reserved", () => {
  assert.ok(validRefund("base", EVM_ADDR));
  assert.ok(!validRefund("base", WALLET), "a Solana address is not a Base address");
  assert.ok(validRefund("sol", WALLET));
  assert.ok(validRefund("btc", "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"));
  assert.ok(validRefund("tron", "TXYZopYRdj2D9XRtbG411XZZ3kM5VkAeBf"));
  assert.ok(validRefund("near", "vadym.near"));
  assert.ok(!validRefund("near", "Vadym.NEAR"));
  assert.ok(validRefund("stellar", "GA" + "A".repeat(54)));
  assert.ok(!validRefund("tron", EVM_ADDR));
  assert.ok(!validRefund("unknownchain", EVM_ADDR));
  assert.ok(isSolanaAddress(REF) && !isSolanaAddress("0OIl"));
});

test("amounts go to smallest units without floating point", () => {
  assert.equal(toUnits("5", 6), "5000000");
  assert.equal(toUnits(0.01, 9), "10000000");
  assert.equal(toUnits("1.000001", 6), "1000001");
  assert.throws(() => toUnits("1.0000001", 6));
  assert.throws(() => toUnits("0", 6));
  assert.throws(() => toUnits("-1", 6));
  assert.throws(() => toUnits("1e3", 6));
  assert.equal(fromUnits("5031000", 6), "5.031");
  assert.equal(fromUnits("10000000", 9), "0.01");
});

test("the source list comes from the real /api/tokens snapshot: many chains, well-known assets only", () => {
  const s = pickSources(TOKENS);
  const chains = new Set(s.map((x) => x.chain));
  assert.ok(chains.size >= 20, `chains: ${chains.size}`);
  for (const c of ["base", "arb", "eth", "tron", "btc", "near", "ton", "sol", "bsc", "pol"]) assert.ok(chains.has(c), c);
  assert.ok(s.find((x) => x.chain === "base" && x.symbol === "USDC" && x.assetId === BASE_USDC));
  assert.ok(!s.some((x) => ["TRUMP", "PENGU", "BOME", "MELANIA"].includes(x.symbol)), "no meme tokens offered");
  assert.equal(s.filter((x) => x.chain === "base" && x.symbol === "USDC").length, 1, "one entry per chain+symbol");
  assert.equal(s[0].symbol, "USDC");
  // the destination asset ids match the live list (USDC mint on Solana)
  const solUsdc = TOKENS.tokens.find((t) => t.assetId === DEST.USDC.assetId);
  assert.equal(solUsdc.contractAddress, DEST.USDC.mint);
  assert.ok(TOKENS.tokens.find((t) => t.assetId === DEST.SOL.assetId));
});

test("quotes are EXACT_OUTPUT to the requester's Solana wallet with refunds to the payer's own address", () => {
  const src = pickSources(TOKENS).find((x) => x.assetId === BASE_USDC);
  const b = quoteBody({ to: WALLET, token: "USDC", amount: "5" }, src, ` ${EVM_ADDR} `, Date.parse("2026-10-23T10:00:00Z"));
  assert.equal(b.swapType, "EXACT_OUTPUT");
  assert.equal(b.amount, "5000000");
  assert.equal(b.destinationAsset, DEST.USDC.assetId);
  assert.equal(b.recipient, WALLET);
  assert.equal(b.recipientType, "DESTINATION_CHAIN");
  assert.equal(b.refundTo, EVM_ADDR);
  assert.equal(b.refundType, "ORIGIN_CHAIN");
  assert.equal(b.depositType, "ORIGIN_CHAIN");
  assert.equal(b.dry, false);
  assert.equal(b.deadline, "2026-10-23T10:30:00.000Z");
  const btc = pickSources(TOKENS).find((x) => x.chain === "btc");
  assert.equal(quoteBody({ to: WALLET, token: "SOL", amount: "0.1" }, btc, "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", Date.parse("2026-10-23T10:00:00Z")).deadline, "2026-10-23T11:30:00.000Z");
});

test("full flow: request -> quote -> status SUCCESS marks paid with the Solana tx", async () => {
  _resetCache();
  const e = env(); const fa = fakeAurora();
  const c = await call(e, fa, "/anychain/request", "POST", { to: WALLET, amount: 5, token: "usdc", who: "Ira", from: "Vadym", note: "Lunch <b>", reference: REF });
  assert.equal(c.status, 200);
  assert.match(c.body.url, /^https:\/\/sol-anychain\.test\/r\/[a-z0-9]{10}$/);
  assert.equal(c.body.request.note, "Lunch b");
  assert.equal(c.body.request.solanaPay, `solana:${WALLET}?amount=5&spl-token=${DEST.USDC.mint}&reference=${REF}&label=Sol&message=Lunch%20b`);
  const id = c.body.id;

  const bad = await call(e, fa, "/anychain/quote", "POST", { id, origin: BASE_USDC, refundTo: WALLET });
  assert.equal(bad.status, 400); assert.equal(bad.body.error, "BAD_REFUND_ADDRESS");
  assert.ok(!fa.calls.some((x) => x.path.startsWith("/api/quote")), "no quote reserved for a bad refund address");

  const q = await call(e, fa, "/anychain/quote", "POST", { id, origin: BASE_USDC, refundTo: EVM_ADDR });
  assert.equal(q.status, 200);
  assert.equal(q.body.deposit.depositAddress, "0xdeposit000000000000000000000000000000001");
  assert.equal(q.body.deposit.amountInFormatted, "5.031");
  assert.equal(q.body.deposit.chainName, "Base");
  assert.ok(!("refundTo" in q.body.deposit), "refund address is never public");
  assert.match(q.body.requirements[0], /Send only USDC from the Base network\. 1 asset is supported/);
  assert.ok(q.body.requirements.some((l) => /loss of funds/.test(l)));
  assert.equal(q.body.requirements.at(-1), "Vadym receives exactly 5 USDC on Solana, at 8J3h…67ic.", "the requester, not the payer, receives");
  const sent = fa.calls.find((x) => x.path === "/api/quote/test-key").body;
  assert.equal(sent.swapType, "EXACT_OUTPUT"); assert.equal(sent.recipient, WALLET);

  let s = await call(e, fa, `/anychain/status?id=${id}`);
  assert.equal(s.body.info.title, "Waiting for your transfer");
  assert.equal(s.body.request.status, "open");

  const sub = await call(e, fa, "/anychain/submit", "POST", { id, depositAddress: "0xdeposit000000000000000000000000000000001", txHash: "0x" + "ab".repeat(32) });
  assert.equal(sub.body.status, "KNOWN_DEPOSIT_TX");
  assert.deepEqual(fa.calls.find((x) => x.path === "/api/deposit/submit/test-key").body, { txHash: "0x" + "ab".repeat(32), depositAddress: "0xdeposit000000000000000000000000000000001" });

  fa.set("PROCESSING");
  s = await call(e, fa, `/anychain/status?id=${id}`);
  assert.equal(s.body.info.title, "Converting");
  fa.set("SUCCESS");
  const r = await call(e, fa, `/anychain/request/${id}`);
  assert.equal(r.body.status, "paid");
  assert.deepEqual(r.body.paid.solanaTxs, ["5Ysolanatx"]);
  assert.equal(r.body.paid.via, "aurora-intents");
  assert.equal(r.body.paid.chain, "base");

  const again = await call(e, fa, "/anychain/quote", "POST", { id, origin: BASE_USDC, refundTo: EVM_ADDR });
  assert.equal(again.status, 409); assert.equal(again.body.error, "ALREADY_PAID");
  const n = fa.calls.filter((x) => x.path.startsWith("/api/status")).length;
  await call(e, fa, `/anychain/request/${id}`);
  assert.equal(fa.calls.filter((x) => x.path.startsWith("/api/status")).length, n, "a paid request is not polled again");
});

test("refunds are reported with amount and reason, and the request stays open", async () => {
  _resetCache();
  const e = env(); const fa = fakeAurora();
  const { body } = await call(e, fa, "/anychain/request", "POST", { to: WALLET, amount: "0.05", token: "SOL" });
  await call(e, fa, "/anychain/quote", "POST", { id: body.id, origin: BASE_USDC, refundTo: EVM_ADDR });
  fa.set("REFUNDED");
  const s = await call(e, fa, `/anychain/status?id=${body.id}`);
  assert.equal(s.body.request.status, "open");
  assert.equal(s.body.info.title, "Refunded");
  assert.match(s.body.info.body, /5\.011 went back to your refund address \(deadline passed\)/);
  assert.deepEqual(s.body.request.deposits[0].refunded, { amount: "5.011", reason: "deadline passed" });
  fa.set("SUCCESS");
  await call(e, fa, `/anychain/status?id=${body.id}`);
  assert.equal(fa.calls.filter((x) => x.path.startsWith("/api/status")).length, 1, "a final (refunded) deposit is not polled again");
});

test("input validation and errors", async () => {
  _resetCache();
  const e = env(); const fa = fakeAurora();
  assert.equal((await call(e, fa, "/anychain/request", "POST", { to: WALLET, amount: 1, token: "SKR" })).body.error, "TOKEN_NOT_SUPPORTED");
  assert.equal((await call(e, fa, "/anychain/request", "POST", { to: EVM_ADDR, amount: 1, token: "USDC" })).body.error, "BAD_RECIPIENT");
  assert.equal((await call(e, fa, "/anychain/request", "POST", { to: WALLET, amount: 5000, token: "USDC" })).body.error, "AMOUNT_TOO_LARGE");
  assert.equal((await call(e, fa, "/anychain/request", "POST", { to: WALLET, amount: "1.0000001", token: "USDC" })).body.error, "BAD_AMOUNT");
  assert.equal((await call(e, fa, "/anychain/request/zzzzzzzzzz")).status, 404);
  const { body } = await call(e, fa, "/anychain/request", "POST", { to: WALLET, amount: 1, token: "USDC", reference: REF });
  const solUsdc = (await call(e, fa, "/anychain/quote", "POST", { id: body.id, origin: DEST.USDC.assetId, refundTo: WALLET })).body;
  assert.equal(solUsdc.error, "USE_SOLANA_PAY");
  assert.match(solUsdc.solanaPay, /^solana:/);
  assert.equal((await call(e, fa, "/anychain/quote", "POST", { id: body.id, origin: "nep141:made-up", refundTo: EVM_ADDR })).body.error, "SOURCE_NOT_SUPPORTED");
  assert.equal((await call(e, fa, "/anychain/submit", "POST", { id: body.id, depositAddress: "nope", txHash: "0x" + "ab".repeat(32) })).status, 404);
  const down = fakeAurora({ fail: "Amount is too low for bridge, try at least 1.2" });
  const r = await call(e, down, "/anychain/quote", "POST", { id: body.id, origin: BASE_USDC, refundTo: EVM_ADDR });
  assert.equal(r.status, 502); assert.equal(r.body.error, "AURORA_ERROR"); assert.match(r.body.detail, /too low/);
  const nokey = { ANYCHAIN: kvMem() };
  _resetCache();
  assert.equal((await call(nokey, fa, "/anychain/sources")).body.error, "AURORA_NOT_CONFIGURED");
});

test("rate limit on request creation", async () => {
  const e = env(); const fa = fakeAurora();
  let last;
  for (let i = 0; i < 31; i++) last = await call(e, fa, "/anychain/request", "POST", { to: WALLET, amount: 1, token: "USDC" });
  assert.equal(last.status, 429);
});

test("incidents are filtered to active ones for the chosen chain", async () => {
  const e = env(); const fa = fakeAurora();
  const r = await call(e, fa, "/anychain/incidents?chain=tron");
  assert.deepEqual(r.body.incidents, [{ scope: "chain", value: "tron", text: "Tron delays" }]);
});

test("payer-facing copy for every Swap API status", () => {
  for (const s of ["PENDING_DEPOSIT", "KNOWN_DEPOSIT_TX", "PROCESSING", "INCOMPLETE_DEPOSIT", "SUCCESS", "REFUNDED", "FAILED"]) assert.ok(describe(s, {}, "USDC").title, s);
  assert.match(describe("INCOMPLETE_DEPOSIT", { depositedAmountFormatted: "3 USDC" }).body, /Received 3 USDC.*refunded to your address/);
  assert.deepEqual(settlementOf({ swapDetails: { destinationChainTxHashes: ["a", { hash: "b" }], amountOut: "1" } }).solanaTxs, ["a", "b"]);
});

test("worker: pay page, QR and routing", async () => {
  const e = env();
  const page = await worker.fetch(new Request("https://x.test/r/abcdefghjk"), e);
  assert.equal(page.status, 200);
  assert.match(page.headers.get("content-security-policy"), /default-src 'none'/);
  const html = await page.text();
  assert.match(html, /Pay from another network/);
  assert.match(html, /Transfer requirements/);
  assert.ok(!/https?:\/\/(?!solscan\.io)[a-z]/i.test(html.replace(/http:\/\/www\.w3\.org\/2000\/svg/g, "")), "the page loads nothing from other origins");
  assert.equal(pageHtml("ab<script>").includes("<script>\"ab"), false);
  const qr = await worker.fetch(new Request("https://x.test/anychain/qr?d=bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq&c=btc&a=0.0001"), e);
  assert.equal(qr.headers.get("content-type"), "image/svg+xml");
  assert.match(await qr.text(), /^<svg/);
  assert.equal((await worker.fetch(new Request("https://x.test/anychain/qr?d=<x>"), e)).status, 400);
  assert.equal(qrPayload("btc", "bc1q", "0.1"), "bitcoin:bc1q?amount=0.1");
  assert.equal(qrPayload("base", "0xabc", "5"), "0xabc");
  assert.match(qrSvg("hello"), /<path d="M/);
  assert.equal((await worker.fetch(new Request("https://x.test/nope"), e)).status, 404);
  const root = await (await worker.fetch(new Request("https://x.test/"), e)).json();
  assert.equal(root.aurora, true);
});

test("public view never leaks refund addresses; Solana Pay link only with a reference", () => {
  const r = { id: "a", to: WALLET, token: "SOL", amount: "0.1", deposits: [{ depositAddress: "d", refundTo: "secret-ish", chain: "base", symbol: "USDC", deadline: "x" }] };
  const v = publicView(r);
  assert.equal(JSON.stringify(v).includes("secret-ish"), false);
  assert.equal(v.solanaPay, null);
  assert.equal(solanaPayUrl({ ...r, reference: REF }), `solana:${WALLET}?amount=0.1&reference=${REF}&label=Sol`);
  assert.ok(requirements({ symbol: "XLM", chainName: "Stellar", decimals: 7 }, { amountInFormatted: "10", depositMemo: "123", deadline: "d" }, { amount: "1", token: "USDC", to: WALLET, from: "Vadym" }).some((l) => /memo 123/.test(l)));
});

test("an earlier quote is never dropped while it can still settle; dead quotes stop being polled", async () => {
  _resetCache();
  const e = env(); const fa = fakeAurora();
  const { body } = await call(e, fa, "/anychain/request", "POST", { to: WALLET, amount: 5, token: "USDC" });
  // 11 more quotes from the same page (a payer who keeps switching networks)
  for (let i = 0; i < MAX_LIVE_QUOTES; i++) {
    const q = await call(e, fa, "/anychain/quote", "POST", { id: body.id, origin: BASE_USDC, refundTo: EVM_ADDR });
    assert.equal(q.status, 200, `quote ${i}`);
  }
  const extra = await call(e, fa, "/anychain/quote", "POST", { id: body.id, origin: BASE_USDC, refundTo: EVM_ADDR });
  assert.equal(extra.status, 429); assert.equal(extra.body.error, "TOO_MANY_QUOTES");
  const stored = JSON.parse(await e.ANYCHAIN.get("ac:req:" + body.id));
  assert.equal(stored.deposits.length, MAX_LIVE_QUOTES, "all live quotes kept");
  // the first quote expired 2 h ago unused -> dropped by liveQuotes; one with a submitted tx hash is kept
  const now = Date.now();
  const r = { deposits: [
    { status: "PENDING_DEPOSIT", deadline: new Date(now - 2 * 3600_000).toISOString() },
    { status: "PENDING_DEPOSIT", txHash: "0xabc", deadline: new Date(now - 2 * 3600_000).toISOString() },
    { status: "KNOWN_DEPOSIT_TX", deadline: new Date(now - 2 * 3600_000).toISOString() },
    { status: "PENDING_DEPOSIT", deadline: new Date(now + 600_000).toISOString() },
  ] };
  assert.equal(liveQuotes(r, now).length, 3);
  const dead = { status: "open", deposits: [{ depositAddress: "0xdead", status: "PENDING_DEPOSIT", deadline: new Date(now - 7 * 3600_000).toISOString() }] };
  const n = fa.calls.length;
  await refresh(e, dead, fa.f, now);
  assert.equal(fa.calls.length, n, "a quote 7 h past its deadline is not polled");
});

test("the payer sees the deadline we asked for, not Aurora's later quote.deadline (live 23 Oct: +72 h)", async () => {
  _resetCache();
  const e = env(); const base = fakeAurora();
  const f = async (url, init) => {
    const res = await base.f(url, init);
    if (!String(url).includes("/api/quote/")) return res;
    const j = await res.json();
    j.quote.deadline = new Date(Date.parse(j.quoteRequest.deadline) + 72 * 3600_000).toISOString();
    return new Response(JSON.stringify(j), { status: 200 });
  };
  const { body } = await call(e, base, "/anychain/request", "POST", { to: WALLET, amount: 1, token: "USDC" });
  const [r, u] = req("/anychain/quote", "POST", { id: body.id, origin: BASE_USDC, refundTo: EVM_ADDR });
  const q = await (await handleAnychain(r, e, u, json, f)).json();
  const mins = (Date.parse(q.deposit.deadline) - Date.now()) / 60000;
  assert.ok(mins > 28 && mins <= 30, `deadline in ${mins} min`);
  assert.equal(earliest("2026-10-26T11:50:00Z", "2026-10-23T11:50:00Z", "bad"), "2026-10-23T11:50:00.000Z");
});
