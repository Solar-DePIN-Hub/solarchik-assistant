/** Phone builds have no API key. They call the desk host, which holds the key. */

export const DESK_ORIGIN = "https://solarchik-desk.davidbell1603.workers.dev";
/**
 * 1.1.3: the desk token is no longer in the source (the leaked value was rotated on the desk worker).
 * Server code reads the server env DESK_TOKEN; browser and native bundles get none (the desk answers 401).
 */
export const DESK_TOKEN: string =
  import.meta.env.VITE_NATIVE === "1" || typeof process === "undefined" ? "" : (process.env.DESK_TOKEN ?? "").trim();

export function deskHeaders(): Record<string, string> {
  return {
    "content-type": "application/json",
    "x-desk-token": DESK_TOKEN,
  };
}

export async function grokChat(body: unknown, ms: number): Promise<Response> {
  const key = (() => {
    if (import.meta.env.VITE_NATIVE === "1" || typeof process === "undefined") return "";
    const value = process.env.XAI_API_KEY;
    return typeof value === "string" ? value.trim() : "";
  })();
  if (key) {
    return fetch("https://api.x.ai/v1/chat/completions", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${key}`,
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(ms),
    });
  }
  return fetch(`${DESK_ORIGIN}/api/grok`, {
    method: "POST",
    headers: deskHeaders(),
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(ms),
  });
}

export async function deskRelay(method: "GET" | "POST", path: string, body: string): Promise<Response> {
  return fetch(`${DESK_ORIGIN}/api/poly`, {
    method: "POST",
    headers: deskHeaders(),
    body: JSON.stringify({ method, path, body }),
    signal: AbortSignal.timeout(12_000),
  });
}
