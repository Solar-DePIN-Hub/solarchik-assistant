package net.solardepin.solarchik.ui

import android.app.Dialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import net.solardepin.solarchik.MainActivity
import net.solardepin.solarchik.R
import net.solardepin.solarchik.screen.CallActionRules
import net.solardepin.solarchik.stack.Habits
import net.solardepin.solarchik.ui.Ui.dp

/**
 * 1.2.7 "Pick your habits" (redesign 06): a bottom sheet with the 5 presets and your own, a switch each, one yellow
 * Save. Save USDC asks for your amount (≥ 0.1) and your own savings address the first time it is switched on.
 */
object HabitsSheet {
    @androidx.annotation.VisibleForTesting
    var last: Dialog? = null

    internal fun sheet(host: MainActivity, content: android.view.View): Dialog {
        val d = Dialog(host)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(content)
        d.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }
        return d
    }

    fun show(host: MainActivity, onSaved: () -> Unit) {
        val ctx = host
        val picked = LinkedHashMap<String, Boolean>()
        Habits.PRESETS.forEach { picked[it] = Habits.on(ctx, it) }
        val box = Ui.column(ctx).apply {
            tag = "habits-sheet"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Kit.S2); cornerRadii = floatArrayOf(ctx.dp(32).toFloat(), ctx.dp(32).toFloat(), ctx.dp(32).toFloat(), ctx.dp(32).toFloat(), 0f, 0f, 0f, 0f)
            }
            setPadding(ctx.dp(22), ctx.dp(12), ctx.dp(22), ctx.dp(24) + host.bottomInset)
        }
        box.addView(android.view.View(ctx).apply { background = Ui.rounded(Ui.withAlpha(Color.WHITE, 0x33), ctx.dp(3).toFloat()) },
            LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(Ui.top(Ui.display(ctx, ctx.getString(R.string.habits_title), 26f).apply { maxLines = 1 }, 20))
        box.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.habits_sub), 16f, Kit.MUTED, 600).apply { setLineSpacing(0f, 1.25f) }, 8))
        val list = Kit.list(ctx)
        lateinit var saveBtn: android.widget.TextView
        fun count() = picked.count { it.value } + Habits.customs(ctx).size
        fun paint() { saveBtn.text = ctx.resources.getQuantityString(R.plurals.habits_save, count(), count()) }
        val meta = mapOf(
            Habits.SAVE to Triple(R.drawable.lc_coins, Ui.GREEN, R.string.habit_save_sub),
            Habits.SEASON to Triple(R.drawable.lc_spark, Ui.PURPLE, R.string.habit_season_sub),
            Habits.WALLET to Triple(R.drawable.lc_wallet, Ui.GOLD, R.string.habit_wallet_sub),
            Habits.CALL to Triple(R.drawable.lc_heart, Color.parseColor("#FF8FB1"), R.string.habit_call_sub),
            Habits.WORKOUT to Triple(R.drawable.lc_dumbbell, Ui.CYAN, R.string.habit_workout_sub),
        )
        Habits.PRESETS.forEach { id ->
            val (icon, color, sub) = meta.getValue(id)
            lateinit var sw: android.widget.Switch
            sw = Kit.toggle(ctx, picked[id] == true) { on ->
                picked[id] = on
                paint()
                if (on && id == Habits.SAVE && !Habits.saveReady(ctx)) setupSave(host) { ok -> if (!ok) { picked[id] = false; sw.isChecked = false; paint() } }
                if (on && id == Habits.CALL && Habits.callContact(ctx) == null) pickContact(host) { }
            }.apply { tag = "habit-switch-$id" }
            val title = if (id == Habits.SAVE) Habits.title(ctx, id) else Habits.title(ctx, id).let { if (id == Habits.CALL) ctx.getString(R.string.habit_call) else it }
            Kit.addRow(list, Kit.row(ctx, icon, color, title, ctx.getString(sub), trailing = sw).apply { tag = "habit-row-$id"; minimumHeight = ctx.dp(66) })
        }
        Habits.customs(ctx).forEach { c ->
            Kit.addRow(list, Kit.row(ctx, R.drawable.lc_check, Ui.GREEN, c.title, ctx.getString(R.string.habit_custom_sub),
                trailing = Ui.text(ctx, ctx.getString(R.string.habit_custom_remove), 14f, Kit.RED, 800).apply {
                    minHeight = ctx.dp(44); gravity = Gravity.CENTER; setPadding(ctx.dp(8), 0, 0, 0)
                    setOnClickListener { Habits.removeCustom(ctx, c.id); last?.dismiss(); show(host, onSaved) }
                }))
        }
        Kit.addRow(list, Kit.row(ctx, null, Ui.GOLD, "", null, lead = android.widget.FrameLayout(ctx).apply {
            background = Kit.dashed(ctx, Ui.withAlpha(Ui.GOLD, 0x99), 13)
            addView(android.widget.ImageView(ctx).apply { setImageResource(R.drawable.lc_plus); setColorFilter(Ui.GOLD) }, android.widget.FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
        }, trailing = android.view.View(ctx)) { addCustom(host) { last?.dismiss(); show(host, onSaved) } }.apply {
            tag = "habit-custom-add"
            ((getChildAt(1) as LinearLayout).getChildAt(0) as android.widget.TextView).apply { text = ctx.getString(R.string.habit_custom); setTextColor(Ui.GOLD) }
        })
        box.addView(Ui.top(list, 18))
        box.addView(Ui.top(Ui.text(ctx, ctx.getString(R.string.habits_free), 14f, Kit.MUTED, 600).apply { setLineSpacing(0f, 1.2f); tag = "habits-free" }, 12))
        saveBtn = Kit.primary(ctx, "") {
            picked.forEach { (id, on) -> Habits.set(ctx, id, on && (id != Habits.SAVE || Habits.saveReady(ctx))) }
            MorningStack_touchless(ctx)
            last?.dismiss()
            onSaved()
        }.apply { tag = "habits-save" }
        box.addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(58)).apply { topMargin = ctx.dp(16) })
        paint()
        val d = sheet(host, ScrollView(ctx).apply { addView(box); isVerticalScrollBarEnabled = false })
        d.show()
        last = d
    }

    /** Re-reads the stack after a change of habits (nothing is marked done). */
    private fun MorningStack_touchless(ctx: android.content.Context) { net.solardepin.solarchik.stack.MorningStack.settle(ctx) }

    private fun addCustom(host: MainActivity, then: () -> Unit) {
        val ctx = host
        val input = EditText(ctx).apply { tag = "habit-custom-input"; hint = ctx.getString(R.string.habit_custom_hint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; textSize = 16f }
        val dlg = android.app.AlertDialog.Builder(ctx).setTitle(R.string.habit_custom_title)
            .setView(LinearLayout(ctx).apply { setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
            .setPositiveButton(R.string.habit_custom_add) { _, _ -> if (Habits.addCustom(ctx, input.text.toString()) != null) then() }
            .setNegativeButton(android.R.string.cancel, null).create()
        dlg.show()
        lastForm = dlg
    }

    @androidx.annotation.VisibleForTesting
    var lastForm: android.app.AlertDialog? = null

    /** Save USDC setup: your amount (≥ 0.1 USDC) and your own savings address. [done] true when saved. */
    fun setupSave(host: MainActivity, done: (Boolean) -> Unit) {
        val ctx = host
        val box = Ui.column(ctx, gap = 8).apply { setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0) }
        box.addView(Ui.text(ctx, ctx.getString(R.string.save_setup_body), 14f, Ui.TEXT, 500).apply { setLineSpacing(0f, 1.25f) })
        val amt = EditText(ctx).apply { tag = "save-f-amount"; hint = ctx.getString(R.string.save_f_amount); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(net.solardepin.solarchik.circle.Circle.amount(Habits.saveAmount(ctx))) }
        val to = EditText(ctx).apply { tag = "save-f-to"; hint = ctx.getString(R.string.save_f_to); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; setText(Habits.saveTo(ctx)); textSize = 14f }
        box.addView(amt); box.addView(to)
        box.addView(Ui.button(ctx, ctx.getString(R.string.circle_paste), Ui.Btn.GHOST) {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            if (t.isBlank()) host.toast(ctx.getString(R.string.circle_paste_empty)) else to.setText(t.trim())
        }.apply { textSize = 14f })
        var saved = false
        val dlg = android.app.AlertDialog.Builder(ctx).setTitle(R.string.save_setup_title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.circle_save, null).setNegativeButton(android.R.string.cancel, null).create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = Habits.parseAmount(amt.text.toString())
                val addr = net.solardepin.solarchik.circle.Circle.parseAddress(to.text.toString())
                when {
                    a == null -> host.toast(ctx.getString(R.string.save_err_amount))
                    addr.isBlank() || !CallActionRules.validAddress(addr) -> host.toast(ctx.getString(R.string.save_err_to))
                    host.wallet.connected && addr == host.wallet.address -> host.toast(ctx.getString(R.string.save_err_self))
                    else -> { Habits.setSaveAmount(ctx, a); Habits.setSaveTo(ctx, addr); saved = true; dlg.dismiss() }
                }
            }
        }
        dlg.setOnDismissListener { done(saved) }
        dlg.show()
        lastForm = dlg
    }

    /** "Call someone close": a person from the Circle with a phone number. */
    fun pickContact(host: MainActivity, done: () -> Unit) {
        val ctx = host
        val people = net.solardepin.solarchik.circle.CircleStore(ctx).all().filter { it.phone.isNotBlank() }.sortedBy { it.name.lowercase() }
        if (people.isEmpty()) { host.toast(ctx.getString(R.string.habit_pick_none)); return }
        val dlg = android.app.AlertDialog.Builder(ctx).setTitle(R.string.habit_pick_title)
            .setItems(people.map { it.name }.toTypedArray()) { _, i -> Habits.setCallContact(ctx, people[i].id); Habits.set(ctx, Habits.CALL, true); done() }
            .setNegativeButton(android.R.string.cancel, null).create()
        dlg.show()
        lastForm = dlg
    }
}
