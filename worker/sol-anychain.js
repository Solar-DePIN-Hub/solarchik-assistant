/**
 * sol-anychain: the standalone Worker for Sol's "Pay from any chain" requests (Aurora Intents Swap API).
 * Kept apart from solarchik-screen (calls, Sol chat) so a payments change never redeploys the phone secretary.
 * Deploy: npx wrangler deploy --config worker/wrangler.anychain.toml
 */
import { handleAnychain } from "./aurora-intents.js";
import { pageHtml, qrSvg, qrPayload, PAGE_CSP } from "./anychain-page.js";

const CORS = { "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,OPTIONS", "access-control-allow-headers": "content-type" };
export const json = (o, status = 200, extra = {}) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...CORS, ...extra } });

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (request.method === "GET" && url.pathname === "/") return json({ ok: true, service: "sol-anychain", aurora: !!env.AURORA_API_KEY, docs: "https://github.com/Solar-DePIN-Hub/solarchik-assistant/blob/hackathons/encode-aurora/docs/ANYCHAIN.md" });
    if (url.pathname === "/favicon.ico") return new Response(null, { status: 204 });
    const page = url.pathname.match(/^\/r\/([a-z0-9]{6,20})$/);
    if (request.method === "GET" && page) {
      return new Response(pageHtml(page[1]), { headers: { "content-type": "text/html; charset=utf-8", "content-security-policy": PAGE_CSP, "x-content-type-options": "nosniff", "referrer-policy": "no-referrer", "cache-control": "no-store" } });
    }
    if (request.method === "GET" && url.pathname === "/anychain/qr") {
      const d = (url.searchParams.get("d") || "").slice(0, 200), c = url.searchParams.get("c") || "", a = (url.searchParams.get("a") || "").replace(/[^0-9.]/g, "").slice(0, 30);
      if (!/^[A-Za-z0-9:._-]{10,200}$/.test(d)) return json({ error: "BAD_INPUT" }, 400);
      return new Response(qrSvg(qrPayload(c, d, a)), { headers: { "content-type": "image/svg+xml", "cache-control": "public, max-age=86400", "x-content-type-options": "nosniff" } });
    }
    if (url.pathname.startsWith("/anychain/")) {
      const r = await handleAnychain(request, env, url, json);
      if (r) return r;
    }
    return json({ error: "NOT_FOUND" }, 404);
  },
};
