# Solarchik privacy policy

Last updated: 9 October 2026 · App: Solarchik Assistant (Android, package `net.solardepin.solarchik.assistant`; the CLOCK IN game is `net.solardepin.solarchik`) · Contact: open an issue at https://github.com/Solar-DePIN-Hub/Solarchik/issues

## What stays on your phone
Streak and CLOCK IN history, fee-free windows, your agents list, the fee ledger, the agent desk (paper/devnet positions), the 1.1.0 swap, autopilot, delegated-limit, Saver and Watcher ledgers and settings, the experimental agent key (encrypted with Android Keystore; deleted by "Delete my data"), action cards found in call notes and their reminders, the official Season rules the app last downloaded and which rule changes you approved or ignored, the morning briefing time, its last text and a wallet balance snapshot (to say how it changed since yesterday), Sol chat history (last 40 turns), the days you opened the app and the Season dApp suggestions you opened (Seeker Season plan), call follow-ups you ticked off, notification settings, a random player id, the Mobile Wallet Adapter session token, and (if you turn on the call secretary) its settings, the last 40 screened calls (number, time, silenced/declined, AI note), a pending payment reference and the secretary forwarding number you enter. Call forwarding is set by your carrier through codes you dial yourself; the app never places calls (no CALL_PHONE permission). Nothing of this is uploaded to a Solarchik server. Android cloud backup and device transfer are disabled for the app.

## What leaves your phone, and why
| Data | Sent to | Why |
| --- | --- | --- |
| Text you type or dictate to Sol, recent chat turns, your language, a random player id and conversation id | Solarchik worker `solarchik-screen` (Cloudflare), which asks OpenAI for the reply and the spoken voice; if it is down, the `solarchik-market.vercel.app` fallback (Google Gemini). The daily note retell uses `friend.solardepin.net` (Featherless) | to get Sol's reply. Since 1.0.1 each message also carries a short summary of the phone's state so Sol can help: today's calls from the secretary inbox (name or number, time, the AI note, callback number; blocked callers left out), follow-ups, whether a wallet is connected (short address) and the Seeker Season plan. Since 1.1.0 it can also include which of your agents are on, recent Watcher alerts and open action cards from calls. Questions about your calls and the Season plan are answered on the phone and not sent. |
| Your public wallet address, transactions you approve in your wallet | Solana mainnet public RPC (`api.mainnet-beta.solana.com`); devnet RPC only if you turn on the hidden developer devnet switch | SOL/SKR balances, check-in memo, swaps, transfers from call actions, SPL approve/revoke for the delegated limit. Everything sent to Solana is public and permanent. |
| Token mints, amounts and your public address (for a swap you started, a Saver proposal or a delegated swap); token mints only for prices | Jupiter (`lite-api.jup.ag`): swap quote and swap transaction (v1), Price API v3 | quotes, the swap transaction your wallet then approves, and Watcher/briefing prices. |
| Morning briefing: yesterday's and today's calls (name or number, time, the AI note, callback number; blocked callers left out), due follow-ups, open call actions, Watcher alerts, your SOL/SKR balance and the change since yesterday, the Season plan, your language | Solarchik worker `solarchik-screen` (Cloudflare) → OpenAI | to write Sol's spoken briefing. Not stored by the worker. If it fails, the phone builds the text itself. |
| Call action extraction: for answered (not blocked) calls from the last 7 days, the caller's name, number, intent, AI note and transcript text, callback number, time; your language and time zone | Solarchik worker `solarchik-screen` → OpenAI (structured output) | to find payment, callback and reminder requests. Each call is sent once; nothing is stored by the worker. Your wallet address is not sent. |
| Call secretary, only if you turn on "AI note per screened call": the caller's number and your random player id. Balance, voicemail and payment checks send the player id and the Solana Pay reference | Solarchik secretary worker `solarchik-screen` (Cloudflare), which asks OpenAI for the note | a short note on who the unknown caller likely is. Your contacts and the call audio are never read or sent. |
| Nothing personal (a plain GET / POST without any user data) | Solarchik worker `solarchik-screen` → solanamobile.com, docs.solanamobile.com, OpenAI | the Season rules watcher: official Seeker Season notes and what the Season Agent may adapt |
| Nothing personal (plain public GET requests) | Coinbase, Kraken, Backpack, Open-Meteo, Polymarket Gamma | live market data for the game's strategy agents |

Voice input uses Android's on-device/system speech recognizer (`SpeechRecognizer`) and is asked for only when you tap the mic. Replies can be read aloud with the system text-to-speech engine.

## Permissions
- `RECORD_AUDIO`: voice input to Sol, asked on first mic tap.
- `POST_NOTIFICATIONS`: streak, reward, fee-window and desk notes, plus (1.1.0) the morning briefing, autopilot/Saver suggestions, Watcher alerts and call-action reminders; each can be switched off.
- The callback button opens the phone's dialer with the number filled in (`ACTION_DIAL`); you press call. The app still has no CALL_PHONE permission.
- No contacts permission. The optional call secretary (Android 10+, off by default) asks Android for the call screening role; Android then passes it only calls from numbers that are not in your contacts, and only the number.
- `INTERNET`, plus `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` added by Android WorkManager for the background desk and notes.

## Deleting your data
Settings → Privacy & data → **Delete my data** wipes everything listed under "What stays on your phone" and stops the background jobs. Uninstalling the app does the same. On-chain records cannot be deleted by anyone.

## Children
Solarchik is not directed at children under 13 (or the minimum age in your country) and does not knowingly collect their data.

## Money
1.1.0 runs on Solana mainnet: anything you approve in your wallet app moves real funds and cannot be undone. The app never signs for your wallet. Real swaps (Jupiter, capped), the Saver and the experimental delegated limit are off by default; the delegated limit lets an agent key on the phone spend only the token allowance you approved, inside per-action and per-day caps, until you revoke it. Payments suggested from phone calls are never pre-filled with a recipient. The game's strategy agents forecast on real public prices but never send orders to an exchange; paper mode uses a virtual 1 SOL. The one real-money feature is the optional call secretary credit: "Top up" opens your own wallet with a Solana Pay request for mainnet USDC to the Solarchik treasury, you approve or cancel it there, and the credit pays only for AI notes ($0.20 each). The app never moves funds from your wallet by itself.
