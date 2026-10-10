# Colosseum submission: Sol, AI secretary for Seeker (v1.2.9)

The text I submitted to Colosseum, kept here so judges can read it next to the code.

## Project name

Sol: AI secretary for Seeker

## One-liner

Sol answers the calls you miss, and every morning one swipe clears what came out of them: call back, remind, or pay on Solana.

## Short description

Sol is an Android app for Seeker. When I can't pick up, an AI secretary answers on a real phone line, writes me a note and turns the call into cards. Every morning the cards wait in one stack with my Season tasks and habits. Payments are one tap and one approval in my own wallet, on Solana mainnet.

## Full description

I'm Vadym, a solo founder from Ukraine. In meetings I miss calls, and some of them matter: a client, a friend asking me to pay back for lunch. Others are scams asking for money. So I built Sol.

How a day with Sol works:

1. Someone calls and I'm busy. My carrier forwards the missed call to Sol's line.
2. An AI secretary answers, talks to the caller, and takes the message.
3. I get a short note (who, why, how urgent) and cards: call back, reminder, or payment.
4. In the morning, Today shows one stack: cards from calls, today's Seeker Season tasks, money people owe me or I owe them, and my habits. Each card says what it is and where it came from.
5. Swipe right when it's done. Swipe left for Later, Tomorrow or Dismiss. Tap to do it: the dialer, the payment, the Season post.
6. When every card is handled, I clock in and my streak grows. Signing a CLOCK IN memo on mainnet is optional.

Circle is the money part. It's people I save myself, with their wallet address. Each person has Send and Request. Request makes a Solana Pay link and QR and opens an SMS ("Hi Ira, here's my payment link for 0.01 SOL"). When the money lands, the app finds the transaction on chain and shows "Paid ✓". If a caller says "send me 0.01 SOL for lunch", the call adds that debt to Circle.

Sol is the mic button in the middle. I hold it and ask "who do I owe?" or "send Ira what I owe", and Sol opens the right card. Nothing is sent until I approve it in my wallet.

For Seeker owners there's a Season agent. Every day it reads Solana Mobile's official sources and a dated list of partner drops announced on X, and puts today's tasks into the stack. Each task opens the original announcement. It never claims, farms or signs anything. A Verified Seeker check (Genesis Token) gives 10 extra secretary minutes a day.

## Problem

Freelancers and small business owners miss calls while they work, and a missed call can be a lost client. People don't listen to voicemail. Scam calls make people stop picking up unknown numbers. And when someone asks to be paid back, the call is in one app and the wallet in another, so it gets forgotten.

## Solution

One app that picks up for me and turns the call into the next step. The secretary answers, I get a note and cards, and every morning one stack clears them. Money only moves after one tap in my own wallet.

## Why Solana, why Seeker

- Speed and cost: my 0.01 SOL payments from the app settled in about 3–4 seconds with a fee of 0.00008 SOL. Paying back a $1 lunch right after a call only makes sense when the fee is a fraction of a cent.
- Solana Pay: a request is a plain link or QR anyone with a Solana wallet can open, so I can send it by SMS. The reference key lets the app confirm the payment on chain by itself.
- Seeker: the wallet is built into the phone (Seed Vault), and Sol uses the same Mobile Wallet Adapter flow as Phantom. Seeker owners already hold a wallet, so they're who I start with.

## Competition

- Google Call Screen (Pixel) asks the caller why they're calling and shows a transcript.
- Truecaller Assistant answers and transcribes calls; it comes with Truecaller Premium, $9.99 a month in the US App Store.
- Rosie, an AI receptionist for small businesses, starts at $49 a month.

People already pay for a phone secretary. As far as I know, none of them turns a call into a to-do stack and a wallet payment, or keeps track of who owes whom.

## Try it

- Android APK (latest release): https://github.com/Solar-DePIN-Hub/solarchik-assistant/releases/latest
- Call the shared demo secretary line: +380 91 481 0885. Say who you are and what you want.

## Business model (proposed, nothing is billed yet)

1. Free: Sol, the wallet, Circle, habits, and secretary minutes on the shared demo line.
2. Pro, $7.99 a month: my own secretary number, more minutes, cards from every call.
3. Business (planned): for small service businesses that miss calls while they work, like barbers, repair shops and freelancers. Own number, booking from the call, deposit requests.
4. A small fee on Circle payments (planned, not in the app yet). Every Request I send by SMS also reaches someone who may not have the app yet.

## Go-to-market

1. Seeker owners first, through the Solana dApp Store and the Seeker community. They already have a wallet on the phone.
2. Then small businesses and freelancers who miss calls during the day.

## Traction

Live on Solana mainnet, tested by 4 people, my family and friends.

## Proof on mainnet

Two real 0.01 SOL payments from the app (Circle → Send → Phantom) on 10 Oct 2026:
- https://solscan.io/tx/4z8PUAVD99bFWRM2KHpAGFqarESNDxQBsKNVzsh3e2MCT65N79tgSKayRvfRSVZg8AMhJVnkA7ftUd22ByLUnW6z
- https://solscan.io/tx/3BtXnkEfWTbZbGSzEKFJVsrMTRYqBvGY39xMSd1gzdAEvWCBwFBLE13cYmqRug5xzgeA8Fv2XsDyF8bvdFFQbVTr

## Team

Vadym Bilobrovets, solo founder, Solar DePIN, Ukraine. I do the design, the Android app and the backend. I'm also the first user: I built this because I miss calls in meetings.

## How it's built

- Android app: native Kotlin, no WebView. The app holds no API keys.
- Phone secretary: GSM conditional forwarding → Zadarma number → SIP → OpenAI Realtime voice agent → Cloudflare Worker saves the transcript, writes the note and the action cards. AI minutes are capped per call and per day.
- Wallet: Mobile Wallet Adapter 2.0 (Phantom, Solflare, Seed Vault on Seeker). The app simulates every transaction on mainnet before the wallet opens. The server never holds keys. The wallet sees the app as app.solardepin.net, verified with Digital Asset Links.
- Solana: SOL / USDC / SKR transfers, Solana Pay requests with reference keys, balance reads, an optional CLOCK IN memo, Seeker Genesis Token check (Token-2022) with Sign In With Solana.
- Season agent: the worker reads Solana Mobile's blog and docs, plus a curated, dated list of partner drops from X.
- Tests: Android unit and Robolectric tests, worker tests.
- Open source, MIT.

## Honest limits

This is a hackathon build by one person and it hasn't been audited. I test on an Android tablet with Phantom; I don't own a Seeker yet, so Seed Vault and the Genesis Token check aren't tested on a real Seeker. The secretary runs on one shared demo line; personal numbers are a Pro feature I haven't built yet. The APK is signed with my own key, not a store key.

## Roadmap

1. Personal secretary numbers and Pro billing in USDC / SKR.
2. Publish to the Solana dApp Store.
3. Business plan pilot: booking and deposits from the call card.
4. Test on a real Seeker.
5. An external security review.

## Links

- GitHub: https://github.com/Solar-DePIN-Hub/solarchik-assistant
- APK: https://github.com/Solar-DePIN-Hub/solarchik-assistant/releases/latest
- Pitch video: PITCH_VIDEO_URL
- Tech video: TECH_VIDEO_URL
- Demo secretary line: +380 91 481 0885
- X: https://x.com/SolarDePin
- Email: team.solardepinhub@gmail.com
