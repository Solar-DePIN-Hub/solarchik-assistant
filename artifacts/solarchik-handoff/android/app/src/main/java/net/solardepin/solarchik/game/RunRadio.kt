package net.solardepin.solarchik.game

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.solardepin.solarchik.R
import net.solardepin.solarchik.agents.AgentStore
import net.solardepin.solarchik.agents.Ownership
import net.solardepin.solarchik.core.AgentTier
import net.solardepin.solarchik.core.Catalog
import net.solardepin.solarchik.game.run.BanterKind
import net.solardepin.solarchik.game.run.Ev
import net.solardepin.solarchik.game.run.Phase
import net.solardepin.solarchik.game.run.RunAudio
import net.solardepin.solarchik.game.run.RunBanter
import net.solardepin.solarchik.game.run.ScriptLine
import net.solardepin.solarchik.sol.ActAgent
import net.solardepin.solarchik.sol.ActContext
import net.solardepin.solarchik.sol.SolBrain
import net.solardepin.solarchik.sol.SolChat
import net.solardepin.solarchik.sol.SolChatStore
import net.solardepin.solarchik.sol.SolEars
import net.solardepin.solarchik.sol.SolHandoff
import net.solardepin.solarchik.sol.SolRules
import net.solardepin.solarchik.sol.SolVoice
import net.solardepin.solarchik.agents.engine.Track

/**
 * Sol talks during the run (0.21.8 rewrite).
 *
 *  - Time-critical hints (hurt, combo, bonus, progress, last heart, first roof) are LOCAL lines spoken at
 *    once (their audio is prefetched at the start), and dropped if they are stale (> [Policy.HINT_MAX_AGE_MS])
 *    or the run is over. A slow AI answer to "wire ahead" can no longer arrive seconds after the wire.
 *  - The AI ([SolBrain], scene "run") is used only for GO, the clock moment, DEAD and the player's own
 *    questions; a late GO/clock answer falls back to the local line.
 *  - Player questions are queued (never dropped) and hints never interrupt them. Listening lasts up to
 *    12 s and keeps the partial transcript. An action asked for mid-run waits in [SolHandoff] for the
 *    Sol tab's confirmation card.
 *  - Captions in the wrong language are replaced by the local line.
 * UI thread only.
 */
class RunRadio(
    private val context: Context,
    private val scope: CoroutineScope,
    private val lang: String,
    private val audio: RunAudio?,
    private val onCaption: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    internal var brain = SolBrain()
    private val store = SolChatStore(context)
    init { net.solardepin.solarchik.game.run.RunBanter.load(context, lang) }
    private var voice: SolVoice? = null
    private var ears: SolEars? = null
    private var last: RunHud? = null
    private var started = false
    private var over = false
    private var lastBanter = 0L
    private var banterAfter = 0L
    private var chatOpen = false
    private var chatTurn = 0
    private var asking = 0
    private var talkingUntil = 0L
    private var lastHeartSaid = false
    /** Next local line per kind, chosen ahead so its audio is already cached when it is needed. */
    private val nextLine = mutableMapOf<BanterKind, String>()
    var live = false
    var paused = false
        set(v) { field = v; if (v && !chatOpen && asking == 0) hush() }
    var listening = false
        private set
    private var heard = ""

    private val clearCaption = Runnable { onCaption("") }
    private val unduck = Runnable { audio?.duck(false) }
    private val periodic = object : Runnable {
        override fun run() {
            val h = last
            if (live && !paused && h != null && h.phase == Phase.RUNNING) banter(RunBanter.periodicKind(h.bonus, h.combo, h.hearts), h, System.currentTimeMillis())
            main.postDelayed(this, 35_000)
        }
    }
    private val listenTimeout = Runnable { if (listening) stopListen() }

    fun begin() {
        started = false
        over = false
        lastHeartSaid = false
        main.removeCallbacks(periodic)
        main.postDelayed(periodic, 35_000)
        prefetchLines()
    }

    private fun voice(): SolVoice = voice ?: SolVoice(context).also { voice = it }

    /** Warms the TTS cache for the local lines this run will most likely use. */
    private fun prefetchLines() {
        val chapter = last?.chapter
        for (k in Policy.PREFETCH) {
            val line = RunBanter.pick(k, lang, chapter).takeIf { it.isNotEmpty() } ?: continue
            nextLine[k] = line
            voice().prefetch(line, lang)
        }
        for (s in ScriptLine.values()) voice().prefetch(scriptText(s), lang)
    }

    private fun localLine(kind: BanterKind, hud: RunHud): String {
        val line = nextLine.remove(kind) ?: RunBanter.pick(kind, lang, hud.chapter)
        RunBanter.pick(kind, lang, hud.chapter).takeIf { it.isNotEmpty() }?.let { nextLine[kind] = it; voice().prefetch(it, lang) }
        return line
    }

    private fun show(text: String) {
        onCaption(text)
        main.removeCallbacks(clearCaption)
        main.postDelayed(clearCaption, (2600L + 45L * text.length).coerceAtMost(6500L))
    }

    private fun hush() {
        talkingUntil = 0
        voice?.stop()
        audio?.duck(false)
    }

    private fun duckFor(spoken: String) {
        val ms = (55L * spoken.split(Regex("\\s+")).size + 400).coerceAtMost(9000)
        talkingUntil = maxOf(talkingUntil, System.currentTimeMillis() + ms)
        audio?.duck(true)
        main.removeCallbacks(unduck)
        main.postDelayed(unduck, talkingUntil - System.currentTimeMillis())
    }

    /** A hint/banter line: replaces what is playing (never used while the player is talking to Sol). */
    private fun speak(text: String, offline: Boolean = false) {
        val spoken = SolRules.tidy(text).trim()
        if (spoken.isEmpty()) return
        hush()
        show(if (offline) context.getString(R.string.run_offline_tag) + " · " + spoken else spoken)
        duckFor(spoken)
        voice().speak(spoken, lang)
    }

    /** An answer to the player: appended after earlier answers, never cut by hints. */
    private fun answer(text: String) {
        val spoken = SolRules.tidy(text).trim()
        if (spoken.isEmpty()) return
        show(spoken)
        duckFor(spoken)
        voice().enqueue(spoken, lang)
    }

    private fun talking() = System.currentTimeMillis() < talkingUntil
    private fun inPlayerChat() = chatOpen || asking > 0 || System.currentTimeMillis() < banterAfter

    private fun runContext(h: RunHud) = RunBanter.context(h.meters, h.chapter, h.combo, h.suns, h.hearts, lang)

    /** Game events of one step plus the HUD after it. */
    fun push(events: List<Ev>, hud: RunHud) {
        last = hud
        val now = System.currentTimeMillis()
        if (Ev.DEAD in events) over = true
        val line = RunBanter.scripted(hud.meters.toDouble(), hud.death, hud.hearts, events, GameSave.GOAL_M, lastHeartSaid)
        if (line == ScriptLine.LAST_HEART) lastHeartSaid = true
        if (line != null) scripted(line, hud, now)
        if (hud.phase == Phase.RUNNING && !started) {
            started = true
            banter(BanterKind.GO, hud, now)
        }
        for (ev in events) {
            if (line != null && ev == Ev.DEAD) continue
            RunBanter.eventToBanter(ev)?.let { banter(it, hud, now) }
        }
    }

    fun onHud(hud: RunHud) {
        last = hud
        if (hud.phase == Phase.RUNNING && !started) {
            started = true
            banter(BanterKind.GO, hud, System.currentTimeMillis())
        }
    }

    /** Scripted moment: local line at once; only the clock moment asks the AI (with a deadline). */
    private fun scripted(line: ScriptLine, hud: RunHud, at: Long) {
        if (!live || listening || inPlayerChat()) return
        lastBanter = at
        // 0.22.0: today is already signed: the 1200 m moment is a "day done" line, never "you can sign the day"
        if (line == ScriptLine.CLOCK_READY && freshState()?.signedToday == true) {
            val st = freshState()?.streak ?: 0
            speak(context.resources.getQuantityString(R.plurals.banter_clock_done, st, GameSave.GOAL_M, st))
            return
        }
        if (!Policy.aiForScript(line)) {
            if (!Policy.stale(at, System.currentTimeMillis(), Policy.HINT_MAX_AGE_MS, over, false)) speak(scriptText(line))
            return
        }
        talkingUntil = at + Policy.AI_DEADLINE_MS
        val cue = context.getString(R.string.run_cue_clock_ready, GameSave.GOAL_M)
        scope.launch {
            val r = withTimeoutOrNull(Policy.AI_DEADLINE_MS) { runCatching { ask(cue, hud) }.getOrNull() }
            if (inPlayerChat() || paused) return@launch
            speak(Policy.caption(r?.reply, lang) ?: scriptText(line))
        }
    }

    private fun scriptText(line: ScriptLine): String = when (line) {
        ScriptLine.CLOCK_READY -> context.getString(R.string.banter_clock_ready, GameSave.GOAL_M)
        ScriptLine.FIRST_ROOF -> context.getString(R.string.banter_first_roof)
        ScriptLine.LAST_HEART -> context.getString(R.string.banter_last_heart)
    }

    private fun banter(kind: BanterKind, hud: RunHud, at: Long) {
        if (!live || paused || listening || inPlayerChat()) return
        if (over && kind != BanterKind.DEAD) return
        if (kind != BanterKind.GO && kind != BanterKind.DEAD && at - lastBanter < 32_000) return
        if (talking() && kind != BanterKind.DEAD) return
        lastBanter = at
        if (!Policy.aiForBanter(kind)) {
            localLine(kind, hud).takeIf { it.isNotEmpty() }?.let { speak(it) }
            return
        }
        val cue = cueFor(kind, hud)
        val deadline = if (kind == BanterKind.DEAD) Policy.DEAD_DEADLINE_MS else Policy.AI_DEADLINE_MS
        talkingUntil = at + deadline
        scope.launch {
            val r = withTimeoutOrNull(deadline) { runCatching { ask(cue, hud) }.getOrNull() }
            if (inPlayerChat() || paused && kind != BanterKind.DEAD) return@launch
            if (over && kind != BanterKind.DEAD) return@launch
            val ai = Policy.caption(r?.reply, lang)
            if (ai != null) speak(ai) else localLine(kind, hud).takeIf { it.isNotEmpty() }?.let { speak(it) }
        }
    }

    /** The event in the app language (an English cue made Sol answer a Ukrainian player in English). */
    private fun cueFor(kind: BanterKind, h: RunHud): String = when (kind) {
        BanterKind.GO -> context.getString(R.string.run_cue_go)
        BanterKind.DEAD -> context.getString(R.string.run_cue_dead, h.meters)
        BanterKind.BONUS -> context.getString(R.string.run_cue_bonus)
        BanterKind.COMBO -> context.getString(R.string.run_cue_combo, h.combo)
        BanterKind.HURT -> context.getString(R.string.run_cue_hurt, h.hearts)
        else -> context.getString(R.string.run_cue_progress, h.meters)
    }

    private suspend fun ask(message: String, hud: RunHud?): SolBrain.Reply =
        brain.ask(message, lang, "run", runActContext(), store.turns().takeLast(6), hud?.let { runContext(it) }.orEmpty(), freshState())

    /** 0.22.0: the streak / today's CLOCK IN as it is now (Sol must not ask for 1200 m after the signature). */
    private fun freshState(): net.solardepin.solarchik.sol.SolState? =
        runCatching { net.solardepin.solarchik.sol.SolState.of(net.solardepin.solarchik.game.GameSave(context)) }.getOrNull()

    /** What Sol may refer to mid-run: the paper agents and whether I own them (no chain calls in a run). */
    private fun runActContext(): ActContext {
        val records = runCatching { AgentStore(context).agents() }.getOrDefault(emptyList())
        val agents = Catalog.skus.map { sku ->
            ActAgent(
                id = "paper:${sku.id}", name = if (lang == "uk") net.solardepin.solarchik.ui.AgentNames.uk(sku.name) else sku.name, running = false, skuId = sku.id, tier = sku.tierFor(AgentTier.FREE),
                track = Track.PAPER, owned = Ownership.ownsSku(records, sku.id),
            )
        }
        return ActContext(agents, emptyList(), false)
    }

    // ---- mic ----

    /** Call after RECORD_AUDIO was granted. */
    fun startListen() {
        if (paused || !live || listening) return
        val e = ears ?: SolEars(context).also { ears = it }
        if (!e.available()) return
        chatTurn += 1
        chatOpen = true
        if (asking == 0) hush()
        heard = ""
        listening = true
        onListening(true)
        show(context.getString(R.string.run_listening))
        audio?.play("tick")
        val turn = chatTurn
        e.listen(lang, onPartial = { heard = it; if (it.isNotBlank()) onCaption(it) }) { text ->
            if (turn != chatTurn || !listening) return@listen
            listening = false
            onListening(false)
            main.removeCallbacks(listenTimeout)
            val said = text?.takeIf { it.isNotBlank() } ?: heard
            heard = ""
            // 0.22.3: an empty first utterance (cold recogniser) used to end in silence under "Слухаю…"
            if (said.isNotBlank()) askPlayer(said) else { answer(context.getString(R.string.run_not_heard)); endChat(false) }
        }
        main.removeCallbacks(listenTimeout)
        main.postDelayed(listenTimeout, Policy.LISTEN_MS)
    }

    fun stopListen() {
        if (!listening) return
        listening = false
        onListening(false)
        main.removeCallbacks(listenTimeout)
        ears?.stop()
        val said = heard.trim()
        heard = ""
        if (said.isNotEmpty()) askPlayer(said) else { answer(context.getString(R.string.run_not_heard)); endChat(false) }
    }

    /** Every question gets an answer, in order (answers are appended to the voice queue). */
    private fun askPlayer(text: String) {
        chatOpen = false
        asking++
        show(text)
        audio?.play("tick")
        net.solardepin.solarchik.sol.SolLatency.sent(voice = true)
        scope.launch {
            try {
                // 0.21.9: the answer is spoken while it streams (first clause / sentence), not after the whole reply
                val v = voice()
                v.beginStream()
                var streamed = ""
                net.solardepin.solarchik.sol.SolLatency.request(0)
                val r = runCatching {
                    brain.ask(text, lang, "run", runActContext(), store.turns().takeLast(6), last?.let { runContext(it) }.orEmpty(), freshState()) { soFar ->
                        if (streamed.isEmpty()) net.solardepin.solarchik.sol.SolLatency.firstToken()
                        if (!SolChat.fitsLanguage(soFar, if (lang == "uk") "uk" else "en")) return@ask
                        streamed = soFar
                        show(soFar)
                        duckFor(soFar)
                        v.feed(soFar, lang, final = false)
                    }
                }.getOrNull()
                val action = r?.action
                if (action != null && action.type.needsConfirm) {
                    if (streamed.isNotBlank()) v.feed(streamed, lang, final = true)
                    SolHandoff.put(text, action)
                    answer(context.getString(R.string.run_action_later))
                } else {
                    val said = Policy.caption(r?.reply, lang)
                    if (said != null && streamed.isNotBlank() && said.startsWith(streamed.trim().take(24))) {
                        val spoken = SolRules.tidy(said).trim()
                        show(spoken); duckFor(spoken)
                        v.feed(spoken, lang, final = true)
                    } else if (said != null) {
                        if (streamed.isNotBlank()) v.feed(streamed, lang, final = true)
                        answer(said)
                    } else if (r == null || r.offline) { audio?.play("hurt"); answer(SolChat.offlineLine(lang)) }
                    else if (streamed.isNotBlank()) v.feed(streamed, lang, final = true)
                    else answer(context.getString(R.string.run_not_heard)) // 0.22.3: never silent

                }
            } finally {
                asking--
                endChat(true)
            }
        }
    }

    private fun endChat(cooldown: Boolean) {
        if (asking > 0 || listening) return
        chatOpen = false
        if (cooldown) {
            banterAfter = System.currentTimeMillis() + 30_000
            lastBanter = System.currentTimeMillis()
        }
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        ears?.stop()
        voice?.shutdown()
    }

    /** Pure decisions, unit-tested (RunRadioPolicyTest). */
    object Policy {
        const val HINT_MAX_AGE_MS = 800L
        const val AI_DEADLINE_MS = 2_500L
        const val DEAD_DEADLINE_MS = 6_000L
        const val LISTEN_MS = 12_000L
        val PREFETCH = listOf(BanterKind.GO, BanterKind.HURT, BanterKind.COMBO, BanterKind.BONUS, BanterKind.CHAPTER, BanterKind.DEAD)

        /** Only these moments are worth a model round trip; everything time-critical is local. */
        fun aiForBanter(kind: BanterKind): Boolean = kind == BanterKind.GO || kind == BanterKind.DEAD
        fun aiForScript(line: ScriptLine): Boolean = line == ScriptLine.CLOCK_READY

        /** A hint is dropped once it is older than [maxAgeMs] or the run ended (unless it is about the end). */
        fun stale(at: Long, now: Long, maxAgeMs: Long, over: Boolean, aboutEnd: Boolean): Boolean =
            now - at > maxAgeMs || (over && !aboutEnd)

        /** An AI caption is used only when it is non-blank and in the run's language. */
        fun caption(reply: String?, lang: String): String? =
            reply?.trim()?.takeIf { it.isNotEmpty() && SolChat.fitsLanguage(it, if (lang == "uk") "uk" else "en") }
    }
}
