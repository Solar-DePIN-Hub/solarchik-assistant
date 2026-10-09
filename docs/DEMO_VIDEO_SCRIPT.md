# Solarchik Assistant 1.1.2: demo video shot list (2:00–2:30)

This video plays right after the 2-minute speech (pitch-2min-en.md, kept with the Colosseum deliverables), and later on its own for Colosseum. It must work on its own: the speech explains the problem, why Seeker and the business model. The video **shows** it. Nothing from the speech is repeated word for word, so the captions below use different wording on purpose.

- Target length: **2:20** (2:00 minimum, 2:30 maximum).
- Language: **English** everywhere: app, Sol, secretary, captions.
- Most of the sound comes from the app: the secretary and Sol talk. Voice-over (VO) is optional and short. If you skip the VO, the captions are enough.
- Record the Seeker screen with the built-in screen recorder ("media + mic", sound on). Record the caller's phone separately (see shot 1). Edit in CapCut or similar: cut every wait, keep real speed for taps.

---

## Pre-flight (the evening before, or on the morning of 10 Oct before 11:00 Kyiv)

1. **Install** v1.1.2 from https://github.com/Solar-DePIN-Hub/solarchik-assistant/releases/tag/v1.1.2 and open it once. Finish onboarding. Allow **notifications** and the **microphone**.
2. **Language:** More → Language → English. **Secretary language:** More → Secretary → "Secretary speaks" → **English**. Check that this phone is the owner account ("Owner · no limits").
3. **Wallet:** Seed Vault (or Phantom/Solflare) on **mainnet** with about **0.05 SOL** and some SKR if you have it. Today → **Set up wallet** → approve. Today must show a real SOL balance (and SKR), not "—".
4. **Real swaps on, before recording** (so the risk dialog doesn't eat screen time): Agents → **Saver** → scroll to **Real swaps with Jupiter** → **Real swaps off** → "I understand, turn on real swaps". Keep the default caps.
5. **Watcher on, the evening before:** Agents → **Watcher** → **Turn on Watcher**, set "Alert when a price moves by" to the lowest value, tap **Check now** once. By morning "Recent alerts" should have at least one line instead of "No alerts yet".
6. **Season rules:** Agents → **Season Agent** → **Official Season rules** → **Check for rule updates** once. It must show a source title, a date and a quote, not "No official rule notes yet".
7. **Call forwarding (for the strongest opening):** More → Secretary → **Forward unanswered calls to Sol secretary** → secretary number **+380914810885** → Save → **Busy** → press call in the dialer. Then do one test: ask your helper to call your Seeker number, press **Decline**, and check that the note shows up in **Calls** within about 30 s. If it doesn't land in your Calls, use plan B in shot 1.
8. **The helper (the "caller").** A friend with a second phone. Their phone is on **speaker**, and they record it themselves (their own screen recorder with mic) or you film it with a third phone. They read the caller lines from shot 1. Ask them to speak slowly.
9. **Real-device signing test.** Before recording, sign one tiny transaction off camera (for example a 0.001 SOL → USDC swap) and open it on Solscan. If it works, you can approve on camera in shots 4 and 5. If it doesn't, follow the "reject" path. Never claim on screen that something worked unless the confirmation is in the shot.
10. **Daily check-in:** if you did not sign today's check-in yet, keep it for shot 5. It's one per day; the day resets at 03:00 Kyiv. If you already signed it during the test, shot 5 shows "Signed on Solana ✓" and the Solscan link instead.
11. **Phone:** Do Not Disturb on (calls allowed), battery > 50 %, brightness up, good Wi-Fi/LTE, other apps closed, volume up, not on silent. Hide private notifications. Clear old test calls you don't want on screen (or make sure the newest call is the one from the recording).

---

## Shot list

### Shot 1 · 0:00–0:32 · A real call, answered by the secretary (the opening)

**Plan A (forwarding, best):**

| Time | Seeker screen / taps | Caller phone (speaker, recorded) | Caption |
|---|---|---|---|
| 0:00–0:04 | Seeker on the home screen. Incoming call from the helper ("Olena"). You let it ring once, then press **Decline**. | Ringing. | **I'm busy. I don't pick up.** |
| 0:04–0:28 | Open the app → **Calls**. The new row shows "The secretary is on the call or writing the note…". (Use split screen in the edit: Seeker on the left, caller audio on top.) | The secretary greets and asks who is calling. Caller (slowly): *"Hi, this is Olena. I'm calling Vadym."* Secretary asks what it's about. Caller: *"I paid for our lunch yesterday. Can he send me zero point zero one SOL? And please ask him to call me back today at three. It's about the contract, it's urgent."* Secretary confirms and says goodbye. Caller: *"Thanks, bye."* | **My AI secretary picks up. Live, on a real phone line.** |
| 0:28–0:32 | The notification for the new note slides in. | Hang up. | |

**Plan B (helper calls the demo line directly):** the helper dials **+380 91 481 0885** from their phone. Open on the caller's screen dialing (filmed), then the same conversation. Seeker shows Calls with the "on the call" row. Same caption.

**Plan C (one phone only):** Today → **Try a call**, phone on **speaker**, you play Olena with the same lines. The screen recorder picks up both voices through the mic. Caption: **The secretary line answers. Live.** (Don't call it an incoming call in this version.)

Cut the wait between the hang-up and the note. Don't show a spinner for more than 1 second.

### Shot 2 · 0:32–0:52 · The note and the action cards

| Time | Taps | Caption |
|---|---|---|
| 0:32–0:40 | **Calls** → tap the new call. Show the **Secretary's note** (who, why, how urgent, callback number), then scroll the transcript for one second. | **A short note: who, what, how urgent.** |
| 0:40–0:46 | Back → **Today** → **Actions from calls**: "Pay 0.01 SOL · asked by Olena" and "Call Olena back at 15:00". On the callback card tap **Remind me** → "Reminder at 15:00". | **The call turns into next steps.** |
| 0:46–0:52 | On the payment card tap **Prepare transfer**. Show the sheet: amount filled in, **recipient field empty**, the scam warning, "I checked the recipient address myself" not ticked. Tap back. Don't type an address. | **Payments are only prepared. Empty recipient. Scam warning. My call.** |

### Shot 3 · 0:52–1:08 · Sol's morning briefing

| Time | Taps | Caption |
|---|---|---|
| 0:52–1:08 | **Today** → **Morning briefing** card → **Play briefing**. Let Sol speak for about 12–15 s (he mentions Olena's call and the callback, the wallet, the Season plan). Cut the end if it runs long. | **Every morning Sol reads me my day.** |

If the briefing starts with "No calls since yesterday", the call note hasn't synced yet: go to Calls, tap **Refresh**, and record this shot again.

### Shot 4 · 1:08–1:35 · Mainnet wallet and a Jupiter swap review

| Time | Taps | Caption |
|---|---|---|
| 1:08–1:13 | **Today**: scroll to the wallet card. Real SOL and SKR balances, mainnet. | **Real balances, Solana mainnet.** |
| 1:13–1:24 | **Agents** → **Saver** → scroll to **Real swaps with Jupiter** ("Mainnet · real funds"). You pay: **SOL**, amount **0.01**. You get: **USDC**. Tap **Get quote**. Show **Review before your wallet**: route, price impact, fees, "Counts toward today's cap". | **Jupiter quote, price impact and fees, before anything is signed.** |
| 1:24–1:35 | Tap **Confirm in wallet**. Seed Vault opens with the transaction. **Reject path (default):** tap Reject → the app shows "The wallet did not sign. Nothing was sent." **Approve path (only if the pre-flight test worked):** approve → "Swap sent" → "Swap confirmed on mainnet" → tap the Solscan link for 2 s. | Reject: **My wallet decides. Nothing was sent.** Approve: **Signed in Seed Vault. Confirmed on mainnet.** |

### Shot 5 · 1:35–2:05 · Seeker Season plan and the rules watcher

| Time | Taps / what to say | Caption |
|---|---|---|
| 1:35–1:44 | **Hold the mic** on Today and say: *"Sol, what should I do for Seeker Season today?"* Release. Sol reads the plan out loud. | **Ask Sol.** |
| 1:44–1:53 | **Agents** → **Season Agent** → **Open the Season plan**. Three items: Daily use (ticked), Explore a dApp, One useful onchain action. Tap **Check in** → wallet → **Approve** → "Today's check-in is a real mainnet transaction." If it was already signed, show "Signed on Solana ✓" and the Solscan link. If the wallet fails, cut this tap. | **A daily plan from real actions. No points promised.** |
| 1:53–2:05 | Back → **Official Season rules** → **Check for rule updates**. Show the source title and date, the summary, and **What the Season Agent changed** with the quote. Freeze 1 s on the quote. (A "checked a few minutes ago" toast is fine.) | **It reads Solana Mobile's official pages and quotes them word for word.** |

### Shot 6 · 2:05–2:20 · The three agents, and the end card

| Time | Taps | Caption |
|---|---|---|
| 2:05–2:09 | **Agents** → **Season Agent** tab: the card with Off/On, scroll to the autopilot ("1–2 real actions a day, you sign each"). | **Season Agent: suggests, never signs for me.** |
| 2:09–2:12 | **Saver** tab: the Saver card ("small saves into USDC or SKR"). | **Saver: small saves, inside my caps.** |
| 2:12–2:15 | **Watcher** tab: live SOL/SKR/JUP prices and one line in "Recent alerts". | **Watcher: alerts only. It can't trade.** |
| 2:15–2:20 | End card (still image, 5 s). | **Solarchik Assistant** · Android app for Solana Seeker · v1.1.2 · Call the secretary: **+380 91 481 0885** · github.com/Solar-DePIN-Hub/solarchik-assistant |

Optional: a 3 s cut of the Play tile (the small runner game) before the end card. Leave it out if the video is over 2:25.

---

## Optional voice-over (only if you want your own voice in the video)

Different words from the speech on purpose. Record it after the screen takes, read slowly.

- Shot 1: "Someone calls. I'm in a meeting."
- Shot 2: "Ten seconds later I know what Olena wants, and what to do next."
- Shot 4: "The quote is real. The decision is mine."
- Shot 5: "The plan only counts what I really did today."
- Shot 6: "Three agents. All off until I turn them on."

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
- The note is slow: stop recording, wait, open **Calls** → **Refresh**, and record shot 2 separately.
- Sol doesn't answer: check the connection and ask again. The text box works too, but voice looks better.
- The wallet doesn't open: the wallet app must be installed and on mainnet. Tap again, or use the reject path.
- "Checked a few minutes ago" on the rules check is the normal 5-minute server limit. Keep it.
