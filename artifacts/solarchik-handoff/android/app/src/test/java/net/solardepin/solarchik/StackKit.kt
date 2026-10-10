package net.solardepin.solarchik

import android.view.View
import net.solardepin.solarchik.ui.TodayScreen
import org.robolectric.shadows.ShadowLooper

/** 1.2.9 test helper: Later → "Tomorrow" on every card, then the explicit "Clock in" tap. */
object StackKit {
    private fun find(v: View, tag: String): View? = v.findViewWithTag(tag)
    fun clear(a: MainActivity, max: Int = 12, clockIn: Boolean = true) {
        val today = a.screen(MainActivity.Tab.TODAY) as TodayScreen
        repeat(max) {
            val b = find(a.window.decorView, "stack-later") ?: return@repeat
            b.performClick(); ShadowLooper.idleMainLooper()
            today.lastLater?.let { dl -> dl.listView.performItemClick(null, 1, 1L); ShadowLooper.idleMainLooper() }
        }
        if (clockIn) find(a.window.decorView, "stack-clock-in")?.let { it.performClick(); ShadowLooper.idleMainLooper() }
    }
}
