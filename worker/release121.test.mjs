import test from "node:test";
import assert from "node:assert/strict";
import { metaDate, pageText } from "./season-rules.js";
import { callbackWord, noteText } from "./solarchik-screen.js";
import { briefingSystem } from "./assistant-extras.js";

test("blog date: article:published_time wins over a newer date in the text", () => {
  const html = `<html><head><meta property="article:published_time" content="2026-09-29T10:36:57Z"></head>
  <body><article><p>Related: Oct 9, 2026</p><h1>Summer wrapped</h1><p>text</p></article></body></html>`;
  assert.equal(metaDate(html), "2026-09-29");
  assert.equal(pageText(html).published, "2026-09-29");
});

test("blog date: JSON-LD datePublished, then <time datetime>, then the text", () => {
  assert.equal(metaDate(`<script type="application/ld+json">{"@type":"BlogPosting","datePublished":"2026-10-05"}</script>`), "2026-10-05");
  assert.equal(metaDate(`<p>Oct 9, 2026</p><time datetime="2026-09-08T08:00:00.000Z">Sep 8, 2026</time>`), "2026-09-08");
  assert.equal(metaDate(`<p>nothing</p>`), "");
  assert.equal(pageText(`<article><h1>A</h1><p>Aug 19, 2026</p></article>`).published, "2026-08-19");
});

test("Ukrainian notes say the callback in Ukrainian, English stays", () => {
  assert.equal(noteText({ caller_name: "Іра", intent: "просить 50 SKR", callback: "+380638500117" }, "uk"), "Іра: просить 50 SKR. Номер для зворотного дзвінка: +380638500117.");
  assert.equal(noteText({ caller_name: "Ira", intent: "asks for 50 SKR", callback: "+380638500117" }), "Ira: asks for 50 SKR. Callback +380638500117.");
  assert.equal(callbackWord("Вадим: привіт. Callback +380638500117.", "uk"), "Вадим: привіт. Номер для зворотного дзвінка: +380638500117.");
  assert.equal(callbackWord("Vadim: hi. Callback +380638500117.", "en"), "Vadim: hi. Callback +380638500117.");
});

test("briefing does not read phone numbers aloud", () => {
  for (const l of ["en", "uk"]) assert.match(briefingSystem(l), /do not read phone numbers aloud/);
});
