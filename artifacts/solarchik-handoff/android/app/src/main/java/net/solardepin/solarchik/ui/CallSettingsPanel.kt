package net.solardepin.solarchik.ui

import android.content.Context
import android.text.InputFilter
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.screen.PlayerIds
import net.solardepin.solarchik.screen.ScreenApi
import net.solardepin.solarchik.sol.OpenAiVoice

/**
 * 1.2.15 Premium-only: the answering voice (with a short preview), the greeting Sol says at call start and the
 * call language, stored on the worker per user (protected by the per-install key) and used for forwarded calls.
 */
class CallSettingsPanel(private val host: MainActivity) {
    private val ctx: Context get() = host
    val view: LinearLayout = Ui.column(ctx).apply { tag = "premium-call-settings" }

    companion object {
        val DEFAULT_VOICES = listOf("marin", "cedar", "alloy", "ash", "ballad", "coral", "sage", "shimmer", "verse")
        val LANGS = listOf("auto", "uk", "en")
        const val GREETING_MAX = 160

        /** Mirrors the worker's cleanGreeting: no control chars, quotes, brackets or markup; spaces collapsed; capped. */
        fun clean(t: String, max: Int = GREETING_MAX): String =
            t.replace(Regex("[\\u0000-\\u001f\\u007f<>{}\\[\\]`\"\\\\|]"), " ").replace(Regex("\\s+"), " ").trim().take(max)
    }

    fun load() {
        view.removeAllViews()
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.cs_loading), 12.5f), 8))
        host.scope.launch {
            val r = withContext(Dispatchers.IO) { ScreenApi.callSettings(PlayerIds.get(ctx)) }
            render(r?.first, r?.second ?: ScreenApi.CallOptions(DEFAULT_VOICES, LANGS, GREETING_MAX))
        }
    }

    private fun langLabel(l: String) = ctx.getString(when (l) { "uk" -> R.string.sec_lang_uk; "en" -> R.string.sec_lang_en; else -> R.string.sec_lang_auto })

    private fun render(s: ScreenApi.CallSettings?, o: ScreenApi.CallOptions) {
        view.removeAllViews()
        view.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.cs_title), 15f, Ui.TEXT, 800), 14))
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.cs_voice), 12.5f), 8))
        val voice = Spinner(ctx).apply {
            tag = "cs-voice"
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, o.voices.map { it.replaceFirstChar(Char::titlecase) })
            setSelection(o.voices.indexOf(s?.voice ?: "marin").coerceAtLeast(0))
        }
        view.addView(voice)
        view.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.cs_preview), Ui.Btn.SECONDARY) {
            val v = o.voices.getOrNull(voice.selectedItemPosition) ?: "marin"
            val text = clean(greeting(), o.greetingMax).ifBlank { ctx.getString(R.string.cs_preview_text) }
            val lang = if (net.solardepin.solarchik.core.AppLocale.ui(ctx).language == "uk") "uk" else "en"
            host.scope.launch(Dispatchers.IO) { runCatching { OpenAiVoice.play(ctx, text, lang, v) } }
        }.apply { tag = "cs-preview" }, 6))
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.cs_greeting), 12.5f), 12))
        greetingField = EditText(ctx).apply {
            tag = "cs-greeting"
            hint = ctx.getString(R.string.cs_greeting_hint)
            filters = arrayOf(InputFilter.LengthFilter(o.greetingMax))
            setText(s?.greeting.orEmpty())
        }
        view.addView(greetingField)
        view.addView(Ui.muted(ctx, ctx.getString(R.string.cs_greeting_help, o.greetingMax), 11.5f))
        view.addView(Ui.top(Ui.muted(ctx, ctx.getString(R.string.cs_lang), 12.5f), 12))
        val lang = Spinner(ctx).apply {
            tag = "cs-lang"
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, o.langs.map { langLabel(it) })
            setSelection(o.langs.indexOf(s?.lang ?: "auto").coerceAtLeast(0))
        }
        view.addView(lang)
        view.addView(Ui.top(Ui.button(ctx, ctx.getString(R.string.cs_save), Ui.Btn.PRIMARY) {
            save(o.voices.getOrNull(voice.selectedItemPosition) ?: "marin", clean(greeting(), o.greetingMax), o.langs.getOrNull(lang.selectedItemPosition) ?: "auto")
        }.apply { tag = "cs-save" }, 12))
    }

    private var greetingField: EditText? = null
    private fun greeting() = greetingField?.text?.toString().orEmpty()

    private fun save(voice: String, greeting: String, lang: String) {
        val w = host.wallet
        if (!w.connected) { host.toast(ctx.getString(R.string.pm_need_wallet)); return }
        host.scope.launch {
            val code = withContext(Dispatchers.IO) { ScreenApi.saveCallSettings(PlayerIds.get(ctx), w.address, voice, greeting, lang) }
            host.toast(ctx.getString(when (code) { 200 -> R.string.cs_saved; 402 -> R.string.cs_need_premium; else -> R.string.cs_failed }))
        }
    }
}
