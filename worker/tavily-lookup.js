// Optional Tavily web check for "payment" cards (someone asks the user to pay).
// Only runs when TAVILY_API_KEY is set. It searches the *request pattern* (not the caller's phone number or name),
// so the user can see whether this kind of request is a known scam before tapping Pay. Never blocks the cards.
const TAVILY_URL = "https://api.tavily.com/search";

export function lookupQuery(card) {
  const what = String(card.text || "").replace(/\s+/g, " ").trim().slice(0, 140);
  return what ? `Is this a scam: "${what}"` : "";
}

export async function addPaymentChecks(env, actions, fetchImpl = fetch, { max = 2, timeoutMs = 6000 } = {}) {
  if (!env?.TAVILY_API_KEY) return actions;
  const targets = actions.filter((a) => a.type === "payment" && lookupQuery(a)).slice(0, max);
  await Promise.all(targets.map(async (card) => {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const r = await fetchImpl(TAVILY_URL, {
        method: "POST",
        headers: { "content-type": "application/json", authorization: `Bearer ${env.TAVILY_API_KEY}` },
        body: JSON.stringify({ query: lookupQuery(card), search_depth: "basic", max_results: 3, include_answer: true }),
        signal: ctrl.signal,
      });
      if (!r.ok) return;
      const j = await r.json();
      card.check = {
        source: "tavily",
        answer: String(j.answer || "").slice(0, 400),
        links: (Array.isArray(j.results) ? j.results : []).slice(0, 3).map((x) => ({ title: String(x.title || "").slice(0, 120), url: String(x.url || "") })).filter((x) => /^https?:\/\//.test(x.url)),
      };
    } catch { /* timeout or network: the card stays as it is */ } finally { clearTimeout(timer); }
  }));
  return actions;
}
