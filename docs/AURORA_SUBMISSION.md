# Encode Solana Hackathon 2026: Aurora Intents bounty submission (draft text)

**Project:** Sol: Pay from any chain (Aurora Intents for Solana payment requests)
**Category:** consumer / payments
**Repo:** https://github.com/Solar-DePIN-Hub/solarchik-assistant (branch `hackathons/encode-aurora`). Technical docs: [docs/ANYCHAIN.md](ANYCHAIN.md)
**Live:** https://sol-anychain.davidbell1603.workers.dev (paid example: `/r/wh6yhcdkke`)

## Short description

Sol is an Android AI secretary that answers Vadym's missed calls and turns "you owe me" into a Solana Pay request. That only works if the friend already has USDC or SOL on Solana. Vadym built "Pay from any chain" on the Aurora Intents Swap API. Every USDC or SOL request now also gets a pay page. On it, the friend picks what they actually hold (USDC on Base, USDT on Tron, ETH, BTC, NEAR and about 20 more networks), gets one deposit address and an exact amount, and pays from any wallet. The requester receives exactly the requested amount on Solana mainnet (EXACT_OUTPUT). Short or late deposits and any extra go back to the payer's own address. The app marks the request Paid ✓ only after it reads the Solana transaction itself.

## Aurora products used

Swap API: `GET /api/tokens` (source list), `POST /api/quote` (EXACT_OUTPUT, ORIGIN_CHAIN deposit, refund to the payer's origin address), `POST /api/deposit/submit` (optional speed-up), `GET /api/status` (settlement, refunds, destination tx hashes) and `GET /api/incidents` (chain warnings before quoting).

## Live proof (real mainnet payment, 23 Oct 2026)

Vadym paid a 1 USDC request from his own Phantom wallet on Base to his own Solana wallet. He confirmed the transfer in Phantom himself; the app and server never sign anything.

| Step | Time (Kyiv, UTC+3) | Amount | Tx |
| --- | --- | --- | --- |
| Deposit on Base (payer → Aurora deposit address `0xF9EC…ecA4`) | 18:01:01 | 1.3 USDC (quote was 1.289655) | [Basescan](https://basescan.org/tx/0x7567fb869ba779fe14b1296e7813559937b6c1f8f8d4c8118b04634967cba21f) |
| Refund of the extra to the payer's Base address | 18:01:25 | 0.018337 USDC | [Basescan](https://basescan.org/tx/0xdd674f6c89ef6f765f5c888cc4126287345b289d29260344a65df82fb83fed8e) |
| Delivery on Solana to `8J3h…67ic` | 18:01:33 | exactly 1 USDC | [Solscan](https://solscan.io/tx/5CiB7UPzktqCM4TpZSdKpvbYTuhBYkVgKxXYC2nxesqRywU1j3mqMhqXsyH5rGYGYo5CFyGwvoFp57UsF7gJxaXH) |

- About 32 seconds from the Base deposit to USDC on Solana.
- Net cost: 1.281663 USDC for 1 USDC delivered (about 0.28 USDC of fixed withdraw fee, spread and the 2 bps Studio fee), plus about $0.001 of Base gas.
- The receiving wallet had no USDC token account. The Solana delivery transaction created it, and the relayer paid the 0.00148844 SOL rent. The recipient needed no SOL.
- Paid ✓ was confirmed three ways: the Worker's status endpoint (`SUCCESS`, tx hashes from Aurora), Base receipts from `mainnet.base.org`, and the recipient's USDC balance on Solana RPC.
- Screenshot of the paid page: [docs/evidence/aurora-paid-wh6yhcdkke.png](evidence/aurora-paid-wh6yhcdkke.png)

## Settlement and refund handling

EXACT_OUTPUT with clear min/max rules on the page, a 30-minute window (90 minutes for slow chains), and expired quotes are blocked with a one-tap fresh quote. A request that is already paid refuses new quotes. Paying Solana USDC to a USDC request is redirected to plain Solana Pay. Refund addresses are checked for each chain and never shown publicly. Each Aurora status is shown in plain language (including INCOMPLETE_DEPOSIT and REFUNDED with amount and reason). The live run also showed the over-payment refund working (0.018337 USDC back in 24 s).

## Honest limits

Mainnet only (no devnet path), so testing moves real money. Small amounts are expensive because of the fixed ~0.28 USDC withdraw fee. No Intents Connect or Confidential Intents yet. Creating a pay page is unauthenticated but rate-limited per IP.

## Demo notes (for the video)

- Show the pay page `/r/<id>` → Base / USDC → refund address → "Get the exact amount" → the deposit panel (amount, deadline, QR, transfer requirements).
- Show the Phantom send of USDC on Base, then the page switching to **Paid ✓ 1.0 USDC arrived on Solana** and the "View the Solana transaction" link.
- Close-up proof: the three tx links above (Basescan deposit, Basescan refund, Solscan delivery that also creates the token account).
- Narration uses "Vadym built…". Never say it was filmed on a Seeker.
