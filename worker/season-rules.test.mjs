import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { clipSentences, blogUrls, pageText, publishedOf, sanitizeSignals, checkRules, RULES_KEY, DOCS_PAGES, SKIPPED } from "./season-rules.js";

const fx = (n) => readFileSync(new URL("./fixtures/season/" + n, import.meta.url), "utf8");
const SUMMER = "https://solanamobile.com/blog/summer-wrapped.-what%E2%80%99s-next-on-your-seeker";
const CLOCK = "https://solanamobile.com/blog/clock-in-the-solana-mobile-hackathon";

function kvMem() {
  const m = new Map();
  return { m, async get(k, t) { const v = m.get(k); return v == null ? null : t === "json" ? JSON.parse(v) : v; }, async put(k, v) { m.set(k, v); } };
}

test("clipSentences cuts at a sentence or a word, never mid-word", () => {
  assert.equal(clipSentences("One two three four. Five six seven eight nine ten.", 30), "One two three four.");
  assert.equal(clipSentences("alpha beta gamma delta", 15), "alpha beta…");
  assert.equal(clipSentences("short", 50), "short");
});

test("sitemap gives the newest official blog posts first", () => {
  const urls = blogUrls(fx("sitemap.xml"));
  assert.equal(urls.length, 4);
  assert.equal(urls[0], SUMMER);
  assert.equal(urls[1], CLOCK);
  assert.ok(urls.every((u) => u.startsWith("https://solanamobile.com/blog/")));
});

test("page text comes from the article of the real pages", () => {
  const p = pageText(fx("summer-wrapped.html"));
  assert.match(p.title, /Summer Wrapped/);
  assert.ok(p.text.includes("gives more weight to everyday wallet use and less to activity created to influence the score"));
  assert.ok(!p.text.includes("<div"));
  assert.equal(publishedOf(p.text), "2026-09-29");
  assert.equal(pageText(fx("sms-goes-global.html")).published, "2026-07-14");
  assert.match(pageText(fx("docs-skr.html")).text, /Staking epochs are 2 days/);
});

test("a signal is kept only with a verbatim quote and confidence; nothing invented", () => {
  const { text } = pageText(fx("summer-wrapped.html"));
  const raw = {
    relevant: true,
    summary: "Seed Vault Wallet now weights everyday wallet use more and score-gaming activity less.",
    signals: [
      { kind: "favored", action: "wallet_activity", dapp: "", start: "", end: "", text: "Everyday wallet use counts more.", quote: "The update gives more weight to everyday wallet use and less to activity created to influence the score.", confidence: 0.95 },
      { kind: "devalued", action: "wallet_activity", dapp: "", start: "", end: "", text: "Activity made to influence the score counts less.", quote: "less to activity created to influence the score", confidence: 0.9 },
      { kind: "favored", action: "swap", dapp: "", start: "", end: "", text: "Swaps count double.", quote: "every swap now counts double for Season points", confidence: 0.99 },
      { kind: "favored", action: "staking", dapp: "", start: "", end: "", text: "SKR integration prize.", quote: "Round 4 brought Seeker Summer to a close", confidence: 0.9 },
      { kind: "campaign", action: "quest", dapp: "", start: "2026-13-01", end: "", text: "Round 4 quests.", quote: "Round 4 brought Seeker Summer to a close with eight quests", confidence: 0.4 },
    ],
  };
  const s = sanitizeSignals(JSON.stringify(raw), text);
  assert.equal(s.relevant, true);
  assert.deepEqual(s.signals.map((x) => x.kind + ":" + x.action), ["favored:wallet_activity", "devalued:wallet_activity", "info:staking"], "a prize is a note, not a scoring signal");
  assert.equal(s.dropped, 2, "the made-up swap rule and the unsure one are dropped");
  assert.equal(sanitizeSignals("not json", text).signals.length, 0);
  assert.equal(sanitizeSignals("", text).failed, true);
  assert.equal(sanitizeSignals({ relevant: true, summary: "x", signals: [] }, text).relevant, false);
});

test("checkRules: versioned KV, re-extracts only changed pages, keeps sources on errors, never reads X", async () => {
  const pages = {
    "https://solanamobile.com/sitemap.xml": fx("sitemap.xml"),
    [SUMMER]: fx("summer-wrapped.html"),
    [CLOCK]: fx("clock-in.html"),
    [DOCS_PAGES[0]]: fx("docs-skr.html"),
  };
  const fetched = [];
  const fetcher = async (u) => { fetched.push(u); return pages[u] ? new Response(pages[u]) : new Response("no", { status: 404 }); };
  const calls = [];
  const extractor = async (url, title, text) => {
    calls.push(url);
    if (url === SUMMER) return sanitizeSignals({ relevant: true, summary: "Everyday wallet use weighs more.", signals: [{ kind: "favored", action: "wallet_activity", dapp: "", start: "", end: "", text: "Everyday wallet use counts more.", quote: "gives more weight to everyday wallet use", confidence: 0.9 }] }, text);
    return { relevant: false, summary: "", signals: [], dropped: 0 };
  };
  const kv = kvMem();
  const r1 = await checkRules({}, { kv, fetcher, extractor, now: 1000 });
  assert.equal(r1.rules.version, 1);
  assert.equal(r1.extracted, 3, "at most 3 pages per run");
  assert.equal(r1.rules.signals.length, 1);
  assert.equal(r1.rules.signals[0].url, SUMMER);
  assert.equal(r1.rules.signals[0].published, "2026-09-29");
  assert.ok(r1.rules.errors.some((e) => e.includes("404")), "two of the four posts are not in the fixtures");
  assert.deepEqual(r1.rules.skipped, SKIPPED);
  assert.ok(!fetched.some((u) => u.includes("x.com") || u.includes("twitter")));
  assert.ok(kv.m.has("season-rules:v1"));
  assert.deepEqual(calls, [SUMMER, CLOCK, DOCS_PAGES[0]]);
  // second run: same pages -> nothing goes to the model again
  calls.length = 0;
  const r3 = await checkRules({}, { kv, fetcher, extractor, now: 3000 });
  assert.deepEqual(calls, []);
  assert.equal(r3.rules.version, 1, "nothing changed");
  assert.equal(r3.rules.checkedAt, 3000);
  // the official post changes -> new version
  pages[SUMMER] = pages[SUMMER].replace("gives more weight to everyday wallet use", "gives much more weight to everyday wallet use");
  const r4 = await checkRules({}, { kv, fetcher, extractor: async (u, t, x) => sanitizeSignals({ relevant: true, summary: "Changed.", signals: [{ kind: "favored", action: "daily_use", dapp: "", start: "", end: "", text: "Daily use.", quote: "gives much more weight to everyday wallet use", confidence: 0.9 }] }, x), now: 4000 });
  assert.equal(r4.rules.version, 2);
  assert.equal(JSON.parse(kv.m.get(RULES_KEY)).version, 2);
  assert.ok(kv.m.has("season-rules:v2"));
  // a failed model reply never wipes what was read before
  pages[SUMMER] = pages[SUMMER].replace("gives much more weight", "gives a lot more weight");
  const r5 = await checkRules({}, { kv, fetcher, extractor: async () => sanitizeSignals("", ""), now: 5000 });
  assert.equal(r5.rules.version, 2);
  assert.equal(r5.rules.signals[0].quote, "gives much more weight to everyday wallet use");
  assert.ok(r5.rules.errors.some((e) => e.includes("extraction failed")));
});
