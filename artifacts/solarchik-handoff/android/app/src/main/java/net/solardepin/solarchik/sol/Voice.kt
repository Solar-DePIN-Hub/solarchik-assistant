package net.solardepin.solarchik.sol

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.solardepin.solarchik.core.SolarchikConfig
import net.solardepin.solarchik.solana.Rpc
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sol's voice (0.21.8). A sentence queue: lines are spoken one after another, never dropped, and a
 * streamed reply starts speaking on its first sentence ([feed]).
 *
 * Engines, in order: OpenAI gpt-4o-mini-tts on the solarchik-screen worker (24 kHz PCM streamed into
 * an AudioTrack, cached per line, voice [OpenAiVoice.voice], default "marin") → the market Gemini WAV
 * ([NeuralVoice]) → the phone's system TTS (robotic; only when both servers fail). Which engine played
 * and how long the first audio took is logged (`SolVoice`) and kept in [VoiceStats] for Settings.
 */
class SolVoice(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = kotlinx.coroutines.channels.Channel<Pair<String, String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var worker: Job? = null
    private var player: MediaPlayer? = null
    @Volatile private var track: android.media.AudioTrack? = null
    private var system: SystemVoice? = null
    /** How much of the streamed reply [feed] has queued already. */
    private var fedChars = 0
    @Volatile var speaking = false
        private set
    /** Bumped by [stop]: a line being written to the track sees it and quits. */
    @Volatile private var gen = 0
    var missingLanguage: String? = null
        private set
    /** Engine that voiced the last line: "openai:<voice>", "gemini" or "system". */
    var lastSource: String = ""
        private set
    /** 1.1.7: lines actually heard since the last [speak]/[stop]; [onIdle] gets it when the queue runs dry. */
    @Volatile private var played = 0
    /** Main thread, after the last queued line ended (not after [stop]): how many lines were heard. */
    var onIdle: ((played: Int) -> Unit)? = null

    /** Replaces whatever is playing with [text] (split into sentences). False only if [lang] has no offline voice. */
    fun speak(text: String, lang: String): Boolean {
        stop()
        enqueue(text, lang)
        return missingLanguage != lang
    }

    /** Adds [text] after what is already queued (RunRadio: player answers are never dropped). */
    fun enqueue(text: String, lang: String) {
        val busy = speaking || worker?.isActive == true
        sentences(SpeechText.speakable(text, lang)).forEachIndexed { i, line ->
            queue.trySend(line to lang)
            // 0.21.9: a line that has to wait behind another one is fetched now, so it starts without a gap
            if (busy || i > 0) prefetchLine(line, lang)
        }
        ensureWorker()
    }

    /** In-flight prefetches by cache key: [playLine] waits for one instead of fetching the same line twice. */
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<java.io.File?>>()

    private fun prefetchLine(line: String, lang: String) {
        val v = OpenAiVoice.voice(app)
        val k = OpenAiVoice.key(line, lang, v)
        if (inflight.containsKey(k) || OpenAiVoice.cached(app, line, lang, v) != null) return
        val d = scope.async(Dispatchers.IO) { try { OpenAiVoice.fetchToCache(app, line, lang, v) } finally { inflight.remove(k) } }
        inflight[k] = d
    }

    /** Streaming reply: [soFar] is the whole text so far; complete sentences are queued as they arrive. */
    fun feed(soFar: String, lang: String, final: Boolean) {
        if (soFar.length < fedChars) fedChars = 0
        val rest = soFar.substring(fedChars)
        var cut = if (final) rest.length else lastSentenceEnd(rest)
        // 0.21.9: the first words of a reply start speaking at the first clause (", " / " — " after
        // ~30 chars) instead of waiting for the whole first sentence
        if (cut <= 0 && !final && fedChars == 0) cut = firstClauseEnd(rest)
        if (cut <= 0) return
        val chunk = rest.substring(0, cut)
        fedChars += cut
        enqueue(chunk, lang)
    }

    /** Starts a fresh streamed reply (resets [feed]) without interrupting a line already playing. */
    fun beginStream() { fedChars = 0 }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val (line, lang) = queue.tryReceive().getOrNull() ?: break
                speaking = true
                try { if (playLine(line, lang)) played++ } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) {
                    runCatching { android.util.Log.w("SolVoice", "line failed: ${t.message}") }
                }
            }
            speaking = false
            val n = played
            played = 0
            onIdle?.invoke(n)
        }
    }

    private suspend fun playLine(line: String, lang: String): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        var firstAudio = -1L
        val v = OpenAiVoice.voice(app)
        val g = gen
        inflight[OpenAiVoice.key(line, lang, v)]?.let { runCatching { it.await() } }
        if (gen != g) return false
        val ok = withContext(Dispatchers.IO) {
            OpenAiVoice.play(app, line, lang, v, alive = { gen == g }, onTrack = { track = it }) {
                if (firstAudio < 0) { firstAudio = android.os.SystemClock.elapsedRealtime() - t0; SolLatency.firstAudio(app) }
            }
        }
        if (gen != g) return false
        track = null
        if (ok) { note("openai:$v", firstAudio); return true }
        val file = NeuralVoice.clip(app, line, lang)
        if (file != null) {
            note("gemini", android.os.SystemClock.elapsedRealtime() - t0)
            SolLatency.firstAudio(app)
            playFile(file)
            return true
        }
        note("system", android.os.SystemClock.elapsedRealtime() - t0)
        SolLatency.firstAudio(app)
        val sys = system ?: SystemVoice(app).also { system = it }
        if (!sys.speakAndWait(line, lang)) { missingLanguage = lang; return false }
        return true
    }

    private fun note(source: String, ms: Long) {
        lastSource = source
        VoiceStats.record(app, source, ms)
        runCatching { android.util.Log.i("SolVoice", "engine=$source firstAudioMs=$ms") }
    }

    private suspend fun playFile(file: java.io.File) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); if (player === it) player = null; if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
                setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resumeWith(Result.success(Unit)); true }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.getOrNull()
        if (mp == null) { cont.resumeWith(Result.success(Unit)); return@suspendCancellableCoroutine }
        player = mp
        cont.invokeOnCancellation { runCatching { mp.stop() }; runCatching { mp.release() } }
    }

    /** Warm the cache for a line that is likely to be spoken soon (OpenAI voice). */
    fun prefetch(text: String, lang: String) {
        val v = OpenAiVoice.voice(app)
        scope.launch(Dispatchers.IO) { sentences(SpeechText.speakable(text, lang)).forEach { OpenAiVoice.fetchToCache(app, it, lang, v) } }
    }

    fun stop() {
        gen++
        played = 0
        while (queue.tryReceive().isSuccess) { /* drop queued lines */ }
        inflight.clear()
        worker?.cancel()
        worker = null
        speaking = false
        fedChars = 0
        track?.let { runCatching { it.pause(); it.flush() } }
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        system?.stop()
    }

    fun shutdown() {
        stop()
        system?.shutdown()
        scope.cancel()
    }

    companion object {
        fun localeOf(lang: String): Locale = if (lang == "uk") Locale("uk", "UA") else Locale.US

        private val END = Regex("[.!?…]+[\\\"»”)]*(\\s+|$)")

        /** Index just past the last complete sentence in [text] (0 when none is complete yet). */
        fun lastSentenceEnd(text: String): Int {
            // a terminator at the very end may still grow ("3." → "3.5"): only one followed by a space counts
            var last = 0
            for (m in END.findAll(text)) if (m.groupValues[1].isNotEmpty()) last = m.range.last + 1
            return last
        }

        private val CLAUSE = Regex("(,|;|:|\\s[—–-])\\s")

        /** Index just past the first clause break at or after 30 chars (0 when none yet). Decimal commas ("0,1") have no space and never count. */
        fun firstClauseEnd(text: String): Int {
            for (m in CLAUSE.findAll(text)) if (m.range.first >= 30) return m.range.last + 1
            return 0
        }

        /** Speakable sentences (short fragments are merged into the next one). */
        fun sentences(text: String): List<String> {
            val t = text.replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            var from = 0
            for (m in END.findAll(t)) {
                val end = m.range.last + 1
                val s = t.substring(from, end).trim()
                if (s.isNotEmpty()) {
                    if (out.isNotEmpty() && out.last().length < 18) out[out.lastIndex] = out.last() + " " + s else out += s
                }
                from = end
            }
            val tail = t.substring(from).trim()
            if (tail.isNotEmpty()) { if (out.isNotEmpty() && out.last().length < 18) out[out.lastIndex] = out.last() + " " + tail else out += tail }
            // 1.2.1: the worker speaks at most 400 characters a line; a longer sentence is split at a comma or space
            return out.flatMap { split(it, MAX_LINE) }
        }

        const val MAX_LINE = 380

        fun split(s: String, max: Int): List<String> {
            if (s.length <= max) return listOf(s)
            val out = mutableListOf<String>()
            var rest = s
            while (rest.length > max) {
                val window = rest.substring(0, max)
                var cut = maxOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(" — ")).let { if (it > max / 3) it + 1 else -1 }
                if (cut <= 0) cut = window.lastIndexOf(' ').takeIf { it > max / 3 } ?: max
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }
}

/** Last voice engine and first-audio latency, for Settings (0.21.8). */
object VoiceStats {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("solarchik-voice", Context.MODE_PRIVATE)
    fun record(ctx: Context, source: String, ms: Long) { prefs(ctx).edit().putString("source", source).putLong("ms", ms).putLong("at", System.currentTimeMillis()).apply() }
    fun source(ctx: Context): String = prefs(ctx).getString("source", "").orEmpty()
    fun ms(ctx: Context): Long = prefs(ctx).getLong("ms", -1)
}

/**
 * OpenAI gpt-4o-mini-tts through the worker (`/sol/tts?fmt=pcm`): 24 kHz mono s16le, streamed and
 * played while it downloads; every line is cached (cacheDir/sol-voice-oa, newest 160) so repeats and
 * prefetched run banter play instantly.
 */
object OpenAiVoice {
    const val RATE = 24_000
    val VOICES = listOf("marin", "cedar", "coral", "shimmer")
    const val DEFAULT = "marin"
    private const val KEEP = 160
    private val client by lazy { Rpc.client.newBuilder().callTimeout(30, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build() }
    /** Test seam: replaces the HTTP call (returns the whole PCM). */
    @Volatile var fetcher: (suspend (String, String, String) -> ByteArray?)? = null

    fun voice(ctx: Context): String = ctx.applicationContext.getSharedPreferences("solarchik-voice", Context.MODE_PRIVATE).getString("voice", null)?.takeIf { it in VOICES } ?: DEFAULT
    fun setVoice(ctx: Context, v: String) { if (v in VOICES) ctx.applicationContext.getSharedPreferences("solarchik-voice", Context.MODE_PRIVATE).edit().putString("voice", v).apply() }

    fun url(text: String, lang: String, voice: String): String =
        SolarchikConfig.SOL_TTS_URL + "?fmt=pcm&lang=" + (if (lang == "uk") "uk" else "en") + "&voice=" + voice + "&text=" + java.net.URLEncoder.encode(text.take(400), "UTF-8")

    fun key(text: String, lang: String, voice: String): String =
        MessageDigest.getInstance("SHA-1").digest("oa|$lang|$voice|$text".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dir(ctx: Context) = java.io.File(ctx.cacheDir, "sol-voice-oa").apply { mkdirs() }
    fun cached(ctx: Context, text: String, lang: String, voice: String): java.io.File? =
        java.io.File(dir(ctx), key(text, lang, voice) + ".pcm").takeIf { it.length() > 2000 }

    private fun trim(ctx: Context) {
        dir(ctx).listFiles { f -> f.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** Downloads [text] into the cache without playing it. */
    fun fetchToCache(ctx: Context, text: String, lang: String, voice: String): java.io.File? {
        if (text.isBlank()) return null
        cached(ctx, text, lang, voice)?.let { return it }
        return runCatching {
            val bytes = fetcher?.let { f -> kotlinx.coroutines.runBlocking { f(text, lang, voice) } } ?: client.newCall(Request.Builder().url(url(text, lang, voice)).build()).execute().use { res ->
                if (res.isSuccessful && res.header("content-type").orEmpty().startsWith("audio/")) res.body?.bytes() else null
            }
            if (bytes == null || bytes.size < 2000) return null
            val out = java.io.File(dir(ctx), key(text, lang, voice) + ".pcm")
            val tmp = java.io.File(out.path + ".part")
            tmp.writeBytes(bytes); tmp.renameTo(out); trim(ctx)
            out
        }.getOrNull()
    }

    /**
     * Plays [text] (blocking, IO thread): from the cache, or streamed from the worker while caching.
     * [onFirstAudio] fires when the first samples are handed to the track. False = nothing played.
     */
    fun play(ctx: Context, text: String, lang: String, voice: String, alive: () -> Boolean = { true }, onTrack: (android.media.AudioTrack) -> Unit = {}, onFirstAudio: () -> Unit = {}): Boolean {
        if (text.isBlank()) return true
        val hit = cached(ctx, text, lang, voice)
        val track = newTrack() ?: return false
        onTrack(track)
        var frames = 0L
        try {
            track.play()
            var odd: Int = -1
            fun put(b: ByteArray, o: Int, l: Int) {
                // non-blocking writes, so a stop() (pause + flush) never leaves this thread stuck
                var off = o; var len = l
                while (len > 0 && alive()) {
                    val n = track.write(b, off, len, android.media.AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) throw java.io.IOException("track $n")
                    if (n == 0) Thread.sleep(8)
                    off += n; len -= n
                }
                if (!alive()) throw java.io.InterruptedIOException("stopped")
            }
            fun write(buf: ByteArray, n: Int) {
                var off = 0
                var len = n
                if (odd >= 0 && len > 0) { put(byteArrayOf(odd.toByte(), buf[0]), 0, 2); frames++; off = 1; len--; odd = -1 }
                val even = len and 1.inv()
                if (even > 0) { if (frames == 0L) onFirstAudio(); put(buf, off, even); frames += even / 2 }
                if (len and 1 == 1) odd = buf[off + even].toInt() and 0xFF
            }
            if (hit != null) {
                hit.setLastModified(System.currentTimeMillis())
                val b = hit.readBytes(); write(b, b.size)
            } else {
                val f = fetcher
                if (f != null) {
                    val b = kotlinx.coroutines.runBlocking { f(text, lang, voice) } ?: return false.also { track.release() }
                    if (b.size < 2000) { track.release(); return false }
                    write(b, b.size)
                } else {
                    val req = Request.Builder().url(url(text, lang, voice)).build()
                    val ok = client.newCall(req).execute().use { res ->
                        if (!res.isSuccessful || !res.header("content-type").orEmpty().startsWith("audio/")) return@use false
                        val src = res.body?.byteStream() ?: return@use false
                        val out = java.io.File(dir(ctx), key(text, lang, voice) + ".pcm")
                        val tmp = java.io.File(out.path + ".part")
                        var total = 0L
                        tmp.outputStream().use { file ->
                            val buf = ByteArray(4800)
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                if (n == 0) continue
                                file.write(buf, 0, n); total += n
                                write(buf, n)
                            }
                        }
                        if (total > 2000) { tmp.renameTo(out); trim(ctx) } else tmp.delete()
                        total > 2000
                    }
                    if (!ok) { track.release(); return false }
                }
            }
            // let the tail play out
            val deadline = android.os.SystemClock.elapsedRealtime() + frames * 1000 / RATE + 1500
            while (alive() && track.playState == android.media.AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < frames && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            return frames > 0
        } catch (_: Throwable) {
            return frames > 0
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun newTrack(): android.media.AudioTrack? = runCatching {
        val min = android.media.AudioTrack.getMinBufferSize(RATE, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        android.media.AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(RATE).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, RATE)) // ~0.5 s
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build()
    }.getOrNull()
}

/** Neural clips from the server, cached in cacheDir/sol-voice (newest 80 kept). */
object NeuralVoice {
    const val VOICE = "Sulafat"
    private const val KEEP = 80
    private val client by lazy { Rpc.client.newBuilder().callTimeout(9, TimeUnit.SECONDS).build() }
    /** Test seam: replaces the HTTP call. */
    @Volatile var fetcher: (suspend (String, String) -> ByteArray?)? = null

    fun key(text: String, lang: String, voice: String = VOICE): String {
        val d = MessageDigest.getInstance("SHA-1").digest("$lang|$voice|$text".toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun dir(ctx: Context) = java.io.File(ctx.cacheDir, "sol-voice").apply { mkdirs() }

    fun cached(ctx: Context, text: String, lang: String): java.io.File? =
        java.io.File(dir(ctx), key(text, lang) + ".wav").takeIf { it.length() > 1000 }

    suspend fun clip(ctx: Context, text: String, lang: String): java.io.File? = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext null
        cached(ctx, text, lang)?.let { it.setLastModified(System.currentTimeMillis()); return@withContext it }
        val l = if (lang == "uk") "uk" else "en"
        val bytes = try {
            val f = fetcher
            if (f != null) f(text.take(400), l) else download(text.take(400), l)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
        if (bytes == null || bytes.size < 1000 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return@withContext null
        val d = dir(ctx)
        val out = java.io.File(d, key(text, lang) + ".wav")
        val tmp = java.io.File(d, out.name + ".part")
        tmp.writeBytes(bytes)
        tmp.renameTo(out)
        d.listFiles { f -> f.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        out
    }

    private fun download(text: String, lang: String): ByteArray? {
        val body = buildJsonObject { put("text", text); put("language", lang); put("voice", VOICE) }.toString()
        val req = Request.Builder().url(SolarchikConfig.SOL_VOICE_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { res ->
            val type = res.header("content-type").orEmpty()
            if (res.isSuccessful && type.startsWith("audio/")) res.body?.bytes() else null
        }
    }
}

/** The phone's own TTS (offline fallback). */
private class SystemVoice(context: Context) {
    private var ready = false
    private var pending: Pair<String, String>? = null
    private val done = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        pending?.let { (t, l) -> pending = null; speak(t, l) }
    }.also {
        it.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { done.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { id?.let { done.remove(it)?.complete(Unit) } }
        })
    }

    fun speak(text: String, lang: String, id: String = "sol-" + text.hashCode()): Boolean {
        if (!ready) { pending = text to lang; return true }
        val r = tts.setLanguage(SolVoice.localeOf(lang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        return true
    }

    /** Speaks and suspends until the line is done (or ~10 s). */
    suspend fun speakAndWait(text: String, lang: String): Boolean {
        val id = "sol-" + System.nanoTime()
        val d = kotlinx.coroutines.CompletableDeferred<Unit>()
        done[id] = d
        if (!speak(text, lang, id)) { done.remove(id); return false }
        kotlinx.coroutines.withTimeoutOrNull(2_000L + text.length * 90L) { d.await() }
        done.remove(id)
        return true
    }

    fun stop() = runCatching { tts.stop() }
    fun shutdown() = runCatching { tts.shutdown() }
}

/**
 * One-shot speech recognition. Callbacks arrive on the main thread.
 *
 * 0.21.9 (voice gap): the recognizer is asked for a short end-of-speech silence (700 ms) and the reply no
 * longer waits for its final result: [END_GRACE_MS] after onEndOfSpeech the last partial is used, and a
 * partial that stays unchanged for [STABLE_MS] (≥ 2 words) also ends the turn. Stages go to [SolLatency].
 */
class SolEars(private val context: Context) {
    private var rec: SpeechRecognizer? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var finished = true
    private var lastPartial = ""
    private var lastPartialAt = 0L
    private var endTimer: Runnable? = null
    private var stableTimer: Runnable? = null
    private var finishHook: ((String?) -> Unit)? = null
    /** 1.2.7: the mic level (dB, about -2..10) for the voice sheet's waveform. */
    var onLevel: ((Float) -> Unit)? = null

    /** 1.0.0 hold-to-talk: the finger left the mic. Use what was heard so far; keep listening if nothing yet. */
    fun finishNow(): Boolean {
        if (finished || lastPartial.isBlank()) return false
        finishHook?.invoke(lastPartial)
        return true
    }

    val listening: Boolean get() = !finished

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(lang: String, onPartial: (String) -> Unit, onDone: (String?) -> Unit) {
        stop()
        finished = false
        lastPartial = ""
        lastPartialAt = 0L
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        rec = r
        fun finish(text: String?) {
            if (finished) return
            finished = true
            clearTimers()
            if (!text.isNullOrBlank()) SolLatency.sttFinal()
            onDone(text)
            stop()
        }
        finishHook = { finish(it) }
        fun speechEnd() {
            // t0 = when the last new word arrived (closest the app can see to "the player stopped talking")
            if (SolLatency.current?.let { it.voice && it.stt < 0 } != true) SolLatency.speechEnded(lastPartialAt.takeIf { it > 0 })
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { onLevel?.invoke(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                speechEnd()
                SolLatency.endOfSpeech()
                endTimer?.let { main.removeCallbacks(it) }
                endTimer = Runnable { if (lastPartial.isNotBlank()) finish(lastPartial) }.also { main.postDelayed(it, END_GRACE_MS) }
            }
            override fun onError(error: Int) {
                // "no match" / timeout after we already heard words: use them instead of dropping the turn
                if (lastPartial.isNotBlank()) { speechEnd(); finish(lastPartial) } else finish(null)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: lastPartial
                speechEnd()
                finish(text.ifBlank { null })
            }
            override fun onPartialResults(partial: Bundle?) {
                val p = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (p.isEmpty() || finished) return
                if (p != lastPartial) { lastPartial = p; lastPartialAt = android.os.SystemClock.elapsedRealtime() }
                onPartial(p)
                stableTimer?.let { main.removeCallbacks(it) }
                val seen = p
                stableTimer = Runnable {
                    if (!finished && lastPartial == seen && seen.split(Regex("\\s+")).size >= 2) { speechEnd(); finish(seen) }
                }.also { main.postDelayed(it, STABLE_MS) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val tag = SolVoice.localeOf(lang).toLanguageTag()
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        })
    }

    private fun clearTimers() {
        endTimer?.let { main.removeCallbacks(it) }
        stableTimer?.let { main.removeCallbacks(it) }
        endTimer = null
        stableTimer = null
    }

    fun stop() {
        clearTimers()
        finished = true
        rec?.let { runCatching { it.cancel(); it.destroy() } }
        rec = null
    }

    companion object {
        const val SILENCE_MS = 700L
        /** After onEndOfSpeech, wait this long for the final result, then use the last partial. */
        const val END_GRACE_MS = 350L
        /** A partial unchanged this long ends the turn even if the recognizer has not noticed the silence. */
        const val STABLE_MS = 1_300L
    }
}
