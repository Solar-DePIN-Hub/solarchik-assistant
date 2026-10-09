# Solarchik privacy policy

Last updated: 9 October 2026 · App: Solarchik Assistant (Android, package `net.solardepin.solarchik.assistant`; the CLOCK IN game is `net.solardepin.solarchik`) · Contact: open an issue at https://github.com/Solar-DePIN-Hub/Solarchik/issues

## What stays on your phone
Streak and CLOCK IN history, fee-free windows, your agents list, the fee ledger, the agent desk (paper/devnet positions), Sol chat history (last 40 turns), the days you opened the app and the Season dApp suggestions you opened (Seeker Season plan), call follow-ups you ticked off, notification settings, a random player id, the Mobile Wallet Adapter session token, and (if you turn on the call secretary) its settings, the last 40 screened calls (number, time, silenced/declined, AI note), a pending payment reference and the secretary forwarding number you enter. Call forwarding is set by your carrier through codes you dial yourself; the app never places calls (no CALL_PHONE permission). Nothing of this is uploaded to a Solarchik server. Android cloud backup and device transfer are disabled for the app.

## What leaves your phone, and why
| Data | Sent to | Why |
| --- | --- | --- |
| Text you type or dictate to Sol, recent chat turns, your language, a random player id and conversation id | Solarchik worker `solarchik-screen` (Cloudflare), which asks OpenAI for the reply and the spoken voice; if it is down, the `solarchik-market.vercel.app` fallback (Google Gemini). The daily note retell uses `friend.solardepin.net` (Featherless) | to get Sol's reply. Since 1.0.1 each message also carries a short summary of the phone's state so Sol can help: today's calls from the secretary inbox (name or number, time, the AI note, callback number; blocked callers left out), follow-ups, whether a wallet is connected (short devnet address) and the Seeker Season plan. Questions about your calls and the Season plan are answered on the phone and not sent. |
| Your public wallet address, transactions you approve in your wallet | Solana devnet RPC (`api.devnet.solana.com`); this build never signs on mainnet. For the Seeker Season card only, the address is also sent to the public mainnet RPC (`api.mainnet-beta.solana.com`) to read its SKR balance (read-only) | balances, check-in memo, NFT mint, devnet fee payments, SKR balance. Everything sent to Solana is public and permanent. |
| Call secretary, only if you turn on "AI note per screened call": the caller's number and your random player id. Balance, voicemail and payment checks send the player id and the Solana Pay reference | Solarchik secretary worker `solarchik-screen` (Cloudflare), which asks OpenAI for the note | a short note on who the unknown caller likely is. Your contacts and the call audio are never read or sent. |
| Nothing personal (plain public GET requests) | Coinbase, Kraken, Backpack, Open-Meteo, Polymarket Gamma | live market data for the strategy agents |

Voice input uses Android's on-device/system speech recognizer (`SpeechRecognizer`) and is asked for only when you tap the mic. Replies can be read aloud with the system text-to-speech engine.

## Permissions
- `RECORD_AUDIO`: voice input to Sol, asked on first mic tap.
- `POST_NOTIFICATIONS`: streak, reward, fee-window and desk notes; every kind can be switched off in Settings.
- No contacts permission. The optional call secretary (Android 10+, off by default) asks Android for the call screening role; Android then passes it only calls from numbers that are not in your contacts, and only the number.
- `INTERNET`, plus `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` added by Android WorkManager for the background desk and notes.

## Deleting your data
Settings → Privacy & data → **Delete my data** wipes everything listed under "What stays on your phone" and stops the background jobs. Uninstalling the app does the same. On-chain records cannot be deleted by anyone.

## Children
Solarchik is not directed at children under 13 (or the minimum age in your country) and does not knowingly collect their data.

## Money
The strategy agents forecast on real public prices but never send orders to an exchange. Paper mode uses a virtual 1 SOL. Devnet mode moves only devnet SOL, which has no value. The one real-money feature is the optional call secretary credit: "Top up" opens your own wallet with a Solana Pay request for mainnet USDC to the Solarchik treasury, you approve or cancel it there, and the credit pays only for AI notes ($0.20 each). The app never moves funds by itself.
