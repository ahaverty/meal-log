package studio.alans.meallog

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The popup for an urgent reminder (bolus not seen, a glucose check that came true), drawn over
 * whatever app is open so it's obvious what is ringing and it can be answered in one tap.
 * Only urgent reminders get it; everything else is a quiet notification. Needs "Display over other
 * apps"; on the lock screen the full-screen notification does the same job.
 */
object AlertOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null
    private var shownId = -1

    fun show(c: Context, id: Int, title: String, text: String) {
        if (!Settings.canDrawOverlays(c)) return
        val app = c.applicationContext
        main.post { runCatching { card(app, id, title, text) } }
    }

    /** Takes the popup away if it belongs to [id] (or any, with -1). */
    fun hide(id: Int = -1) {
        main.post {
            if (id != -1 && id != shownId) return@post
            val v = shown ?: return@post
            shown = null; shownId = -1
            runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
        }
    }

    private fun card(c: Context, id: Int, title: String, text: String) {
        shown?.let { old -> runCatching { c.getSystemService(WindowManager::class.java).removeView(old) } }
        shown = null
        val wm = c.getSystemService(WindowManager::class.java)
        val dp = c.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun tv(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
            this.text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val alert = Color.parseColor("#FF6B5B")

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(10))
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp; setColor(Color.parseColor("#F5182235")); setStroke(px(2), alert)
            }
            elevation = 14 * dp
        }
        val openApp = View.OnClickListener {
            hide(id)
            runCatching {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("meallog://reminders"), c, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        card.addView(tv("MEAL LOG · URGENT", 11f, alert, bold = true).apply { setOnClickListener(openApp) })
        card.addView(tv(title, 19f, Color.WHITE, bold = true).apply { setPadding(0, px(2), 0, 0); setOnClickListener(openApp) })
        if (text.isNotBlank()) card.addView(tv(text, 13f, Color.parseColor("#B8C4D6")).apply {
            setPadding(0, px(2), 0, px(6)); setOnClickListener(openApp)
        })
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun button(label: String, primary: Boolean, act: () -> Unit) = tv(label, 15f, if (primary) Color.BLACK else Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(px(14), px(10), px(14), px(10))
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                if (primary) setColor(Color.parseColor("#8FD3FF")) else setStroke(px(1), Color.parseColor("#55B8C4D6"))
            }
            setOnClickListener { hide(id); act() }
        }
        val gap = LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) }
        row.addView(button("Skip", false) { Reminders.answer(c, id, Reminders.Answer.SKIP) }, gap)
        row.addView(button("Quiet 5 min", false) { Reminders.answer(c, id, Reminders.Answer.SNOOZE) }, gap)
        row.addView(button("Done", true) { Reminders.answer(c, id, Reminders.Answer.DONE) }, gap)
        card.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (c.resources.displayMetrics.heightPixels * 0.06f).toInt()
            horizontalMargin = 0.03f
        }
        wm.addView(card, lp)
        shown = card; shownId = id
    }
}
