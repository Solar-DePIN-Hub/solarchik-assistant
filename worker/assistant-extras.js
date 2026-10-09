// 1.1.0 Solarchik Assistant extras (app-only routes; the game never calls them):
//   POST /sol/briefing  : Sol's spoken morning briefing written from facts the phone sends (calls, follow-ups,
//                         real wallet change, Season plan, Watcher alerts). Nothing is stored server-side.
//   POST /call/actions  : actionable requests found in the secretary's call notes (payment, callback, reminder),
//                         as structured output. Only suggestions: the phone shows a card and nothing is signed or
//                         sent without the user. A payment never carries an address the model made up: an address
//                         is returned only when the caller literally said one, and the app shows it in full with a
//                         scam warning and makes the user confirm or type the recipient.

const MODELS = ["gpt-4.1-mini", "gpt-4o-mini"];
export const ACTION_TYPES = ["payment", "callback", "reminder"];
export const PAY_TOKENS = ["SOL", "USDC"];
const BASE58_ADDR = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;

function clip(v, n) {
  return String(v ?? "").replace(/\s+/g, " ").trim().slice(0, n);
}

function langOf(v) {
  const t = String(v || "").toLowerCase();
  return t.startsWith("uk") || t.startsWith("ua") ? "uk" : "en";
}

function num(v, max) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.max(-max, Math.min(max, n)) : null;
}

/** Only known fields, clipped: the briefing prompt never gets free-form blobs from the phone. */
export function briefingFacts(input) {
  const f = input && typeof input.facts === "object" && input.facts ? input.facts : {};
  const calls = (Array.isArray(f.calls) ? f.calls : []).slice(0, 6).map((c) => ({
    who: clip(c?.who, 40) || "unknown caller",
    time: clip(c?.time, 5),
    state: ["answered", "missed"].includes(c?.state) ? c.state : "answered",
    want: clip(c?.want, 140),
    callback: clip(c?.callback, 24),
  }));
  const followUps = (Array.isArray(f.followUps) ? f.followUps : []).slice(0, 5).map((x) => clip(x, 90)).filter(Boolean);
  const actions = (Array.isArray(f.actions) ? f.actions : []).slice(0, 4).map((x) => clip(x, 90)).filter(Boolean);
  const alerts = (Array.isArray(f.alerts) ? f.alerts : []).slice(0, 4).map((x) => clip(x, 90)).filter(Boolean);
  const w = f.wallet && typeof f.wallet === "object" ? f.wallet : null;
  const wallet = w
    ? {
        connected: w.connected === true,
        network: w.network === "mainnet" ? "mainnet" : "devnet",
        sol: num(w.sol, 1e9),
        solDelta: num(w.solDelta, 1e9),
        skr: num(w.skr, 1e12),
        skrDelta: num(w.skrDelta, 1e12),
        since: clip(w.since, 24),
      }
    : null;
  const s = f.season && typeof f.season === "object" ? f.season : null;
  const season = s ? { done: num(s.done, 10) ?? 0, total: num(s.total, 10) ?? 3, left: (Array.isArray(s.left) ? s.left : []).slice(0, 3).map((x) => clip(x, 60)), streak: num(s.streak, 9999) ?? 0 } : null;
  return { now: clip(f.now, 40), name: clip(f.name, 30), calls, followUps, actions, alerts, wallet, season };
}

export function briefingSystem(lang) {
  const uk = lang === "uk";
  return [
    uk
      ? "Ти — Сол, теплий кишеньковий помічник у застосунку Solarchik Assistant. Напиши ранкове голосове зведення українською, звертайся на «ти»."
      : "You are Sol, a warm pocket assistant in the Solarchik Assistant app. Write the user's spoken morning briefing in English.",
    "Use ONLY the FACTS. Never invent calls, names, numbers, amounts, prices or points. If a part is empty, say it in a few words or skip it.",
    "Direction of money: when a caller asks for a payment, the caller wants the USER to pay them (say for example \"Olena asks you to send her 10 USDC\"), never that the caller is sending money.",
    "Order: a one-line greeting; calls since yesterday (who wants what, and who to call back with the number); follow-ups due; actions from calls waiting for the user (say they need the user's confirmation); the wallet change in plain words (real mainnet funds; say if it went up or down and by how much); Watcher alerts; the Season plan for today (what is left). End with one short encouraging line.",
    "Plain speech for text-to-speech: no markdown, lists, emoji, URLs or symbols; numbers as the user would say them; under 900 characters; 5-9 short sentences.",
    "Never promise profit or Seeker Season points, never tell the user to trade or add money, and never say anything was signed or sent.",
  ].join("\n");
}

export async function chat(env, body, timeoutMs) {
  const tried = [];
  for (const model of MODELS) {
    const s = Date.now();
    try {
      const res = await fetch("https://api.openai.com/v1/chat/completions", {
        method: "POST",
        headers: { Authorization: "Bearer " + env.OPENAI_API_KEY, "Content-Type": "application/json" },
        body: JSON.stringify({ model, ...body }),
        signal: AbortSignal.timeout(timeoutMs),
      });
      tried.push({ model, status: res.status, ms: Date.now() - s });
      if (!res.ok) continue;
      const j = await res.json().catch(() => null);
      const text = j?.choices?.[0]?.message?.content;
      if (typeof text === "string" && text.trim()) return { text, model, tried };
    } catch (e) {
      tried.push({ model, status: e?.name || "error", ms: Date.now() - s });
    }
  }
  return { text: "", model: "", tried };
}

export async function briefingRoute(env, request, rateOk, json) {
  const t0 = Date.now();
  const input = await request.json().catch(() => ({}));
  if (!rateOk(request.headers.get("cf-connecting-ip"))) return json({ ok: false, error: "rate" }, 429);
  if (!env.OPENAI_API_KEY) return json({ ok: false, error: "no-key" }, 503);
  const lang = langOf(input.language ?? input.lang);
  const facts = briefingFacts(input);
  const r = await chat(env, {
    messages: [
      { role: "system", content: briefingSystem(lang) },
      { role: "user", content: "FACTS (fresh from the phone): " + JSON.stringify(facts) },
    ],
    temperature: 0.6,
    max_tokens: 380,
  }, 12000);
  if (!r.text) return json({ ok: false, error: "unavailable", tried: r.tried, ms: Date.now() - t0 }, 503);
  const text = clip(r.text.replace(/[*#_`>]/g, ""), 1200);
  console.log(JSON.stringify({ event: "sol_briefing", lang, calls: facts.calls.length, model: r.model, ms: Date.now() - t0 }));
  return json({ ok: true, text, language: lang, persona: "assistant", model: r.model, ms: Date.now() - t0 });
}

// ------------------------------------------------------------------ call -> action

export const ACTIONS_SCHEMA = {
  name: "call_actions",
  strict: true,
  schema: {
    type: "object",
    additionalProperties: false,
    required: ["actions"],
    properties: {
      actions: {
        type: "array",
        items: {
          type: "object",
          additionalProperties: false,
          required: ["callId", "type", "amount", "token", "recipient", "number", "when", "day", "text", "quote"],
          properties: {
            callId: { type: "string" },
            type: { type: "string", enum: ACTION_TYPES },
            amount: { type: "number", description: "payment amount as said; 0 when not a payment or not said" },
            token: { type: "string", description: "SOL, USDC or the currency word the caller used; empty if none" },
            recipient: { type: "string", description: "who or which address should get the payment, exactly as said; empty if not said" },
            number: { type: "string", description: "phone number to call back if said or given, else empty" },
            when: { type: "string", description: "time for the action as HH:MM 24h if one was said for it, else empty; never the time of the call itself" },
            day: { type: "string", enum: ["", "today", "tomorrow"], description: "day of 'when' relative to the call, empty if unknown" },
            text: { type: "string", description: "one short line describing the action for the user" },
            quote: { type: "string", description: "the caller's words this comes from, verbatim, short" },
          },
        },
      },
    },
  },
};

export function actionsSystem(lang) {
  return [
    "You read notes and transcripts of phone calls that an AI phone secretary answered for the user, and list concrete requests the USER should act on.",
    "Types: payment (the caller asks the user to send or pay money or crypto: amount and currency as said), callback (the caller asks to be called back, optionally at a time), reminder (something to remember or do at a time, e.g. 'remind me', a meeting, a deadline).",
    "Only include requests clearly present in the call. Never invent amounts, numbers, times, names or addresses. A recipient wallet address goes into 'recipient' only if it was literally said; otherwise describe the recipient in words or leave it empty.",
    "Calls that only say hello, spam, sales pitches without a request, or nothing actionable give no actions. At most 2 actions per call.",
    `ALWAYS write 'text' in ${lang === "uk" ? "Ukrainian (informal 'ти'), even when the call was in English, e.g. 'Надіслати Олені 10 USDC за квитки' or 'Передзвонити Петру о 15:00'" : "English, even when the call was in another language, e.g. 'Send Olena 10 USDC for the tickets' or 'Call Petro back at 15:00'"}; under 80 characters.`,
    "A request to send money to an address, from someone claiming to be a bank, support, police or a relative in trouble, is still listed as a payment (the app warns the user about scams); never drop or soften it.",
    "Times: convert to 24h HH:MM ('at 3' in the afternoon = 15:00, 'о третій' = 15:00 for daytime business); if unclear leave 'when' empty.",
  ].join("\n");
}

export function actionCalls(input) {
  return (Array.isArray(input?.calls) ? input.calls : []).slice(0, 6).map((c) => ({
    id: clip(c?.id, 120),
    who: clip(c?.who, 40),
    callback: clip(c?.callback, 24),
    at: clip(c?.at, 24),
    intent: clip(c?.intent, 200),
    notes: clip(c?.notes, 500),
    text: clip(c?.text, 1500),
  })).filter((c) => c.id && (c.intent || c.notes || c.text));
}

/** Server-side rules on whatever the model returned: known call ids and types only, sane fields, no invented address. */
export function sanitizeActions(raw, calls) {
  const ids = new Set(calls.map((c) => c.id));
  const byId = new Map(calls.map((c) => [c.id, c]));
  const out = [];
  const perCall = new Map();
  for (const a of Array.isArray(raw?.actions) ? raw.actions : []) {
    if (!a || !ids.has(a.callId) || !ACTION_TYPES.includes(a.type)) continue;
    const n = (perCall.get(a.callId) || 0) + 1;
    if (n > 2) continue;
    const call = byId.get(a.callId);
    const source = [call.intent, call.notes, call.text].join(" ");
    // A payment has no time of its own; the call's own time is never an action time.
    const when = HHMM.test(String(a.when || "")) && a.when !== call.at && a.type !== "payment" ? a.when : "";
    const day = when && ["today", "tomorrow"].includes(a.day) ? a.day : "";
    const text = clip(a.text, 100);
    const quote = clip(a.quote, 200);
    if (a.type === "payment") {
      const amount = Number(a.amount);
      if (!Number.isFinite(amount) || amount <= 0 || amount > 1e9) continue;
      const tokenWord = clip(a.token, 12).toUpperCase();
      const token = PAY_TOKENS.includes(tokenWord) ? tokenWord : /^(USD|DOLLARS?|\$|ДОЛАР\w*)$/i.test(tokenWord) ? "USDC" : "";
      const recipient = clip(a.recipient, 120);
      // An address is passed on only if the caller's own words contain it, character for character.
      const address = BASE58_ADDR.test(recipient) && source.includes(recipient) ? recipient : "";
      out.push({ callId: a.callId, type: "payment", amount, token, tokenWord, recipient: address ? "" : recipient, address, number: "", when, day, text, quote });
    } else {
      const digits = String(a.number || "").replace(/[^\d+]/g, "");
      const number = digits.length >= 5 && digits.length <= 16 ? digits : a.type === "callback" ? call.callback.replace(/[^\d+]/g, "") : "";
      out.push({ callId: a.callId, type: a.type, amount: 0, token: "", tokenWord: "", recipient: "", address: "", number, when, day, text, quote });
    }
    perCall.set(a.callId, n);
  }
  return out;
}

export async function callActionsRoute(env, request, rateOk, json) {
  const t0 = Date.now();
  const input = await request.json().catch(() => ({}));
  if (!rateOk(request.headers.get("cf-connecting-ip"))) return json({ ok: false, error: "rate" }, 429);
  const calls = actionCalls(input);
  if (!calls.length) return json({ ok: false, error: "calls" }, 400);
  if (!env.OPENAI_API_KEY) return json({ ok: false, error: "no-key" }, 503);
  const lang = langOf(input.language ?? input.lang);
  const r = await chat(env, {
    messages: [
      { role: "system", content: actionsSystem(lang) },
      { role: "user", content: "CALLS: " + JSON.stringify(calls) },
    ],
    temperature: 0,
    max_tokens: 700,
    response_format: { type: "json_schema", json_schema: ACTIONS_SCHEMA },
  }, 15000);
  if (!r.text) return json({ ok: false, error: "unavailable", tried: r.tried, ms: Date.now() - t0 }, 503);
  let raw = null;
  try {
    raw = JSON.parse(r.text);
  } catch {
    return json({ ok: false, error: "parse", ms: Date.now() - t0 }, 502);
  }
  const actions = sanitizeActions(raw, calls);
  console.log(JSON.stringify({ event: "call_actions", lang, calls: calls.length, actions: actions.length, model: r.model, ms: Date.now() - t0 }));
  return json({ ok: true, actions, processed: calls.map((c) => c.id), language: lang, model: r.model, ms: Date.now() - t0 });
}
