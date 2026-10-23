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

## Live payment, end to end (23 Oct 2026, mainnet, real money)

One real payment through the deployed Worker, paid by Vadym from his own Phantom wallet on Base, to his own Solana wallet. Nothing was signed by the app or the server; Vadym confirmed the transfer in Phantom himself.

- Pay page: https://sol-anychain.davidbell1603.workers.dev/r/wh6yhcdkke (request for **1 USDC** on Solana to `8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic`). The page now shows **Paid ✓ 1.0 USDC arrived on Solana**.
- Quote (USDC on Base, EXACT_OUTPUT): send 1.289655 USDC, deposit address `0xF9ECbaD16bE93a7Fe25357B5BEfCF3E949EcecA4`, 30-minute window.
- **Base deposit** (18:01:01 Kyiv): 1.3 USDC from `0x148c…c2e1` to the deposit address. Phantom rounded the amount up to 1.3, so it was slightly above the quote. Tx [0x7567fb86…cba21f](https://basescan.org/tx/0x7567fb869ba779fe14b1296e7813559937b6c1f8f8d4c8118b04634967cba21f). Base gas: 0.00000045 ETH (about $0.001).
- **Refund of the extra** (18:01:25 Kyiv, 24 s later): Aurora sent 0.018337 USDC back to the payer's own Base address, the refund address given on the page. Tx [0xdd674f6c…3fed8e](https://basescan.org/tx/0xdd674f6c89ef6f765f5c888cc4126287345b289d29260344a65df82fb83fed8e).
- **Solana settlement** (18:01:33 Kyiv, 32 s after the deposit): exactly 1 USDC (`transferChecked`, 1,000,000 units of mint EPjF…Dt1v) to the requester. Tx [5CiB7UPz…7gJxaXH](https://solscan.io/tx/5CiB7UPzktqCM4TpZSdKpvbYTuhBYkVgKxXYC2nxesqRywU1j3mqMhqXsyH5rGYGYo5CFyGwvoFp57UsF7gJxaXH). This hash came from Aurora's `GET /api/status` (`destinationChainTxHashes`). The Worker stored it, and we re-read the transaction on Solana mainnet RPC.
- **Token account creation was covered by the bridge side.** The receiving wallet had no USDC account before. The same Solana transaction creates its associated token account (`14crdz7G995umvSC6q1vBKsdpgxXAknCYB3EKudisW6z`). The relayer `DuSs7rCr…gkbn` paid the 0.00148844 SOL rent and the tx fee. The requester paid nothing on Solana and needed no SOL.
- **Cost:** 1.281663 USDC net from Base (1.3 sent − 0.018337 refunded) for 1 USDC delivered on Solana. That is about 0.28 USDC: the fixed Solana withdraw fee shown in every quote, plus spread and the 2 bps Studio fee. Plus about $0.001 of Base gas.
- **Checks done independently:** Worker `GET /anychain/status?id=wh6yhcdkke` returns `status: paid`, `SUCCESS`, the two Base txs and the Solana tx. Base receipts were read from `mainnet.base.org` (both status 1, USDC Transfer logs as above). Solana `getTokenAccountsByOwner` for `8J3h…67ic` now shows 1 USDC, up from no account before.

## Live check (23 Oct 2026, mainnet, no money moved)

The Swap API key is set as a Cloudflare secret on `sol-anychain` (never in git). Quotes are live.

- Test pay page: https://sol-anychain.davidbell1603.workers.dev/r/6aedyvu8t4 ("TEST - DO NOT PAY", 1 USDC to Solana). Choosing USDC on Base gave a real deposit address, a QR code, "Send exactly 1.286456 USDC (≈ $1.29)", a minimum of 1.276353 and a 30-minute send window. The status poll goes through Aurora (`PENDING`, "Waiting for your transfer"). The refund address on that quote is a dummy, so nobody should pay it.
- Fees seen: a fixed ~0.28 USDC Solana withdraw fee (already inside the quote), plus a small spread and the 2 bps app fee and referral that Intents Studio adds to every quote for this key. 1 USDC out costs ~1.29 USDC on Base, Arbitrum or Ethereum; 2 USDC costs ~2.30.
- Minimums: none seen for USDC on Base, Arbitrum or Ethereum (a 0.01 USDC quote works, but the fixed fee makes small amounts expensive). Other bridges have floors: USDT on Tron needs at least ~1.62 USDC out, BTC ~6.49 USDC out. The page turns Aurora's "Amount is too low" error into a plain message with that minimum.
- A finding: Aurora's `quote.deadline` came back as our requested deadline + 72 h. The page shows the earlier of the two (`earliest()` in `aurora-intents.js`, with a test), so the payer sees the 30-minute window we asked for.
- Active chain incidents with no public text now get a plain fallback line ("<Chain> has an active incident; transfers may be delayed.").

## Honest limits

- Aurora Intents runs on mainnet only, so a real end-to-end test moves real money. There is no devnet path. One real 1 USDC payment was made on 23 Oct (see above).
- Creating a pay page is unauthenticated (rate-limited per IP). The page shows the Solana address the money goes to and "only pay people you know".
- Persistent deposit addresses (one reusable address per contact) need Aurora's approval; not used.
- No Intents Connect (contract-level composability) or Confidential Intents yet.
