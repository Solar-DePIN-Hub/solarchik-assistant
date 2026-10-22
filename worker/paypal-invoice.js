// PayPal AI Hackathon: an "owed" action card ("Andrii owes you $200") -> PayPal invoice with a pay link.
// Sandbox by default. Nothing is sent without the user's tap in the app; this route is only called on that tap.
// Env: PAYPAL_ENABLED=1, PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET (secrets), optional PAYPAL_ENV=live (default sandbox),
//      PAYPAL_CURRENCY (default USD).
export function paypalBase(env) {
  return env.PAYPAL_ENV === "live" ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";
}

export async function paypalToken(env, f = fetch) {
  const res = await f(paypalBase(env) + "/v1/oauth2/token", {
    method: "POST",
    headers: { Authorization: "Basic " + btoa(env.PAYPAL_CLIENT_ID + ":" + env.PAYPAL_CLIENT_SECRET), "Content-Type": "application/x-www-form-urlencoded" },
    body: "grant_type=client_credentials",
  });
  const j = await res.json().catch(() => ({}));
  if (!res.ok || !j.access_token) throw new Error("paypal-auth " + res.status);
  return j.access_token;
}

const clip = (v, n) => String(v ?? "").replace(/\s+/g, " ").trim().slice(0, n);

/** Validated invoice input from the app: amount > 0, a description, optional payer email. */
export function invoiceInput(b) {
  const amount = Number(b?.amount);
  if (!Number.isFinite(amount) || amount <= 0 || amount > 10000) return null;
  const email = clip(b?.payerEmail, 120);
  return {
    amount: amount.toFixed(2),
    currency: /^[A-Z]{3}$/.test(String(b?.currency || "")) ? b.currency : "",
    note: clip(b?.text || "Payment requested after our call", 120),
    payerName: clip(b?.payerName, 60),
    payerEmail: /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) ? email : "",
    callId: clip(b?.callId, 120),
  };
}

export async function createInvoice(env, inp, f = fetch) {
  const token = await paypalToken(env, f);
  const H = { Authorization: "Bearer " + token, "Content-Type": "application/json", Prefer: "return=representation" };
  const currency = inp.currency || env.PAYPAL_CURRENCY || "USD";
  const body = {
    detail: { currency_code: currency, note: "Created by Sol, the AI call secretary, from a phone call.", memo: inp.callId },
    items: [{ name: inp.note, quantity: "1", unit_amount: { currency_code: currency, value: inp.amount } }],
  };
  if (inp.payerEmail) body.primary_recipients = [{ billing_info: { email_address: inp.payerEmail, name: inp.payerName ? { full_name: inp.payerName } : undefined } }];
  const cr = await f(paypalBase(env) + "/v2/invoicing/invoices", { method: "POST", headers: H, body: JSON.stringify(body) });
  const inv = await cr.json().catch(() => ({}));
  if (!cr.ok || !inv.id) throw new Error("paypal-create " + cr.status);
  // Send without emailing (send_to_recipient=false) so the user shares the link themselves.
  const sr = await f(paypalBase(env) + "/v2/invoicing/invoices/" + inv.id + "/send", {
    method: "POST", headers: H, body: JSON.stringify({ send_to_recipient: Boolean(inp.payerEmail), send_to_invoicer: false }),
  });
  const sent = await sr.json().catch(() => ({}));
  if (!sr.ok) throw new Error("paypal-send " + sr.status);
  const payLink = sent?.href || (sent?.links || []).find((l) => l.rel === "payer-view")?.href || "";
  return { id: inv.id, amount: inp.amount, currency, payLink };
}

export async function paypalInvoiceRoute(env, request, rateOk, json, f = fetch) {
  if (env.PAYPAL_ENABLED !== "1") return json({ ok: false, error: "disabled" }, 404);
  if (!rateOk(request.headers.get("cf-connecting-ip"))) return json({ ok: false, error: "rate" }, 429);
  if (!env.PAYPAL_CLIENT_ID || !env.PAYPAL_CLIENT_SECRET) return json({ ok: false, error: "no-key" }, 503);
  const inp = invoiceInput(await request.json().catch(() => ({})));
  if (!inp) return json({ ok: false, error: "amount" }, 400);
  try {
    const r = await createInvoice(env, inp, f);
    return json({ ok: true, sandbox: env.PAYPAL_ENV !== "live", ...r });
  } catch (e) {
    return json({ ok: false, error: String(e.message || e) }, 502);
  }
}
