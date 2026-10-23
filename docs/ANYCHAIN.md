# Pay from any chain (Aurora Intents)

Docs: https://docs.intents.aurora.dev. Built for the Encode Club Solana Hackathon 2026 (Aurora Intents bounty: "Bring any-chain liquidity to Solana"), on branch `hackathons/encode-aurora`, starting 23 Oct 2026.

## The problem

Sol's Circle tab turns "you owe me" into a Solana Pay request (link + QR + SMS). That only works if the friend already has SOL or USDC in a Solana wallet. Most of the people who owe me money hold USDT on Tron, USDC on Base, ETH, or nothing on Solana at all. Before this, the request was a dead end for them.

## What it does

When I make a **USDC or SOL** request in Circle, the app also creates a pay page on a small Cloudflare Worker (`sol-anychain`). The SMS now has two links:

```
Hi Ira, here's my payment link for 5 USDC: solana:8J3h…?amount=5&spl-token=EPjF…&reference=…
No Solana wallet? Pay from any network (USDC on Base, USDT on Tron, ETH, BTC…): https://sol-anychain.davidbell1603.workers.dev/r/<id>
```

On the page, Ira:

1. picks the network and asset she holds (the list comes live from Aurora's `/api/tokens`: USDC, USDT, ETH, BTC, SOL, NEAR and more on 26 networks in the 23 Oct snapshot),
2. pastes her own address on that network (for refunds; it is checked per chain before any quote is reserved),
3. gets **one deposit address**, the **exact amount** to send, the deadline, a QR, and the transfer requirements right under the address (network and asset always shown next to the address, "1 asset supported", "sending another asset may result in loss of funds"),
4. sends from any wallet or exchange. The page polls the intent and shows *Waiting → Deposit seen → Converting → Paid ✓* with a link to the Solana transaction. Late, short or failed deposits show *Refunded* with the amount and reason.

I get **exactly** the requested amount on Solana mainnet in my own wallet. The app marks the request **Paid ✓** only after it reads that Solana transaction itself and sees my wallet gained the amount in the right mint (the server's word is never enough).

## How it uses Aurora Intents (Swap API)

| Step | Aurora call | Notes |
| --- | --- | --- |
| Source list | `GET /api/tokens/{key}` | filtered to well-known assets (USDC, USDT, ETH, BTC, SOL, NEAR, BNB, POL, AVAX, TRX, XRP, LTC, DOGE…; no meme tokens), cached 10 min |
| Quote | `POST /api/quote/{key}` | `swapType: EXACT_OUTPUT` (the requester gets the exact amount), `depositType: ORIGIN_CHAIN`, `recipientType: DESTINATION_CHAIN` (my Solana address), `refundType: ORIGIN_CHAIN` (the payer's own address), deadline 30 min (90 min for BTC/LTC/DOGE/BCH/ZEC), `slippageTolerance: 100`, `depositMode: MEMO` for Stellar, `referral: sol-solardepin`, `dry: false` |
| Speed-up | `POST /api/deposit/submit/{key}` | optional "Already sent? paste the tx hash" |
| Settlement | `GET /api/status/{key}?depositAddress=` | every status mapped to plain copy; `SUCCESS` stores `destinationChainTxHashes` (the Solana txs); `REFUNDED` / `INCOMPLETE_DEPOSIT` / `FAILED` show amount and reason; final deposits are not polled again |
| Heads-up | `GET /api/incidents/{key}` | active incidents for the chosen network are shown before quoting |

Settlement and refund handling:

- EXACT_OUTPUT gives `minAmountIn`/`maxAmountIn`: the page says that less is refunded and anything above the quote is refunded.
- A quote whose deadline passed is shown as expired ("do not send to this address any more") and a fresh quote is one tap.
- A request already paid refuses new quotes (409 `ALREADY_PAID`).
- Paying from Solana USDC to a USDC request is redirected to the plain Solana Pay link (no pointless swap).
- Refund addresses are never shown back on the public page or API.
- QR codes carry only well-defined payloads (bare address; `bitcoin:`/`litecoin:`/`dogecoin:` URIs with the amount), not EIP-681, so a wallet can't pick the wrong token from a QR.

## Code

- `worker/aurora-intents.js`: the Swap API client, source filtering, quote body, status copy, request store (KV `ac:req:<id>`, 30 days), per-IP rate limit, routes `/anychain/*`.
- `worker/anychain-page.js`: the pay page (no framework, no third-party scripts, strict CSP) and server-side QR SVG (`worker/vendor/qrcode.mjs`, qrcode-generator 1.4.4, MIT).
- `worker/sol-anychain.js`: the Worker entry (`/r/<id>`, `/anychain/qr`, `/anychain/*`). Config `worker/wrangler.anychain.toml`. It is a separate Worker so the production call secretary is not redeployed.
- Android: `circle/AnyChain.kt` (create the page, read the settlement, verify the Solana tx), `circle/PayWatch.kt` (falls back to the any-chain check), `circle/SolanaPay.kt` (`validate(..., requireReference = false)` for solver-sent transfers, share text), `ui/PayRequestSheet.kt` (creates the page, Share waits for it up to 5 s, a one-line hint on the sheet).
- Tests: `worker/aurora-intents.test.mjs` (12 node tests against a fake Aurora API and the real `/api/tokens` snapshot in `worker/fixtures/aurora/`), `AnyChainTest.kt` (4 Robolectric tests: page creation, URL pinning, verified settlement including a server that lies about "paid").

## Run it

```bash
cd worker
node --test aurora-intents.test.mjs
npx wrangler kv namespace create sol-anychain-requests      # once; put the id in wrangler.anychain.toml
npx wrangler secret put AURORA_API_KEY --config wrangler.anychain.toml   # key from https://studio.aurora.dev
npx wrangler deploy --config wrangler.anychain.toml
```

Without `AURORA_API_KEY`, `/anychain/*` answers `503 AURORA_NOT_CONFIGURED` and the page offers only the Solana Pay option.

## Honest limits

- Aurora Intents runs on mainnet only, so a real end-to-end test moves real money (a few dollars). There is no devnet path.
- Creating a pay page is unauthenticated (rate-limited per IP). The page shows the Solana address the money goes to and "only pay people you know".
- Persistent deposit addresses (one reusable address per contact) need Aurora's approval; not used.
- No Intents Connect (contract-level composability) or Confidential Intents yet.
