# Colosseum Crypto World's Fair — draft fields (Solarchik Assistant)

Deadline: 12 Oct 2026 11:59pm PT (13 Oct 2026 09:59 Kyiv).  
Track: Solana. Category focus: AI Platforms / Agents.  
Repo: https://github.com/Solar-DePIN-Hub/solarchik-assistant  
Voice: first person as Vadym. Do not submit until he OKs.

## Short description (one liner)

I built Solarchik Assistant: a pocket AI on Android with a voice friend, a real-line call secretary, and agent NFTs on Solana.

## Elevator pitch (~90s / short form)

I got tired of cold chatbots and spam calls. Solarchik Assistant puts one character on your phone home screen. You hold a mic and talk to Sol. If you cannot pick up, the AI secretary answers on a real number, talks to the caller, and leaves a short summary: who called, why, what to do next. Agent NFTs on Solana hold strategy and a small wallet path. A daily on-chain check-in keeps the habit. The roof run is only a bonus. For the hackathon everything money-related stays on Solana devnet and paper mode. No live exchange orders.

## Problem

People get spam and unknown calls, and most AI tools feel like another chat tab. Mobile users need something that actually picks up, talks, and leaves a clear next step, with a real on-chain habit, not a slide deck.

## Solution

Native Kotlin Android app. Sol for voice. Secretary on a real line with transcripts and reminders. Metaplex Core agent NFTs and check-in memos on Solana devnet. Optional roof run as a daily bonus.

## How it uses Solana

- Daily check-in as a memo transaction on devnet
- Metaplex Core agent NFTs (free / Pro mint path)
- Strategy change transactions and ownership checks
- Mobile Wallet Adapter (Phantom / Solflare / Seed Vault) or a built-in devnet wallet for judges

## Demo video script (film on phone, ~2–3 min)

1. Home: Sol + mic. Ask one question in Ukrainian, one in English.
2. Missed-call / secretary card: open summary, show transcript snippet.
3. Agents: mint or open an existing strategy NFT on explorer (devnet link).
4. Check-in: sign the daily memo, show explorer.
5. Optional 10s of roof run. Close: “Solarchik Assistant — pocket AI on Solana.”

## Links to paste

- GitHub: https://github.com/Solar-DePIN-Hub/solarchik-assistant
- CLOCK IN game repo (separate): https://github.com/Solar-DePIN-Hub/Solarchik
- API / market: https://solarchik-market.vercel.app
- Demo (CLOCK IN cut, reuse until Assistant cut): https://youtu.be/oAxoliLwUXo
- Deck PDF (CLOCK IN frames; refresh for Assistant): docs/clockin-deck.pdf in this repo
- X: https://x.com/SolarDePin
- Email: team.solardepinhub@gmail.com

## Honest disclosures

- Started Sep 19, 2026 on the Solarchik line; older Jul Telegram prototype is unrelated.
- MunichTech and CLOCK IN used the game-shaped build; Colosseum is repositioned as Assistant in this repo.
- No SKR / dApp Store publish yet.
- No live mainnet trading; agents are paper/devnet.
- Prior accelerator wins were with a different project (answer No if the form means this product).

## Open before submit

- [ ] New `applicationId` so Assistant installs beside the game APK
- [ ] Home UI: Sol + large mic (not rooftop-first)
- [ ] Fresh demo video + deck frames for Assistant story
- [ ] Update Colosseum project page links to this repo
