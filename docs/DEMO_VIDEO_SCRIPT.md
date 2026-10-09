# Solarchik Assistant 1.1.4: demo video shot list (2:20–2:30)

This video plays right after the 2-minute speech (pitch-2min-en.md, kept with the Colosseum deliverables), and later on its own for Colosseum. It must work on its own: the speech explains the problem, why Seeker and the business model. The video **shows** it. Nothing from the speech is repeated word for word, so the captions below use different wording on purpose.

- **Device, honestly:** the app is built for Solana Seeker and supports Seed Vault through Mobile Wallet Adapter (MWA). But the video is filmed on an **Android tablet with Phantom**, through the same MWA flow. Vadym doesn't have a Seeker. Never say or caption that something is filmed or signed on a Seeker or in Seed Vault. The first caption says what the device is.
- Target length: **2:25** (2:20 minimum, 2:30 maximum). The slot allows 3:00, but a tight video keeps the wow. Don't stretch it.
- Language: **English** everywhere: app, Sol, secretary, captions.
- Most of the sound comes from the app: the secretary and Sol talk. Voice-over (VO) is optional and short. If you skip the VO, the captions are enough.
- Record the tablet screen with its built-in screen recorder ("media + mic", sound on; on Samsung: quick panel → Screen recorder → "Media sounds and mic"). Record the caller's phone separately (see shot 1). Edit in CapCut or similar: cut every wait, keep real speed for taps.
- **Must-have shots:** the live call (shot 1) and the **daily check-in signed in Phantom** (shot 4). If time runs short, cut from shots 5, 6 and 8, never from these two.

---

## Pre-flight (the evening before, or on the morning of 10 Oct before 11:00 Kyiv)

Nothing has been installed yet, so do the steps in this order. Allow about 40 minutes.

1. **Phantom on the tablet.** Google Play → **Phantom** (publisher Phantom Technologies) → Install → **Create a new wallet**. Use a fresh wallet for the demo, not your personal one: its address and balance will be on camera. Write the recovery phrase on paper and keep it off camera. Phantom is on Solana mainnet by default; check that **Settings → Developer settings → Testnet mode is off**.
2. **Fund Phantom with about 0.02–0.05 SOL.** In Phantom tap **Receive** → Solana → copy the address. Send 0.02–0.05 SOL to it from an exchange or another wallet (double-check the first and last 4 characters). Wait until Phantom shows the balance. 0.02 SOL is enough for the check-in, a small swap test and the swap on camera; 0.05 SOL leaves room for retakes. (SKR is optional; without it the SKR line shows 0, that's fine.)
3. **Install the app.** On the tablet open https://github.com/Solar-DePIN-Hub/solarchik-assistant/releases/tag/v1.1.4 → **solarchik-assistant.apk** → open the download → allow "Install unknown apps" for the browser when Android asks → Install. Open it once, finish onboarding, allow **notifications** and the **microphone**.
4. **Connect Phantom in the app.** Today → **Set up wallet** → choose **Phantom** if Android asks → Phantom shows "Connect" for Solarchik → **Connect**. Back in the app, Today must show the real SOL balance, not "—". (If the app says "No Solana wallet app found", Phantom isn't installed or the app was opened before it; close the app and open it again.)
5. **Language:** More → Language → English. **Secretary language:** More → Secretary → "Secretary speaks" → **English**.
6. **Your account and the call budget.** The tablet install is a new account. More → Secretary: if it says **"Owner · no credit needed"**, you're set. If it shows **Secretary credit** with a trial amount instead, the account has **3 starter calls**, and each caller number gets 3 trial calls a day. To lift that, send your **Account ID** (More → Secretary → Account ID) to the agent: it gets added to the owner list on the worker (`ADMIN_USER_IDS`, no app update). Do this before the test calls.
7. **Real swaps on, before recording** (so the risk dialog doesn't eat screen time): Agents → **Saver** → scroll to **Real swaps with Jupiter** → **Real swaps off** → "I understand, turn on real swaps". Keep the default caps.
8. **Watcher on, the evening before:** Agents → **Watcher** → **Turn on Watcher**, set "Alert when a price moves by" to the lowest value, tap **Check now** once. By morning "Recent alerts" should have at least one line instead of "No alerts yet".
9. **Season rules:** Agents → **Season Agent** → **Official Season rules** → **Check for rule updates** once. It must show a source title, a date and a quote, not "No official rule notes yet".
10. **Calls on a tablet.** The tablet doesn't need a SIM. On Today tap **Try a call**: the app reserves the shared secretary line for **3 minutes** for your account ("The line is yours for 3 minutes"; a tablet without a dialer also says "No dialer on this device: the number is copied", that's normal). Within those 3 minutes the helper dials **+380 91 481 0885** from their phone, and the note lands in **Calls** on the tablet. Do one test call this way and check the note appears within about 30 s. (Call forwarding from Settings is for a phone with a SIM; skip it.)
11. **Call budget (new in 1.1.3):** each AI call ends at 3:00 (the secretary says goodbye at 2:45), and the line answers at most 30 AI minutes a day in total, 20 per account (the day resets at 03:00 Kyiv). A test call is about 1 minute, so 2–3 test calls plus the take are fine.
12. **The helper (the "caller").** A friend with a second phone. Their phone is on **speaker**, and they record it themselves (their own screen recorder with mic) or you film it with a third device. They read the caller lines from shot 1. Ask them to speak slowly.
13. **Signing test with Phantom, off camera.** Agents → Saver → Real swaps with Jupiter → pay **SOL 0.002** → get **USDC** → Get quote → Confirm in wallet → **Approve** in Phantom → wait for "Swap confirmed on mainnet" → open the Solscan link. If it works, you can approve on camera in shot 5. If it doesn't, use the "reject" path there and tell the agent what the app said. Never claim on screen that something worked unless the confirmation is in the shot. **Don't use the check-in for this test** (see 14).
14. **Daily check-in: do NOT sign it before recording.** It's one per day and the day resets at 03:00 Kyiv, so keep today's check-in for shot 4. (If it was signed by accident, shot 4 shows "Signed on Solana ✓" plus the Solscan link, which is weaker.)
15. **Tablet:** Do Not Disturb on, battery > 50 %, brightness up, good Wi-Fi, other apps closed, volume up. Hide private notifications. Clear old test calls you don't want on screen (or make sure the newest call is the one from the recording).
16. **Architecture card for shot 7:** `docs/architecture-1.1.3.png` in the repo (also `/workspace/deliverables/deck/architecture-1.1.3.png`). Put it into the edit as a still.

---

## Shot list

### Shot 1 · 0:00–0:30 · A real call, answered by the secretary (the opening)

| Time | Tablet screen / taps | Caller phone (speaker, recorded) | Caption |
|---|---|---|---|
| 0:00–0:04 | Today on the tablet. Tap **Try a call** → "The line is yours for 3 minutes". (Cut the "number is copied" toast.) | The helper dials +380 91 481 0885. | **I'm busy. My AI secretary takes the call.** Small, bottom corner, 0:00–0:06: *Filmed on an Android tablet with Phantom. Built for Solana Seeker.* |
| 0:04–0:26 | Open **Calls**. The new row shows "The secretary is on the call or writing the note…". (Split screen in the edit: tablet on the left, caller phone on the right.) | The secretary greets and asks who is calling. Caller (slowly): *"Hi, this is Olena. I'm calling Vadym."* Secretary asks what it's about. Caller: *"I paid for our lunch yesterday. Can he send me zero point zero one SOL? Please ask him to call me back today at three, it's about the contract. And remind him about our meeting on Monday."* Secretary confirms and says goodbye. Caller: *"Thanks, bye."* | **Live, on a real phone line.** |
| 0:26–0:30 | The notification for the new note slides in. | Hang up. | |

**Plan B (one device only):** Today → **Try a call**, then dial +380 91 481 0885 from any phone yourself, on **speaker**, and play Olena with the same lines. The tablet's screen recorder picks up both voices through the mic. Caption: **The secretary line answers. Live.**

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

### Shot 4 · 1:00–1:20 · Daily check-in, signed in Phantom (must-have)

| Time | Taps | Caption |
|---|---|---|
| 1:00–1:05 | **Today**: the wallet card with the real SOL balance (mainnet), then the **Daily check-in** card. | **Real balance. Solana mainnet.** |
| 1:05–1:15 | Tap **Check in**. **Phantom** opens with the transaction (network fee only). Tap **Approve**. Keep Phantom's approval screen in the shot; never the recovery phrase or password. | **Signed in my own wallet, through Mobile Wallet Adapter.** |
| 1:15–1:20 | Back in the app: "Signed on Solana ✓". Tap the Solscan link and hold 2 s on the confirmed memo transaction. | **One real mainnet transaction a day.** |

If Phantom fails: stop, fix, record this shot again. This one is not optional. Don't caption it as Seeker Season activity: Season counts Seed Vault activity on a Seeker.

### Shot 5 · 1:20–1:38 · A Jupiter swap review

| Time | Taps | Caption |
|---|---|---|
| 1:20–1:29 | **Agents** → **Saver** → **Real swaps with Jupiter** ("Mainnet · real funds"). You pay **SOL 0.005** (0.01 if you funded 0.05 SOL), you get **USDC**. **Get quote**. Show **Review before your wallet**: route, price impact, fees, "Counts toward today's cap". | **A real Jupiter quote. Fees and impact first.** |
| 1:29–1:38 | **Confirm in wallet**. Phantom opens. **Reject path (default):** Reject → "The wallet did not sign. Nothing was sent." **Approve path (only if the pre-flight test worked):** Approve → "Swap confirmed on mainnet" → Solscan 1 s. | Reject: **My wallet decides. Nothing was sent.** Approve: **Confirmed on mainnet.** |

### Shot 6 · 1:38–1:52 · Seeker Season plan and the rules watcher

| Time | Taps | Caption |
|---|---|---|
| 1:38–1:44 | **Agents** → **Season Agent** → **Open the Season plan**: Daily use ✓ (from shot 4's check-in), Explore a dApp, One useful onchain action. | **A daily plan from real actions. No points promised.** |
| 1:44–1:52 | **Official Season rules** → **Check for rule updates**. Show the source title and date and **What the Season Agent changed** with the quote. Freeze 1 s on the quote. | **It reads Solana Mobile's official pages and quotes them.** |

### Shot 7 · 1:52–2:10 · Under the hood (fast)

| Time | On screen | Caption |
|---|---|---|
| 1:52–2:00 | The architecture card (`architecture-1.1.3.png`), slow zoom from the Android app box to Solana mainnet. | **Kotlin app → Cloudflare Worker → OpenAI Realtime over a real SIP line. Mobile Wallet Adapter: Seed Vault on Seeker, Phantom here. Jupiter. Solana mainnet.** |
| 2:00–2:05 | Zoom into the green safety bar of the same card. | **Calls end at 3:00. 30 AI minutes a day, max.** |
| 2:05–2:10 | Quick cut back to the payment sheet from shot 2 (reuse the footage): the scam warning and the empty recipient. | **No payment without me. A dictated address gets a scam warning.** |

### Shot 8 · 2:10–2:25 · The three agents, and the end card

| Time | Taps | Caption |
|---|---|---|
| 2:10–2:13 | **Season Agent** tab: autopilot line "1–2 real actions a day, you sign each". | **Season Agent: suggests, never signs for me.** |
| 2:13–2:16 | **Saver** tab, then **Watcher** tab with one line in "Recent alerts" (two quick cuts). | **Saver: inside my caps. Watcher: alerts only.** |
| 2:16–2:20 | (Optional, cut first if long) Sol answers one held-mic question: *"Sol, what's left for today?"* | **Ask Sol.** |
| 2:20–2:25 | End card (still image, 5 s). | **Solarchik Assistant** · Android app built for Solana Seeker · v1.1.4 · Call the secretary: **+380 91 481 0885** · github.com/Solar-DePIN-Hub/solarchik-assistant |

---

## Optional voice-over (only if you want your own voice in the video)

Different words from the speech on purpose. Record it after the screen takes, read slowly.

- Shot 1: "Someone calls. I'm in a meeting."
- Shot 2: "Ten seconds later I know what Olena wants, and what to do next."
- Shot 4: "Every day one real transaction, signed in my own wallet."
- Shot 5: "The quote is real. The decision is mine."
- Shot 7: "A real phone line, my own wallet, and limits so nothing runs away."

---

## What to avoid on camera

- **Seeker claims:** no caption or VO saying "on my Seeker", "Seed Vault", "signed on Seeker", or that the check-ins count for Seeker Season. The device is an Android tablet and the wallet is Phantom.
- **Empty states:** "No calls yet", "No calls since yesterday", "No alerts yet", "No official rule notes yet", "Reading prices…", "Loading calls…", "—" in place of a balance. Prepare the data the evening before (pre-flight 8–10).
- **Devnet anywhere:** More → developer switch / "Force devnet", "Get devnet SOL", the old strategy market, any "(devnet)" label. Don't open the developer settings at all.
- **"Mainnet mint: coming soon"** on the agent NFT card: scroll past it, don't tap Mint. (The speech already says the NFT opens later.)
- **Experimental delegated limit:** don't turn it on in the video. It's a side feature with a red warning; showing it would need explaining.
- **Claims the video can't back up:** no caption saying "proven", "guaranteed", "earns points" or "fully tested". Show an approval only if the confirmation is in the shot.
- **Private data:** Phantom's recovery phrase and password, a personal wallet you don't want public (use the fresh demo wallet), other people's numbers, unrelated notifications.
- **Long waits:** spinners, "Preparing…", the 30 s before the note appears. Cut them.
- **Sending real money to an address a caller dictated.** Never, even for the demo.

## If something goes wrong while recording

- The secretary doesn't pick up: check the internet, wait a minute, tap **Try a call** again and call again. Plan B works with one helper phone.
- The note lands somewhere else (not in the tablet's Calls): the 3-minute reservation ran out before the call. Tap **Try a call** again right before the helper dials.
- You hear "the assistant can't take any more calls today": the daily AI minutes cap (pre-flight 11) was reached, or the starter credit ran out (pre-flight 6). Ask the agent to raise `CALL_DAILY_MIN_GLOBAL` / `CALL_DAILY_MIN_ACCOUNT` or add your Account ID to the owner list (worker settings, no app update).
- The note is slow: stop recording, wait, open **Calls** → **Refresh**, and record shot 2 separately.
- Sol doesn't answer: check the connection and ask again. The text box works too, but voice looks better.
- Phantom doesn't open: Phantom must be installed and on mainnet (Testnet mode off). Tap again; if it still fails, More → Wallet → **Forget**, then Today → **Set up wallet** again. In shot 5 you can use the reject path; shot 4 needs a real approval, so fix it and record again.
- "Checked a few minutes ago" on the rules check is the normal 5-minute server limit. Keep it.
