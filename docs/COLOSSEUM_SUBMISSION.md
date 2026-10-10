# Colosseum Crypto World's Fair — draft fields (Solarchik Assistant)

Deadline: 12 Oct 2026 11:59pm PT (13 Oct 2026 09:59 Kyiv).  
Track: Solana. Category focus: AI Platforms / Agents.  
Repo: https://github.com/Solar-DePIN-Hub/solarchik-assistant  
Voice: first person as Vadym. Do not submit until he OKs.

## Short description (one liner)

I built Solarchik Assistant: a pocket AI on Android with a voice friend, a real-line call secretary that turns calls into actions, a morning voice briefing and three small agents on Solana mainnet, where every transaction is approved in your wallet.

## Elevator pitch (~90s / short form)

I got tired of cold chatbots and spam calls. Solarchik Assistant puts one character on your phone home screen. You hold a mic and talk to Sol. If you cannot pick up, the AI secretary answers on a real number, talks to the caller, and leaves a short summary: who called, why, what to do next. What the caller asked for becomes a card: call back in one tap, a reminder, or a payment you approve in your own wallet, with the recipient typed or confirmed by you and a scam warning. Every morning Sol reads you a short briefing: calls, follow-ups, how your wallet changed, your Seeker Season plan. Three agents, all off by default: a Season Agent, a Saver that moves small amounts into USDC or SKR through real Jupiter swaps with caps, and a Watcher that only watches prices. It runs on Solana mainnet with Mobile Wallet Adapter; the app never signs for your wallet.

## Problem

People get spam and unknown calls, and most AI tools feel like another chat tab. Mobile users need something that actually picks up, talks, and leaves a clear next step, with a real on-chain habit, not a slide deck.

## Solution

Native Kotlin Android app. Sol for voice. Secretary on a real line with transcripts, reminders and action cards. Morning voice briefing from local data. Mainnet check-in memos, capped Jupiter swaps, SOL/USDC transfers and an experimental SPL-approve delegated limit, all through MWA. Metaplex Core agent NFTs (tested on devnet; mainnet collection coming soon).

## How it uses Solana

- Mainnet-beta by default; Mobile Wallet Adapter only (Phantom / Solflare / Seed Vault); real SOL/SKR balances, Solscan/Orb links
- Daily check-in as a mainnet memo transaction
- Jupiter swaps (SOL/USDC/SKR/JUP), opt-in, daily cap, slippage and impact limits; Saver agent proposals use the same review
- Payments from call notes as SOL / USDC (transferChecked) transfers the user approves
- Experimental delegated limit: SPL Approve to an agent key, caps, revoke/withdraw
- Metaplex Core agent NFTs, Free / Pro (0.1 SOL), paid checks on the server (devnet tested; mainnet coming soon)

## Demo video script (film on phone, ~2–3 min)

1. Morning: the briefing notification, Sol speaks the summary.
2. Secretary call: the note, then the action cards (call back, reminder, payment with the scam warning).
3. Payment: type the recipient, the wallet app opens, approve a tiny amount, show Solscan.
4. Agents: Season Agent plan, Saver proposal → swap review → wallet; Watcher alert.
5. Check-in memo on mainnet. Close: “Solarchik Assistant — pocket AI on Solana.”

## Links to paste

- GitHub: https://github.com/Solar-DePIN-Hub/solarchik-assistant
- CLOCK IN game repo (separate): https://github.com/Solar-DePIN-Hub/Solarchik
- API / market: https://solarchik-market.vercel.app
- Demo (CLOCK IN cut, reuse until Assistant cut): https://youtu.be/oAxoliLwUXo
- APK: https://github.com/Solar-DePIN-Hub/solarchik-assistant/releases/download/v1.1.6/solarchik-assistant.apk
- Deck PDF (CLOCK IN frames; refresh for Assistant): docs/clockin-deck.pdf in this repo
- X: https://x.com/SolarDePin
- Email: team.solardepinhub@gmail.com

## Honest disclosures

- Started Sep 19, 2026 on the Solarchik line; older Jul Telegram prototype is unrelated.
- MunichTech and CLOCK IN used the game-shaped build; Colosseum is repositioned as Assistant in this repo.
- No dApp Store publish yet. SKR is shown read-only (mainnet balance); staking is a link to stake.solanamobile.com. The Seeker Season plan never signs or repeats anything, and it can't show Season points.
- 1.1.0 is on mainnet with real funds; swaps and agents are opt-in with small caps. Signed mainnet transactions were verified by building and simulating against mainnet, not by spending funds. Agent NFT mint on mainnet is not open yet.
- No Seeker in hand: the demo video is filmed on an Android tablet with Phantom through Mobile Wallet Adapter. The app is built for Seeker and reaches Seed Vault through the same MWA flow, but Seed Vault on a real Seeker is not tested yet. Seeker Season counts Seed Vault activity, so the tablet's check-ins do not count for Season.
- Prior accelerator wins were with a different project (answer No if the form means this product).

## Open before submit

- [x] New `applicationId` (`net.solardepin.solarchik.assistant`) so Assistant installs beside the game APK (v1.0.0)
- [x] Home UI: Today with Sol + large mic, secretary, follow-ups, wallet, Seeker Season (v1.0.0)
- [x] v1.1.0 mainnet build: three agents, morning briefing, call → action
- [ ] Fresh demo video + deck frames for Assistant story
- [ ] Update Colosseum project page links to this repo
