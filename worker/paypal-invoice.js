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
    currency: ["USD", "EUR"].includes(String(b?.currency || "")) ? b.currency : "USD",
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
  const body = await request.json().catch(() => ({}));
  // The user must have confirmed the exact amount (owed cards in SOL/SKR/UAH are never converted silently).
  if (body?.confirmed !== true) return json({ ok: false, error: "confirm" }, 400);
  const inp = invoiceInput(body);
  if (!inp) return json({ ok: false, error: "amount" }, 400);
  try {
    const r = await createInvoice(env, inp, f);
    return json({ ok: true, sandbox: env.PAYPAL_ENV !== "live", ...r });
  } catch (e) {
    return json({ ok: false, error: String(e.message || e) }, 502);
  }
}

// ------------------------------------------------------------------ paid status: poll + webhook

/** Map PayPal invoice status to the card state the app shows. */
export function cardState(status) {
  if (status === "PAID" || status === "MARKED_AS_PAID") return "paid";
  if (status === "PARTIALLY_PAID") return "partial";
  if (status === "CANCELLED" || status === "REFUNDED" || status === "MARKED_AS_REFUNDED") return "cancelled";
  return "sent";
}

export async function invoiceStatus(env, id, f = fetch) {
  const token = await paypalToken(env, f);
  const r = await f(paypalBase(env) + "/v2/invoicing/invoices/" + encodeURIComponent(id), { headers: { Authorization: "Bearer " + token } });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error("paypal-get " + r.status);
  const paid = j?.payments?.paid_amount;
  return { id, status: j.status || "", state: cardState(j.status), paid: paid ? paid.value + " " + paid.currency_code : "" };
}

/** GET /paypal/invoice/status?id=INV2-...  : the app/demo polls this (KV webhook result first, then PayPal). */
export async function paypalStatusRoute(env, request, rateOk, json, f = fetch) {
  if (env.PAYPAL_ENABLED !== "1") return json({ ok: false, error: "disabled" }, 404);
  if (!rateOk(request.headers.get("cf-connecting-ip"))) return json({ ok: false, error: "rate" }, 429);
  const id = new URL(request.url).searchParams.get("id") || "";
  if (!/^INV2(-[A-Z0-9]{4}){4}$/.test(id)) return json({ ok: false, error: "id" }, 400);
  try {
    const hook = env.BALANCES ? JSON.parse((await env.BALANCES.get("ppinv:" + id)) || "null") : null;
    if (hook?.state === "paid") return json({ ok: true, source: "webhook", ...hook });
    return json({ ok: true, source: "poll", ...(await invoiceStatus(env, id, f)) });
  } catch (e) {
    return json({ ok: false, error: String(e.message || e) }, 502);
  }
}

/** POST /paypal/webhook : INVOICING.INVOICE.PAID etc. Verified with PayPal's verify-webhook-signature (needs PAYPAL_WEBHOOK_ID). */
export async function paypalWebhookRoute(env, request, json, f = fetch) {
  if (env.PAYPAL_ENABLED !== "1" || !env.PAYPAL_WEBHOOK_ID) return json({ ok: false, error: "disabled" }, 404);
  const raw = await request.text();
  let event;
  try { event = JSON.parse(raw); } catch { return json({ ok: false, error: "json" }, 400); }
  const h = (k) => request.headers.get(k) || "";
  const token = await paypalToken(env, f);
  const v = await f(paypalBase(env) + "/v1/notifications/verify-webhook-signature", {
    method: "POST",
    headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
    body: JSON.stringify({
      auth_algo: h("paypal-auth-algo"), cert_url: h("paypal-cert-url"), transmission_id: h("paypal-transmission-id"),
      transmission_sig: h("paypal-transmission-sig"), transmission_time: h("paypal-transmission-time"),
      webhook_id: env.PAYPAL_WEBHOOK_ID, webhook_event: event,
    }),
  });
  const vj = await v.json().catch(() => ({}));
  if (vj.verification_status !== "SUCCESS") return json({ ok: false, error: "signature" }, 401);
  const inv = event?.resource?.invoice || event?.resource || {};
  const id = String(inv.id || "");
  if (/^INV2/.test(id) && env.BALANCES) {
    const rec = { id, status: inv.status || "", state: cardState(inv.status), event: event.event_type || "", at: Date.now() };
    await env.BALANCES.put("ppinv:" + id, JSON.stringify(rec), { expirationTtl: 60 * 60 * 24 * 90 });
  }
  return json({ ok: true });
}
