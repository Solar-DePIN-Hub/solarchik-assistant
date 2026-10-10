> Historical: a devnet run from the 1.0.x builds, before the app moved to mainnet. Current mainnet proof is in the [README](../README.md#on-chain-proof).

# Strategy NFTs: devnet run (2026-10-02)

Real devnet transactions made by `scripts/strategy-devnet-run.ts` (branch `web-fees`) with the real server code
(`strategy.server.ts`, `mint.server.ts`, positions ledger) on a persistent PGLite database.
The server clock was never shifted. Times are UTC (Kyiv = UTC+3). Every signature below was checked with `getSignatureStatuses`.

**Sale-lock rule:** a fresh mint (strategy v1) is not locked (`su = sc`, FreezeDelegate thawed and owner-held). Every strategy change
(v2+, and a legacy asset's first save) writes `su = sc + 240 h` and freezes the NFT under the server; the server thaws only after `su`.

- Server authority (collection update authority, freeze / transfer delegate): `8eKeV2Vh7QhGHjsTgQN2m938iyeALqsGyhSiNQRJAxR3`
- Server collection: [`74Tyscw6gL9mDxvNsSSDuj6v4YHqFPCyUULirjLPeuQC`](https://explorer.solana.com/address/74Tyscw6gL9mDxvNsSSDuj6v4YHqFPCyUULirjLPeuQC?cluster=devnet)
- Treasury (5 % royalty): [`8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic`](https://explorer.solana.com/address/8J3hxf1XSYV1HKVUJtwtQtVwSvSeaAyW5RmL8EqC67ic?cluster=devnet)
- Seller (test wallet): [`u2irHRaCwjogBYjdRQK7NmmzkGtLZzUqGfsSWAqsrQq`](https://explorer.solana.com/address/u2irHRaCwjogBYjdRQK7NmmzkGtLZzUqGfsSWAqsrQq?cluster=devnet)
- Buyer (test wallet): [`Gkgpdi8M9ZzHVHB9HsqthyS7c5fVMvRbsgjE6E212yTw`](https://explorer.solana.com/address/Gkgpdi8M9ZzHVHB9HsqthyS7c5fVMvRbsgjE6E212yTw?cluster=devnet)

## Funding

| Step | Transaction |
| --- | --- |
| 0.8 SOL authority → seller | [2ojJB3Gq…SuY2](https://explorer.solana.com/tx/2ojJB3Gq6xDr9GQAUZL6SqgwJHhsizTAntQMAdeUZn2z7THvjfhUN3coKLParVeBoNp3HbEUPUbWcKi6zBszSuY2?cluster=devnet) |
| 0.8 SOL authority → buyer | [LJkdXWPY…x9zw](https://explorer.solana.com/tx/LJkdXWPYL8kSmBQzUAxq3LGdxv9W7Tzibm3iW1JFdcPCgoxRiiFN3FtsYNSzYco8LNVSYVQj3CvnCKF6F5kx9zw?cluster=devnet) |

## Lock test NFT `Lock Test BTC Windows` (proof of the 240 h lock)

Asset [`BxXZvfVhEbBDqYRYu3pK8pkBfWq6YQ3GL4QevGm1TDip`](https://core.metaplex.com/explorer/BxXZvfVhEbBDqYRYu3pK8pkBfWq6YQ3GL4QevGm1TDip?env=devnet), owner: seller. Minted under the old rule; its lock now comes from the v2 change and ends 2026-10-12 16:38 UTC. The mint-lock migration left it untouched (it has a server-signed change).

| Step | Result | Transaction |
| --- | --- | --- |
| collection setup (first mint) | ok | [5DN2JdkP…wdPh](https://explorer.solana.com/tx/5DN2JdkPAtn7dWUmrdWUDohiFwXMXjqpFxDU6rkV13BJ8jBTv5YvgoqgYWmHRDYq1aDimKuvVaifuLmo3taZwdPh?cluster=devnet) |
| co-signed mint, strategy v1 | ok | [3iToTdZK…EhP3](https://explorer.solana.com/tx/3iToTdZKs6rEnbYF8nvhDwNaNgNVemHp4Zs7GwrABRn2xM6jVa8YKXYREjb4C1mCBG6Pfy1MZmyY3cGrH96dEhP3?cluster=devnet) |
| strategy change → v2 (hash `3vsiGK9V…`), 240 h lock to 2026-10-12 16:38 UTC | ok | [FU9HaQSH…Y7p6](https://explorer.solana.com/tx/FU9HaQSHsQqU9x2s9T7HogELLk3b51i2jMdfkLFhHLzbqnEiZb6LGdt3NT6tN55HLBbqskNDyJsh65JpqwgY7p6?cluster=devnet) |
| listing while locked | refused by the server (no tx) | — |
| owner transfer while frozen | **failed on chain** (mpl-core 0x9), owner unchanged | [2kUUn4L8…N9if](https://explorer.solana.com/tx/2kUUn4L8rNN6ntfEvMawocHDqsrysdSdEhoxYbuugZuSH3wnUw1rz8Zjgdqhg9HaouA3ghvw7g5MhMJfeHTxN9if?cluster=devnet) |
| off-strategy trade (stake 0.02 > 0.015) | refused by the server | — |
| server writes results: 3 trades, win 66.7 %, PnL 0.0045 SOL, APR 7d/30d/since 10950 % | ok; judge recompute matches | [3JZbHzWk…jTqH](https://explorer.solana.com/tx/3JZbHzWkShuR54dc46frsas2zrBK6vSNPGuxwYaWoDHoZTGttkKGTghUpm8UjxxEEoR4Sk8zzwivPTh5GjwbjTqH?cluster=devnet) |
| judge faucet drip 0.02 SOL to a fresh wallet (second ask refused) | ok | [4fYWrcsC…aJnA](https://explorer.solana.com/tx/4fYWrcsC1tUaK8uogzJ89tWXGAVGdiceCRGL4jsSkn8n3ZVYz5cSXNQFrepq5d7T6SfmJT94UhNX37ZEVYbdaJnA?cluster=devnet) |

The trades behind the results are scripted entries in the server ledger (simulated prices, like browser trades); the results write is a real server-signed transaction.

## Demo Strategy NFTs (seller wallet) — listed

Minted at 16:38 UTC with their own strategy (v1) while the code still started the lock at mint. They never had a strategy change,
so under the corrected rule the server's sync (`syncAsset` → `releaseMintLock`) released that lock: a server-signed Attributes write
`su = sc` (spec hash unchanged), the normal results init (0 trades), and a server thaw that hands the freeze authority back to the owner.
Then the seller listed them (escrow: freeze + transfer delegate to the server).

| Name | Strategy | Asset | Mint | Release lock | Results init | Thaw | List | Price |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Calm Hourly BTC | crypto 60/240 min, calm, stake 0.005, band 0.35–0.65, stop 25 %, take 40 %, `allow if price >= 0.4 and price <= 0.6; deny if hour < 6` | [`sZ4R4sbU…DfVH`](https://core.metaplex.com/explorer/sZ4R4sbUtuGc8ygfmwHvG34zqL5LoSBRF5YkHDTDfVH?env=devnet) | [2G2RoFqf…dSU5](https://explorer.solana.com/tx/2G2RoFqfTnaACG3arUYGbXqngSYBgVnK6agb8LGtJ8MXHcfPcSJEeK4ZZkLZMjRcNvHvvQ9epun476B8DvB5dSU5?cluster=devnet) | [5yatn3Yb…zKFM](https://explorer.solana.com/tx/5yatn3Yb4oDDoCauJm5zodhLq4QiqbXqUDdKg8NiaYhtwyRF1cEWXsyUV4AuFWKhNYz1pRZSvpuxnmSRDe9azKFM?cluster=devnet) | [2E4dPPLh…21EB](https://explorer.solana.com/tx/2E4dPPLhvwUFPbywFFnsg7apcjL4EjDua8iAmJRVNu6iZpDKw1SGbVc8Bm57xUxVNDXgP1Ny3zmQiH2NXPE521EB?cluster=devnet) | [4P2noFsf…oS4L](https://explorer.solana.com/tx/4P2noFsfVtaLL7pnrtaR1xoFnA5DboeZErsYBCL6rCtqSDEvBK5JPFQqxeFHriHzzJXCn7wvaidwQ4AH1DTeoS4L?cluster=devnet) | [QfaYpuPf…7GJC](https://explorer.solana.com/tx/QfaYpuPfTW9rMXwzjsiPKcLir9oB9z5nBzHBAFuoETrw7VqYde3yrWq4T7mwc1VneGzb3uczeZxm15dyRov7GJC?cluster=devnet) | 0.05 SOL |
| Momentum Rider 5m | crypto 5/15 min, risky, stake 0.02, band 0.55–0.90, stop 50 %, take 120 %, `allow yes if price >= 0.6; allow no if price >= 0.6; deny if price > 0.88` | [`AV8EgTtP…WEwX`](https://core.metaplex.com/explorer/AV8EgTtPaZydbrRDZtFFUfeuDR32jB6oHuRozUhKWEwX?env=devnet) | [3NHFpwDB…JtWY](https://explorer.solana.com/tx/3NHFpwDB5KcNTC395HYtgedwdqRJL86wwrjKY99KV5takTykqyLexnzrGcdBHPYuueNPumfTLB5PcXhafNPhJtWY?cluster=devnet) | [5WarpShY…Y8v1](https://explorer.solana.com/tx/5WarpShYdAgN9DYQqsfdLrXtTkGdaMX4sQwXdTQyWGmh21URwonCwVZA3EXUVJpFs4EFTAnHgTviq9AS3W5QY8v1?cluster=devnet) | [2Zz55YXL…EdWh](https://explorer.solana.com/tx/2Zz55YXLGVHEmWhKUVNYaXGrwFtHZuv37LKKenMpp56CgkQVBsEnTRuYagfxvdXbFxZwCQXQ63tXCzWJNsF8EdWh?cluster=devnet) | [4S3F9rwf…Bd5H](https://explorer.solana.com/tx/4S3F9rwfeP1k1AK9Tni5bMpZRBX8y37yKC2ByWwzBzEeZMZbzn91ZE2RXz5q4PuBVKDvFt4ewhZd2oXYU766Bd5H?cluster=devnet) | [8nMNNV81…yWi3](https://explorer.solana.com/tx/8nMNNV81tiiou4ELEfpFWvvNsmCUTFx9LiNeQxpj5mfx4TzUkR5E4epxFdYCN5ne1BG3gCpePrXDWddjyuPyWi3?cluster=devnet) | 0.12 SOL |
| Mean Revert Scout | crypto + events 15/60 min, balanced, stake 0.01, band 0.10–0.40, stop 35 %, take 150 %, `allow if price <= 0.35; deny if price < 0.12; deny if lane = events and stake > 0.008` | [`2ijiD193…dsK7`](https://core.metaplex.com/explorer/2ijiD193pRVfhomaFtXSNuc13ki9XVwhxw7AFXL1dsK7?env=devnet) | [2BTpdJTs…uXdL](https://explorer.solana.com/tx/2BTpdJTszR4ShNKHmfEakoff8dFySwibwUS5hnJneNi7xfNbVimaTw2m8RMRjbBNgFLvUyfvKTDjqP3585bUuXdL?cluster=devnet) | [3UwL52am…3SRN](https://explorer.solana.com/tx/3UwL52amkCWY2nYxtU49yeirrTpRuQ2eyxptzJZY9n2E8YeW8stMDbGKepXuJocjufsw4vvqT7pQ1xHmQM4v3SRN?cluster=devnet) | [Cc6M9D32…SNvH](https://explorer.solana.com/tx/Cc6M9D32DdaCXiwn6TjX4pwZUZvCjXx1iuc6M2bRZ2hZ3YNk1gt5b2ZNYe8rx2AY8nWd61oBGL6at4uGgghSNvH?cluster=devnet) | [4sdpn8ZQ…DhN1](https://explorer.solana.com/tx/4sdpn8ZQgVoC7nCR4guHoKyvnLTJQH6HE4kKdPPDfKmUcJ96VyrYWUd9whELZ7mw2vkWMxA7DCoukGKMhdJsDhN1?cluster=devnet) | [3cVcTaBr…v3H4](https://explorer.solana.com/tx/3cVcTaBrAQbV8WhqkTK2ZnbnwXRNXboJo9yDBDxu31eW8PXVa114SgtMAZETrRgTSsB7XpEMGCtKzMH7zkXWv3H4?cluster=devnet) | 0.08 SOL |

## Market test NFT `Market Test` — list, buy, re-lock

Asset [`8vSSKk1Eio44Ja265FR6WvfDr2QvmBPLsrtjnoEUHjWe`](https://core.metaplex.com/explorer/8vSSKk1Eio44Ja265FR6WvfDr2QvmBPLsrtjnoEUHjWe?env=devnet).

| Step | Result | Transaction |
| --- | --- | --- |
| mint under the new rule (seller) | v1, `su = sc`, FreezeDelegate thawed / owner | [5RwMow4o…nNuE](https://explorer.solana.com/tx/5RwMow4oQJvZqc1n5SVmV8RscdGaq65ebKHeDZNFhnKSjZMkyCGcJwoNHzX2ker2kvdDiL5M8kt5GLTyAY58nNuE?cluster=devnet) |
| list at 0.02 SOL (escrow) | frozen + transfer delegate = server | [4jquQRLL…kAQw](https://explorer.solana.com/tx/4jquQRLLTLm9e9SenyprJVffuYiiqNVuzAHFKofQpCN73rk37PM1Ty4zEjYSUDqdpDJ5rymV1gZUSMHeqBowkAQw?cluster=devnet) |
| buyer buys | in the tx: seller +0.019 SOL, treasury +0.001 SOL (5 %), buyer −0.02001 SOL (incl. fee); owner = buyer | [4cJkFGAC…iSc9](https://explorer.solana.com/tx/4cJkFGAC7UqKWwxCcKGnpFVgcmkXsB41zwvXp5FEwMFER7n5zsuDEvx2wZyiDYSEXhMhS2zAW9ULUxNppL9oiSc9?cluster=devnet) |
| buyer changes the strategy: owner hands the freeze to the server | ok | [4k8mwdhy…5rfE](https://explorer.solana.com/tx/4k8mwdhyvGYXACfvzXAbMjUtbk2Dm63zNc5DoCTNze5x7wg3eRSPJPEV6NVJvk12vz54rtNBYCZjhA32SBu75rfE?cluster=devnet) |
| … server-signed v2 write + freeze, lock to 2026-10-12 16:50 UTC (240 h) | ok | [37xLuoN5…n6LK](https://explorer.solana.com/tx/37xLuoN5AsQRVqqdZf8Jwptgjx4k6RPxKBY3AY3V1Dfs7CL9Ht7kao8g5QjohA1WDxo3b3rMfbvytnGNv4qCn6LK?cluster=devnet) |
| listing after the change | refused by the server (no tx) | — |
| buyer transfer after the change | **failed on chain** (mpl-core 0x9), owner unchanged | [mRda1HJ4…bsW8](https://explorer.solana.com/tx/mRda1HJ4C9wQy1i4EopJKaH1aNpFUvBRtcjLfTNPkUQxw5nXAn7XmU9B5uJNz88CSWprimSWaykQno3qJXabsW8?cluster=devnet) |

## Where the listings live

Escrow is on chain (frozen + transfer delegate under the server). The listing rows (price, seller) are in the server database;
for this run that is the runner's PGLite on the test box. A deployed app shows them only with `MINT_AUTHORITY_SECRET` = the key of
`8eKeV2Vh…AxR3` and a `DATABASE_URL` that holds these listings.

Run phases: `JITI_ALIAS='{"@/":"<repo>/src/"}' PHASE=cycle|demo|migrate|market npx jiti scripts/strategy-devnet-run.ts`.
