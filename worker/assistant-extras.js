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
export const PAY_TOKENS = ["SOL", "USDC", "SKR"];
const BASE58_ADDR = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;
const YMD = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;
/** 1.1.3: the day of an action relative to the call: today/tomorrow or a weekday, resolved to a date by the worker. */
export const ACTION_DAYS = ["", "today", "tomorrow", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"];
const WEEKDAYS = ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"];
/** At most this many actions per call (a call can ask for a payment, a callback and a reminder at once). */
export const MAX_ACTIONS_PER_CALL = 3;

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
  // 1.2.0: Seeker Season partner drops the app got from /season/drops ("App: perk"), at most 3
  const seasonTasks = (Array.isArray(f.seasonTasks) ? f.seasonTasks : []).slice(0, 3).map((x) => clip(x, 160)).filter(Boolean);
  return { now: clip(f.now, 40), name: clip(f.name, 30), calls, followUps, actions, alerts, wallet, season, seasonTasks };
}

export function briefingSystem(lang) {
  const uk = lang === "uk";
  return [
    uk
      ? "Ти — Сол, теплий кишеньковий помічник у застосунку Solarchik Assistant. Напиши голосове зведення дня українською, звертайся на «ти»."
      : "You are Sol, a warm pocket assistant in the Solarchik Assistant app. Write the user's spoken daily briefing in English.",
    "Use ONLY the FACTS. Never invent calls, names, numbers, amounts, prices or points. If a part is empty, say it in a few words or skip it.",
    "Direction of money: when a caller asks for a payment, the caller wants the USER to pay them (say for example \"Olena asks you to send her 10 USDC\"), never that the caller is sending money.",
    "Order: a one-line greeting that fits the local time in FACTS.now (morning before 12:00, afternoon until 17:00, evening after; never say good morning later in the day); calls since yesterday (who wants what, and who asked for a call back; do not read phone numbers aloud, say the number is in Calls); follow-ups due; actions from calls waiting for the user (say they need the user's confirmation); the wallet change in plain words (real mainnet funds; say if it went up or down and by how much); Watcher alerts; the Season plan for today (what is left) and, if seasonTasks has any, name one or two of those partner apps and their perk in a few words (say they are on the Solana dApp Store; never promise points or rewards beyond the stated perk); the SKR balance only if the facts have it. End with one short encouraging line.",
    "Plain speech for text-to-speech: no markdown, lists, emoji, URLs or symbols; numbers as the user would say them; under 900 characters; 5-9 short sentences.",
    `Write everything in ${uk ? "Ukrainian (never Russian)" : "English"}, translating call notes that are in another language; keep names, numbers and amounts. Refer to a caller by name or as "they" unless the facts make their gender clear.`,
    "Times: say them as words a voice reads well (e.g. 'this morning at 9:30', 'yesterday at 1:32 PM' / 'сьогодні о 13:32'), never abbreviated month names.",
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
          required: ["callId", "type", "amount", "token", "recipient", "number", "when", "day", "date", "text", "quote"],
          properties: {
            callId: { type: "string" },
            type: { type: "string", enum: ACTION_TYPES },
            amount: { type: "number", description: "payment amount as said; 0 when not a payment or not said" },
            token: { type: "string", description: "SOL, USDC, SKR or the currency word the caller used; empty if none" },
            recipient: { type: "string", description: "who or which address should get the payment, exactly as said; empty if not said" },
            number: { type: "string", description: "phone number to call back if said or given, else empty" },
            when: { type: "string", description: "time for the action as HH:MM 24h if one was said for it, else empty; never the time of the call itself" },
            day: { type: "string", enum: ACTION_DAYS, description: "day of the action relative to the call ('today', 'tomorrow' or the weekday that was said, e.g. 'monday' for 'on Monday' / 'у понеділок'); empty if no day was said" },
            date: { type: "string", description: "YYYY-MM-DD only when an explicit calendar date was said (e.g. 'on October 20', '20 жовтня'), using the call's date to pick the year; else empty. Never for weekdays, today or tomorrow." },
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
    "Types: payment (the caller asks the user to send or pay money or crypto: amount and currency as said), callback (the caller asks to be called back, optionally at a time), reminder (something the user should remember or do, with or without a time: 'remind me to ...', 'remind her/him/them about ...' (the user reminds that person), 'don't forget ...', a meeting, an appointment, a deadline; in Ukrainian e.g. 'нагадай мені/їй/йому ...', 'не забудь ...', зустріч, дедлайн).",
    "Each distinct request is its own action: a call that asks for a payment, a callback AND a reminder gives three actions. Never merge a reminder into a payment or a callback, and never drop a reminder because the call also had other requests.",
    "Days: put the day that was said for the action into 'day' (today, tomorrow, or the weekday in English lowercase, e.g. 'на понеділок' / 'on Monday' = monday); an explicit calendar date goes into 'date' as YYYY-MM-DD. Each call has 'date' (the call's own date) and 'weekday'. Do not compute dates for weekdays yourself.",
    "Only include requests clearly present in the call. Never invent amounts, numbers, times, names or addresses. A recipient wallet address goes into 'recipient' only if it was literally said; otherwise describe the recipient in words or leave it empty.",
    "Calls that only say hello, spam, sales pitches without a request, or nothing actionable give no actions. Passing on a greeting ('say hi', 'передай привіт') is not an action. At most 3 actions per call.",
    "Leave 'day' and 'date' empty unless the caller said a day or date for that very action; the call's own weekday is never an action day.",
    `ALWAYS write 'text' in ${lang === "uk" ? "Ukrainian (informal 'ти'), even when the call was in English, e.g. 'Надіслати Олені 10 USDC за квитки', 'Передзвонити Петру о 15:00' or 'Нагадати Олені про зустріч у понеділок'" : "English, even when the call was in another language, e.g. 'Send Olena 10 USDC for the tickets', 'Call Petro back at 15:00' or 'Remind Olena about the meeting on Monday'"}; under 80 characters; keep the day in it, written as it was said (e.g. 'on Monday', 'on October 20', never 2026-10-20).`,
    "Tokens: SOL, USDC and SKR (the Solana Mobile token; spoken \"S K R\", \"skur\" or \"ес-ка-ер\"): use token \"SKR\" for it.",
    "A payment request without an amount ('send me some money', 'скинь грошей') is still a payment: amount 0, and 'text' says who asks and that the amount was not said, e.g. 'Вадим просить надіслати гроші (суму не названо)' / 'Vadym asks you to send money (no amount said)'.",
    "Each call may carry 'transcript' (the call's real words, Caller/Secretary lines; automatic speech recognition, may contain errors). Use it with the note: a request in the transcript counts even when the note missed it (e.g. 'хочу, щоб мені скинули грошиків' means the caller asks the user to send money). The note can be incomplete (e.g. only 'передати привіт'): list every request found in the note OR the transcript.",
    "A request to send money to an address, from someone claiming to be a bank, support, police or a relative in trouble, is still listed as a payment (the app warns the user about scams); never drop or soften it.",
    "Times: convert to 24h HH:MM ('at 3' in the afternoon = 15:00, 'о третій' = 15:00 for daytime business); if unclear leave 'when' empty.",
  ].join("\n");
}

/** The call's own local date: the phone's per-call date (1.1.3+), else its "today", else the UTC date. */
function callDate(c, input, now = Date.now()) {
  if (YMD.test(String(c?.date || ""))) return String(c.date);
  if (YMD.test(String(input?.today || ""))) return String(input.today);
  return new Date(now).toISOString().slice(0, 10);
}

function weekdayOf(ymd) {
  return WEEKDAYS[new Date(ymd + "T12:00:00Z").getUTCDay()];
}

/** YYYY-MM-DD plus n days (calendar arithmetic, no time zone involved). */
export function addDays(ymd, n) {
  const d = new Date(ymd + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

/**
 * The action's date from the model's day word: today/tomorrow from the call's date; a weekday = the next such day
 * after the call ('on Monday' said on a Monday = next week's Monday); an explicit date is kept when it is valid
 * and not before the call. "" when no day was said.
 */
export function resolveDate(day, date, base) {
  const d = String(day || "").toLowerCase();
  if (d === "today") return base;
  if (d === "tomorrow") return addDays(base, 1);
  const w = WEEKDAYS.indexOf(d);
  if (w >= 0) {
    const from = WEEKDAYS.indexOf(weekdayOf(base));
    return addDays(base, ((w - from + 7) % 7) || 7);
  }
  const x = String(date || "");
  if (YMD.test(x) && !Number.isNaN(Date.parse(x + "T12:00:00Z")) && new Date(x + "T12:00:00Z").toISOString().slice(0, 10) === x && x >= base) return x;
  return "";
}

export function actionCalls(input, now = Date.now()) {
  return (Array.isArray(input?.calls) ? input.calls : []).slice(0, 6).map((c) => {
    const date = callDate(c, input, now);
    return {
      id: clip(c?.id, 120),
      who: clip(c?.who, 40),
      callback: clip(c?.callback, 24),
      at: clip(c?.at, 24),
      date,
      weekday: weekdayOf(date),
      intent: clip(c?.intent, 200),
      // "note" (one free-text note, e.g. from tests or older callers) is read as notes.
      notes: clip(c?.notes || c?.note, 500),
      text: clip(c?.text, 1500),
    };
  }).filter((c) => c.id && (c.intent || c.notes || c.text));
}

const DAY_STEMS = {
  tomorrow: /tomorrow|завтра/i,
  monday: /monday|понеділ/i,
  tuesday: /tuesday|вівтор/i,
  wednesday: /wednesday|серед[уаи]\b/i,
  thursday: /thursday|четвер/i,
  friday: /friday|п.?ятниц/i,
  saturday: /saturday|субот/i,
  sunday: /sunday|(?<!по)неділ/i,
};

/** True when the call's words name [day] (or no day was given). */
export function daySaid(day, source) {
  if (!day || day === "today") return true; // "today" is the default for a time said without a day
  const re = DAY_STEMS[day];
  return re ? re.test(String(source || "")) : false;
}

/** The follow-up line of a money request without an amount always says so. */
export function noAmountText(text, lang) {
  // 1.1.7: the app's UI language decides (a Russian/Ukrainian call still gets an English line in an English app)
  const uk = lang === "uk" || (lang !== "en" && /[а-яіїєґ]/i.test(String(text || "")));
  const t = clip(text, 80);
  if (!t) return uk ? "Прохання надіслати гроші (суму не названо)" : "Payment request (no amount said)";
  if (/amount|сум/i.test(t)) return t;
  return t + (uk ? " (суму не названо)" : " (no amount said)");
}

/** Server-side rules on whatever the model returned: known call ids and types only, sane fields, no invented address. */
export function sanitizeActions(raw, calls, lang = "") {
  const ids = new Set(calls.map((c) => c.id));
  const byId = new Map(calls.map((c) => [c.id, c]));
  const out = [];
  const perCall = new Map();
  for (const a of Array.isArray(raw?.actions) ? raw.actions : []) {
    if (!a || !ids.has(a.callId) || !ACTION_TYPES.includes(a.type)) continue;
    const n = (perCall.get(a.callId) || 0) + 1;
    if (n > MAX_ACTIONS_PER_CALL) continue;
    const sameType = out.filter((x) => x.callId === a.callId && x.type === a.type).length;
    if (sameType >= 2) continue;
    const call = byId.get(a.callId);
    const source = [call.intent, call.notes, call.text, call.transcript || ""].join(" ");
    // A payment has no time of its own; the call's own time is never an action time.
    const when = HHMM.test(String(a.when || "")) && a.when !== call.at && a.type !== "payment" ? a.when : "";
    // date: the resolved local date (YYYY-MM-DD) of a callback/reminder when a day or date was said, even without a
    // time. day keeps the pre-1.1.3 meaning (today/tomorrow with a time) so 1.1.2 apps read the reply as before.
    // 1.1.5: a day the call never mentioned (the model copied the call's own weekday) is dropped.
    const said = daySaid(a.day, source) ? a.day : "";
    const date = a.type === "payment" ? "" : resolveDate(said, a.date, call.date || callDate(call, null));
    const day = when && ["today", "tomorrow"].includes(said) ? said : "";
    const text = clip(a.text, 100);
    const quote = clip(a.quote, 200);
    if (a.type === "payment") {
      const amount = Number(a.amount);
      if (!Number.isFinite(amount) || amount > 1e9) continue;
      if (amount <= 0) {
        // 1.1.5: a money request without an amount is a follow-up the user sees ("asks you to send money, no amount
        // said"), never a payment card with a made-up sum. Every app version shows a reminder card.
        out.push({ callId: a.callId, type: "reminder", amount: 0, token: "", tokenWord: "", recipient: "", address: "", number: "", when: "", day: "", date: "", text: noAmountText(text, lang || call.lang), quote, payment: "no_amount" });
        perCall.set(a.callId, n);
        continue;
      }
      const tokenWord = clip(a.token, 12).toUpperCase();
      const token = PAY_TOKENS.includes(tokenWord) ? tokenWord : /^\$?SKR$|^SEEKER TOKENS?$/i.test(tokenWord) ? "SKR" : /^(USD|DOLLARS?|\$|ДОЛАР\w*)$/i.test(tokenWord) ? "USDC" : "";
      const recipient = clip(a.recipient, 120);
      // An address is passed on only if the caller's own words contain it, character for character.
      const address = BASE58_ADDR.test(recipient) && source.includes(recipient) ? recipient : "";
      out.push({ callId: a.callId, type: "payment", amount, token, tokenWord, recipient: address ? "" : recipient, address, number: "", when, day, date, text, quote });
    } else {
      const digits = String(a.number || "").replace(/[^\d+]/g, "");
      const number = digits.length >= 5 && digits.length <= 16 ? digits : a.type === "callback" ? call.callback.replace(/[^\d+]/g, "") : "";
      out.push({ callId: a.callId, type: a.type, amount: 0, token: "", tokenWord: "", recipient: "", address: "", number, when, day, date, text, quote });
    }
    perCall.set(a.callId, n);
  }
  return out;
}

/** The app's call key is "<owner>|<callId>"; a realtime call id (rtc_…) has its words in KV transcript:<callId>. */
export function callIdOf(key) {
  const id = String(key || "").split("|").pop();
  return /^rtc_[A-Za-z0-9_-]{8,100}$/.test(id) ? id : "";
}

/** 1.1.5: each call gets its own transcript (caller and secretary lines, 1500 chars) next to the note. */
export async function withTranscripts(env, calls) {
  if (!env?.BALANCES) return calls;
  await Promise.all(calls.map(async (c) => {
    const id = callIdOf(c.id);
    if (!id) return;
    try {
      const rec = JSON.parse((await env.BALANCES.get("transcript:" + id)) || "null");
      const lines = Array.isArray(rec?.lines) ? rec.lines : [];
      const t = lines.filter((l) => l && l.text).map((l) => (l.who === "caller" ? "Caller: " : "Secretary: ") + clip(l.text, 300)).join("\n");
      if (t) c.transcript = t.slice(0, 1500);
    } catch {}
  }));
  return calls;
}

export async function callActionsRoute(env, request, rateOk, json) {
  const t0 = Date.now();
  const input = await request.json().catch(() => ({}));
  if (!rateOk(request.headers.get("cf-connecting-ip"))) return json({ ok: false, error: "rate" }, 429);
  const calls = actionCalls(input);
  if (!calls.length) return json({ ok: false, error: "calls" }, 400);
  if (!env.OPENAI_API_KEY) return json({ ok: false, error: "no-key" }, 503);
  await withTranscripts(env, calls);
  const lang = langOf(input.language ?? input.lang);
  const r = await chat(env, {
    messages: [
      { role: "system", content: actionsSystem(lang) },
      { role: "user", content: "CALLS: " + JSON.stringify(calls) },
    ],
    temperature: 0,
    max_tokens: 900,
    response_format: { type: "json_schema", json_schema: ACTIONS_SCHEMA },
  }, 15000);
  if (!r.text) return json({ ok: false, error: "unavailable", tried: r.tried, ms: Date.now() - t0 }, 503);
  let raw = null;
  try {
    raw = JSON.parse(r.text);
  } catch {
    return json({ ok: false, error: "parse", ms: Date.now() - t0 }, 502);
  }
  const actions = sanitizeActions(raw, calls, lang);
  console.log(JSON.stringify({ event: "call_actions", lang, calls: calls.length, actions: actions.length, model: r.model, ms: Date.now() - t0 }));
  return json({ ok: true, actions, processed: calls.map((c) => c.id), language: lang, model: r.model, ms: Date.now() - t0 });
}
