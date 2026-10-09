# Solarchik Assistant — Colosseum (Crypto World's Fair)

Українською: [README.uk.md](README.uk.md).

**Solarchik Assistant** is a native Android pocket AI on Solana: voice friend **Sol**, an AI **phone secretary**, agent NFTs with a wallet path, and a small daily check-in. The roof run is a habit bonus, not the main product.

Built for **Colosseum Crypto World's Fair** (submissions due **12 Oct 2026, 11:59pm PT** / **13 Oct 2026, 09:59 Kyiv**), category **AI Platforms / Agents**, Solana track.

This repo is the Colosseum build. The CLOCK IN game submission stays in [Solar-DePIN-Hub/Solarchik](https://github.com/Solar-DePIN-Hub/Solarchik) (`native-full`).

## What judges should try

1. Talk to Sol with the mic (EN/UK follows the phone language).
2. Open the call secretary inbox: summaries, reminders, block list.
3. Mint or run a strategy agent NFT on **Solana devnet** (paper / devnet only; no live exchange orders).
4. Do the daily check-in (on-chain memo on devnet).

## Links

| Item | URL |
| --- | --- |
| This repo | https://github.com/Solar-DePIN-Hub/solarchik-assistant |
| Game repo (CLOCK IN, untouched) | https://github.com/Solar-DePIN-Hub/Solarchik |
| Live API / market (devnet) | https://solarchik-market.vercel.app |
| Friend / voice worker | `friend.solardepin.net` |
| Pitch | [PITCH.md](PITCH.md) |
| Privacy | [PRIVACY.md](PRIVACY.md) |
| Build notes | [artifacts/solarchik-handoff/NATIVE_PROGRESS.md](artifacts/solarchik-handoff/NATIVE_PROGRESS.md) |

## Stack (honest)

- **Native Kotlin** APK, no WebView (`artifacts/solarchik-handoff/android`, package currently `net.solardepin.solarchik`, version **0.22.3**). A separate installer id for Assistant vs the game APK is planned before final Colosseum submit.
- **Solana Mobile Stack**: Mobile Wallet Adapter; optional built-in **devnet** wallet in Android Keystore for judges without Phantom.
- **On Solana (devnet)**: check-in memos, Metaplex Core agent NFTs (Free / Pro), strategy change txs.
- **AI**: Sol chat via Featherless-first worker (no keys on the phone). Phone secretary: real line → SIP → realtime model → notes & transcript.
- **Safety**: agents never send exchange orders; paper + devnet only; paid mainnet mint compiled off (`MAINNET_PAID_MINT=false`).

## Product map (target home)

| Surface | Role |
| --- | --- |
| Home | Sol + large mic, recent secretary cards, reminders, wallet strip, small check-in |
| Calls | Inbox, transcripts, block, reminders |
| Agents | Mint / run strategy NFTs, fees on devnet |
| Check-in | Daily on-chain habit (memo) |
| Run | Optional roof runner (bonus) |
| Settings | Wallet, language, privacy & delete-my-data |

Source on `main` today is still the 0.22.3 rooftop build copied from `native-full`. Home is being reshaped toward the table above for Colosseum.

## Build

```bash
cd artifacts/solarchik-handoff/android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## Timeline

Started Sep 19, 2026 on the Solarchik line. An older unrelated Telegram prototype (Jul 2026) is not part of this submission. Also shipped MunichTech (Sep 2026) and CLOCK IN / Radiants (Oct 2026) from the game repo.

## Team

Solar DePIN / Vadym Bilobrovets (solo).  
Contact: team.solardepinhub@gmail.com · X [@SolarDePin](https://x.com/SolarDePin)
