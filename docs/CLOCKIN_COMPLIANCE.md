> Historical: this checklist is for my first CLOCK IN build (the game, Solarchik 0.20.5, repo Solar-DePIN-Hub/Solarchik). The current app is described in the [README](../README.md).

# CLOCK IN compliance checklist (Solarchik 0.20.5)

Checked 1–2 Oct 2026 against the official documents. Status: PASS / FAIL / OWNER (only Vadym can do it) / UNKNOWN.

Sources
- [T] Terms and Conditions (PDF): https://solanamobile.radiant.nexus/legal/clock-in-terms.pdf
- [S] Official site (rules, FAQ, submission form): https://solanamobile.radiant.nexus/
- [B] Solana Mobile blog announcement: https://solanamobile.com/blog/clock-in-the-solana-mobile-hackathon
- [L] dApp Store listing guidelines: https://docs.solanamobile.com/dapp-store/listing-page-guidelines
- [C] dApp Store publishing checklist: https://docs.solanamobile.com/dapp-store/checklist
- [N] Submit a new app: https://docs.solanamobile.com/dapp-store/submit-new-app
- [P] Publisher Policy: https://solanamobile.com/publisher-policy-web
- [K] SKR token: https://docs.solanamobile.com/solana-mobile-stack/skr

| # | Requirement | Source | Status | Evidence |
|---|---|---|---|---|
| 1 | Functional Android APK | T 6.3, 6.4(a); S; B | PASS (box) | 0.20.5 release APK built and signed (apksigner verifies); Robolectric UI tests render all tabs. Not run on a phone here (no emulator by rule). |
| 2 | Integrate Solana Mobile Stack + Mobile Wallet Adapter | T 6.3; S | PASS | `mobile-wallet-adapter-clientlib-ktx:2.0.7`; `<queries>` WALLET_ASSOCIATE; `ComplianceTest.mobileWalletAdapterIsWired` |
| 3 | Interact meaningfully with Solana | T 6.3; S | PASS | CLOCK IN memo tx, Metaplex Core mint (DevnetMintIT simulation of all 10 mints), devnet fee transfer+memo |
| 4 | Built for mobile, not a web port / PWA wrapper | T 6.2; S | PASS | 100 % native Kotlin Views; `ComplianceTest` asserts no `android.webkit.WebView` in sources |
| 5 | GitHub repo with source | T 6.4(b); S form `repoUrl` | PASS | https://github.com/Solar-DePIN-Hub/Solarchik is PUBLIC; native code on branch `native-full` (default branch is `main`, so link the branch) |
| 6 | Demo video (site: "a demo of three minutes", runs on a device) | T 6.4(c); S "What Counts as a Submission"; form `videoUrl` | OWNER | No video in the repo. YouTube / Loom / public Drive link required |
| 7 | Pitch deck / brief presentation | T 6.4(d); form `deckUrl` | OWNER | PITCH.md is 13 lines of text; a deck URL is required |
| 8 | Direct-download APK URL | form `apkUrl` ("direct download link") | OWNER | No GitHub release exists; `public/Solarchik-CLOCK-IN-0.19.51.apk` is the old build |
| 9 | Project started ≤ 3 months before launch (8 Sep 2026); significant new mobile work | T 6.1; form `builtLast3Months` | PASS (repo) / OWNER (answer) | Repo created 2026-09-19; native app built during the hackathon |
| 10 | Form answers: prior VC/angel funding, previous hackathon win, porting / new mobile work, SKR integration | S form | OWNER | Draft answers below |
| 11 | Eligibility: 18+, eligible country (Ukraine listed; occupied regions excluded), not sanctioned | T 3, 4 | OWNER | Vadym confirms |
| 12 | One submission per contestant; team fixed at submission; Team Representative | T 5.2, 9.5; S | OWNER | |
| 13 | No malware, no IP infringement; fonts licensed | T 6.3 | PASS | Manrope/Unbounded OFL licences in `assets/licenses` |
| 14 | Judging 25 % each: stickiness, UX, innovation, presentation | T 8.1; S | n/a | Covered by the video/deck |
| 15 | SKR integration prize (optional; staking does not qualify) | B; S FAQ; K | FAIL (not integrated) | SKR (`SKRbvo6G…`) exists only on mainnet; this work is devnet-only, so no SKR integration was added |
| 16 | Winners: publish on Solana dApp Store within 30 days; KYC via Sumsub | T 9.3, 10 | OWNER (later) | |
| 17 | dApp Store: signed release APK, not debug | C; N | PASS (box key) / OWNER | Release APK signed with the BOX TEST KEY. The store build must use the owner's production key; 0.19.51 was signed with `CN=Solar DePin` |
| 18 | dApp Store icon 512×512 | L | PASS | `docs/store/icon-512.png`, rendered from the APK launcher drawable by `ComplianceTest.storeIcon512FromLauncherDrawable` |
| 19 | Screenshots ≥ 1080 px both sides, same orientation and aspect; at least 4 for listing | L | PASS | `docs/store/screenshots/*.png`, 1080×2400 portrait |
| 20 | Short description ≤ 30 chars | L | PASS | "Solar robot, daily CLOCK IN" (27) |
| 21 | Privacy policy disclosing data use | P | PASS | `PRIVACY.md`, linked in Settings → Privacy & data |
| 22 | Users can delete their data | P | PASS | Settings → Delete my data (`AppData.wipe`, `ComplianceTest.deleteMyDataWipesEveryStore`) |
| 23 | Minimal permissions | P (user data) | PASS | Removed unused READ_CALL_LOG, READ_PHONE_STATE, MODIFY_AUDIO_SETTINGS; 0.20.5 also drops READ_CONTACTS (call secretary relies on the OS passing only non-contacts) and REORDER_TASKS (leaked from androidx.test via MWA clientlib-ktx, now excluded); `ComplianceTest.onlyUsedPermissions`, `SecretaryTest.noContactsOrTestPermissionsInTheApp` |
| 24 | No misleading brand use | P | NOTE | App label "Solarchik CLOCK IN" reuses the hackathon name; consider plain "Solarchik" for the store |
| 25 | targetSdk / release flags | C | PASS | target 35, min 26, `debuggable=false`, `allowBackup=false`, cleartext off |
| 26 | Real money disclosed | P | PASS | Only the optional call-secretary credit uses mainnet USDC, paid in the user's own wallet via a Solana Pay request; disclosed in Settings and PRIVACY.md. Agents stay paper/devnet |

## Draft form answers (for Vadym to check)
- PROJECT TITLE: Solarchik
- PRIOR VC/ANGEL FUNDING: (Vadym)
- BUILT IN THE LAST 3 MONTHS: Yes (repo created 19 Sep 2026)
- PREVIOUS HACKATHON WIN: (Vadym; put N/A if none)
- NEW MOBILE DEVELOPMENT: A fully native Kotlin Android app (no WebView): native yard, SurfaceView runner, MWA 2.0 connect / sign-and-send with sign-only fallback, hand-encoded Metaplex Core mints, on-phone strategy desk on live public data with risk caps and WorkManager background ticks, devnet fee payment with de-dup memo, notifications, voice chat with Sol (SpeechRecognizer + TTS), EN/UK localisation, accessibility pass, data deletion.
- SKR INTEGRATION: No (devnet-only build).
- DECK URL / DEMO VIDEO URL / APK URL: (Vadym)
- REPOSITORY URL: https://github.com/Solar-DePIN-Hub/Solarchik/tree/native-full
