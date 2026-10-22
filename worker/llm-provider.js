// LLM provider switch for Sol's call -> action cards step (Nebius x NVIDIA hackathon).
// LLM_PROVIDER=nebius  -> NVIDIA Nemotron on Nebius Token Factory (OpenAI-compatible API), OpenAI as fallback.
// unset / "openai"     -> previous behaviour (OpenAI only).
// Env: NEBIUS_API_KEY (secret), optional NEBIUS_BASE_URL, NEBIUS_MODEL, NEBIUS_FAST_MODEL.
export const NEBIUS_BASE_URL = "https://api.tokenfactory.nebius.com/v1";
export const NEBIUS_MODEL = "nvidia/nemotron-3-super-120b-a12b"; // reasoning for call -> actions
export const NEBIUS_FAST_MODEL = "nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B"; // fast everyday calls (briefing)
const OPENAI_URL = "https://api.openai.com/v1/chat/completions";

/** Ordered list of {url, key, model, provider} to try for a given task ("actions" | "briefing"). */
export function providerChain(env, openaiModels, task = "actions") {
  const chain = [];
  if (String(env.LLM_PROVIDER || "").toLowerCase() === "nebius" && env.NEBIUS_API_KEY) {
    const base = String(env.NEBIUS_BASE_URL || NEBIUS_BASE_URL).replace(/\/+$/, "");
    const model = task === "briefing" ? env.NEBIUS_FAST_MODEL || NEBIUS_FAST_MODEL : env.NEBIUS_MODEL || NEBIUS_MODEL;
    chain.push({ provider: "nebius", url: base + "/chat/completions", key: env.NEBIUS_API_KEY, model });
  }
  if (env.OPENAI_API_KEY) for (const model of openaiModels) chain.push({ provider: "openai", url: OPENAI_URL, key: env.OPENAI_API_KEY, model });
  return chain;
}

export function hasLlmKey(env) {
  return Boolean(env.OPENAI_API_KEY || (String(env.LLM_PROVIDER || "").toLowerCase() === "nebius" && env.NEBIUS_API_KEY));
}

/** Nemotron reasoning models may wrap output in <think>…</think> or ```json fences; keep only the answer. */
export function cleanText(t) {
  let s = String(t || "").replace(/<think>[\s\S]*?<\/think>/gi, "").trim();
  const fence = s.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
  if (fence) s = fence[1].trim();
  return s;
}
