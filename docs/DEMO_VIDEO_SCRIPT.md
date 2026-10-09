# Solarchik Assistant 1.1.3: demo video shot list (2:20–2:30)

This video plays right after the 2-minute speech (pitch-2min-en.md, kept with the Colosseum deliverables), and later on its own for Colosseum. It must work on its own: the speech explains the problem, why Seeker and the business model. The video **shows** it. Nothing from the speech is repeated word for word, so the captions below use different wording on purpose.

- Target length: **2:25** (2:20 minimum, 2:30 maximum). The slot allows 3:00, but a tight video keeps the wow. Don't stretch it.
- Language: **English** everywhere: app, Sol, secretary, captions.
- Most of the sound comes from the app: the secretary and Sol talk. Voice-over (VO) is optional and short. If you skip the VO, the captions are enough.
- Record the Seeker screen with the built-in screen recorder ("media + mic", sound on). Record the caller's phone separately (see shot 1). Edit in CapCut or similar: cut every wait, keep real speed for taps.
- **Must-have shots:** the live call (shot 1) and the **daily check-in signed in Seed Vault on the real Seeker** (shot 4, it matters for CLOCK IN). If time runs short, cut from shots 5, 6 and 8, never from these two.

---

## Pre-flight (the evening before, or on the morning of 10 Oct before 11:00 Kyiv)

1. **Install** v1.1.3 from https://github.com/Solar-DePIN-Hub/solarchik-assistant/releases/tag/v1.1.3 and open it once. Finish onboarding. Allow **notifications** and the **microphone**.
2. **Language:** More → Language → English. **Secretary language:** More → Secretary → "Secretary speaks" → **English**. Check that this phone is the owner account ("Owner · no credit needed").
3. **Wallet:** Seed Vault on **mainnet** with about **0.05 SOL** and some SKR if you have it. Today → **Set up wallet** → approve. Today must show a real SOL balance (and SKR), not "—".
4. **Real swaps on, before recording** (so the risk dialog doesn't eat screen time): Agents → **Saver** → scroll to **Real swaps with Jupiter** → **Real swaps off** → "I understand, turn on real swaps". Keep the default caps.
5. **Watcher on, the evening before:** Agents → **Watcher** → **Turn on Watcher**, set "Alert when a price moves by" to the lowest value, tap **Check now** once. By morning "Recent alerts" should have at least one line instead of "No alerts yet".
6. **Season rules:** Agents → **Season Agent** → **Official Season rules** → **Check for rule updates** once. It must show a source title, a date and a quote, not "No official rule notes yet".
7. **Call forwarding (for the strongest opening):** More → Secretary → **Forward unanswered calls to Sol secretary** → secretary number **+380914810885** → Save → **Busy** → press call in the dialer. Then do one test: ask your helper to call your Seeker number, press **Decline**, and check that the note shows up in **Calls** within about 30 s. If it doesn't land in your Calls, use plan B in shot 1.
8. **Call budget (new in 1.1.3):** each AI call ends at 3:00 (the secretary says goodbye at 2:45), and the line answers at most 30 AI minutes a day in total, 20 per account (the day resets at 03:00 Kyiv). A normal test call is about 1 minute, so 3–5 test calls plus the take are fine. Don't leave a call running "to see what happens".
9. **The helper (the "caller").** A friend with a second phone. Their phone is on **speaker**, and they record it themselves (their own screen recorder with mic) or you film it with a third phone. They read the caller lines from shot 1. Ask them to speak slowly.
10. **Real-device signing test.** Before recording, sign one tiny transaction off camera (for example a 0.001 SOL → USDC swap) and open it on Solscan. If it works, you can approve on camera in shot 5. If it doesn't, follow the "reject" path. Never claim on screen that something worked unless the confirmation is in the shot.
11. **Daily check-in: do NOT sign it before recording.** It's one per day and the day resets at 03:00 Kyiv, so keep today's check-in for shot 4. (If it was already signed by accident, shot 4 shows "Signed on Solana ✓" plus the Solscan link, which is weaker. Then record shot 4 again after 03:00 Kyiv the next night if you have time.)
12. **Phone:** Do Not Disturb on (calls allowed), battery > 50 %, brightness up, good Wi-Fi/LTE, other apps closed, volume up, not on silent. Hide private notifications. Clear old test calls you don't want on screen (or make sure the newest call is the one from the recording).
13. **Architecture card for shot 7:** `docs/architecture-1.1.3.png` in the repo (also `/workspace/deliverables/deck/architecture-1.1.3.png`). Put it into the edit as a still.

---

## Shot list

### Shot 1 · 0:00–0:30 · A real call, answered by the secretary (the opening)

**Plan A (forwarding, best):**

| Time | Seeker screen / taps | Caller phone (speaker, recorded) | Caption |
|---|---|---|---|
| 0:00–0:04 | Seeker on the home screen. Incoming call from the helper ("Olena"). You let it ring once, then press **Decline**. | Ringing. | **I'm busy. I don't pick up.** |
| 0:04–0:26 | Open the app → **Calls**. The new row shows "The secretary is on the call or writing the note…". (Use split screen in the edit: Seeker on the left, caller audio on top.) | The secretary greets and asks who is calling. Caller (slowly): *"Hi, this is Olena. I'm calling Vadym."* Secretary asks what it's about. Caller: *"I paid for our lunch yesterday. Can he send me zero point zero one SOL? Please ask him to call me back today at three, it's about the contract. And remind him about our meeting on Monday."* Secretary confirms and says goodbye. Caller: *"Thanks, bye."* | **My AI secretary picks up. Live, on a real phone line.** |
| 0:26–0:30 | The notification for the new note slides in. | Hang up. | |

**Plan B (helper calls the demo line directly):** the helper dials **+380 91 481 0885** from their phone. Open on the caller's screen dialing (filmed), then the same conversation. Seeker shows Calls with the "on the call" row. Same caption.

**Plan C (one phone only):** Today → **Try a call**, phone on **speaker**, you play Olena with the same lines. The screen recorder picks up both voices through the mic. Caption: **The secretary line answers. Live.** (Don't call it an incoming call in this version.)

Cut the wait between the hang-up and the note. Don't show a spinner for more than 1 second.

### Shot 2 · 0:30–0:48 · The note and the action cards

| Time | Taps | Caption |
|---|---|---|
| 0:30–0:36 | **Calls** → tap the new call. Show the **Secretary's note** (who, why, how urgent, callback number), one second of the transcript. | **A short note: who, what, how urgent.** |
| 0:36–0:42 | Back → **Today** → **Actions from calls**: "Pay 0.01 SOL · asked by Olena", "Call Olena back at 15:00" and the reminder "Remind … about the meeting on Monday". Tap **Remind me** on the reminder → "Reminder at 12 Oct, 09:00" (the Monday after the call). | **One call, three next steps.** |
| 0:42–0:48 | On the payment card tap **Prepare transfer**. Show the sheet: amount filled in, **recipient field empty**, the scam warning, "I checked the recipient address myself" not ticked. Tap back. Don't type an address. | **Payments are only prepared. Empty recipient. Scam warning.** |

If the reminder card doesn't appear (the model can vary), skip that tap and keep the two other cards; the caption then says **The call turns into next steps.**

### Shot 3 · 0:48–1:00 · Sol's morning briefing

| Time | Taps | Caption |
|---|---|---|
| 0:48–1:00 | **Today** → **Morning briefing** card → **Play briefing**. Let Sol speak for about 10 s (Olena's call and the callback, the wallet). Cut the rest. | **Every morning Sol reads me my day.** |

If the briefing starts with "No calls since yesterday", the call note hasn't synced yet: go to Calls, tap **Refresh**, and record this shot again.

### Shot 4 · 1:00–1:20 · Daily check-in, signed on the real Seeker (must-have)

| Time | Taps | Caption |
|---|---|---|
| 1:00–1:05 | **Today**: the wallet card with real SOL and SKR (mainnet), then the **Daily check-in** card. | **Real balances. Solana mainnet. My Seeker.** |
| 1:05–1:15 | Tap **Check in**. **Seed Vault** opens on the Seeker with the transaction. Approve it (fingerprint / double-press, whatever Seed Vault asks). Keep the Seed Vault screen in the shot, but never the PIN. | **Signed in Seed Vault, on the device.** |
| 1:15–1:20 | Back in the app: "Signed on Solana ✓". Tap the Solscan link and hold 2 s on the confirmed memo transaction. | **One real mainnet transaction a day. That's my streak.** |

If the wallet fails: stop, fix, record this shot again. This one is not optional.

### Shot 5 · 1:20–1:38 · A Jupiter swap review

| Time | Taps | Caption |
|---|---|---|
| 1:20–1:29 | **Agents** → **Saver** → **Real swaps with Jupiter** ("Mainnet · real funds"). You pay **SOL 0.01**, you get **USDC**. **Get quote**. Show **Review before your wallet**: route, price impact, fees, "Counts toward today's cap". | **A real Jupiter quote. Fees and impact first.** |
| 1:29–1:38 | **Confirm in wallet**. Seed Vault opens. **Reject path (default):** Reject → "The wallet did not sign. Nothing was sent." **Approve path (only if the pre-flight test worked):** approve → "Swap confirmed on mainnet" → Solscan 1 s. | Reject: **My wallet decides. Nothing was sent.** Approve: **Confirmed on mainnet.** |

### Shot 6 · 1:38–1:52 · Seeker Season plan and the rules watcher

| Time | Taps | Caption |
|---|---|---|
| 1:38–1:44 | **Agents** → **Season Agent** → **Open the Season plan**: Daily use ✓ (from shot 4's check-in), Explore a dApp, One useful onchain action. | **A daily plan from real actions. No points promised.** |
| 1:44–1:52 | **Official Season rules** → **Check for rule updates**. Show the source title and date and **What the Season Agent changed** with the quote. Freeze 1 s on the quote. | **It reads Solana Mobile's official pages and quotes them.** |

### Shot 7 · 1:52–2:10 · Under the hood (fast)

| Time | On screen | Caption |
|---|---|---|
| 1:52–2:00 | The architecture card (`architecture-1.1.3.png`), slow zoom from the Android app box to Solana mainnet. | **Kotlin app → Cloudflare Worker → OpenAI Realtime over a real SIP line. Wallet: Mobile Wallet Adapter, Seed Vault. Jupiter. Solana mainnet.** |
| 2:00–2:05 | Zoom into the green safety bar of the same card. | **Calls end at 3:00. 30 AI minutes a day, max.** |
| 2:05–2:10 | Quick cut back to the payment sheet from shot 2 (reuse the footage): the scam warning and the empty recipient. | **No payment without me. A dictated address gets a scam warning.** |

### Shot 8 · 2:10–2:25 · The three agents, and the end card

| Time | Taps | Caption |
|---|---|---|
| 2:10–2:13 | **Season Agent** tab: autopilot line "1–2 real actions a day, you sign each". | **Season Agent: suggests, never signs for me.** |
| 2:13–2:16 | **Saver** tab, then **Watcher** tab with one line in "Recent alerts" (two quick cuts). | **Saver: inside my caps. Watcher: alerts only.** |
| 2:16–2:20 | (Optional, cut first if long) Sol answers one held-mic question: *"Sol, what's left for today?"* | **Ask Sol.** |
| 2:20–2:25 | End card (still image, 5 s). | **Solarchik Assistant** · Android app for Solana Seeker · v1.1.3 · Call the secretary: **+380 91 481 0885** · github.com/Solar-DePIN-Hub/solarchik-assistant |

---

## Optional voice-over (only if you want your own voice in the video)

Different words from the speech on purpose. Record it after the screen takes, read slowly.

- Shot 1: "Someone calls. I'm in a meeting."
- Shot 2: "Ten seconds later I know what Olena wants, and what to do next."
- Shot 4: "Every day one real transaction, signed on my Seeker."
- Shot 5: "The quote is real. The decision is mine."
- Shot 7: "A real phone line, my own wallet, and limits so nothing runs away."

---

## What to avoid on camera

- **Empty states:** "No calls yet", "No calls since yesterday", "No alerts yet", "No official rule notes yet", "Reading prices…", "Loading calls…", "—" in place of a balance. Prepare the data the evening before (pre-flight 5–7).
- **Devnet anywhere:** More → developer switch / "Force devnet", "Get devnet SOL", the old strategy market, any "(devnet)" label. Don't open the developer settings at all.
- **"Mainnet mint: coming soon"** on the agent NFT card: scroll past it, don't tap Mint. (The speech already says the NFT opens later.)
- **Experimental delegated limit:** don't turn it on in the video. It's a side feature with a red warning; showing it would need explaining.
- **Claims the video can't back up:** no caption saying "proven", "guaranteed", "earns points" or "fully tested". Show an approval only if the confirmation is in the shot.
- **Private data:** seed phrase, Seed Vault PIN, full balance of a personal wallet you don't want public, other people's numbers, unrelated notifications.
- **Long waits:** spinners, "Preparing…", the 30 s before the note appears. Cut them.
- **Sending real money to an address a caller dictated.** Never, even for the demo.

## If something goes wrong while recording

- The secretary doesn't pick up: check the internet, wait a minute, call again. Plan C works with one phone.
- You hear "the assistant can't take any more calls today": the daily AI minutes cap (pre-flight 8) was reached. Ask the parent agent/Vadym's assistant to raise `CALL_DAILY_MIN_GLOBAL` / `CALL_DAILY_MIN_ACCOUNT` on the worker (one `wrangler secret put`, no app update).
- The note is slow: stop recording, wait, open **Calls** → **Refresh**, and record shot 2 separately.
- Sol doesn't answer: check the connection and ask again. The text box works too, but voice looks better.
- The wallet doesn't open: the wallet app must be installed and on mainnet. Tap again, or use the reject path (not for shot 4: fix it and record again).
- "Checked a few minutes ago" on the rules check is the normal 5-minute server limit. Keep it.
