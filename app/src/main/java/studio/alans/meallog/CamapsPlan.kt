package studio.alans.meallog

import android.annotation.SuppressLint
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

/**
 * The plan as the steps Alan does in CamAPS FX, in CamAPS's own words and order:
 * Bolus (Carbs), Add meal > Slowly absorbed meal, Boost / Ease-off, then the glucose check with
 * the hypo action. One list feeds both the Plan page and the overlay over CamAPS.
 */
data class CamapsStep(
    val kind: Kind,
    val label: String,        // CamAPS menu words: "Bolus", "Add meal › Slowly absorbed meal", "Boost"
    val value: String,        // the thing to type: "15 g", "20:30 – 22:30", "11:45"
    val time: String?,        // when to do it, HH:MM
    val sub: String?,         // a few words: "at the first bite", "14 g carbs + 12 g fat/protein"
    val ifText: String? = null,   // check: "Under 5 and falling"
    val thenText: String? = null, // check: "Add meal › Hypoglycaemia treatment 10 g"
) {
    enum class Kind { Bolus, AddMeal, Mode, Check, Other }
}

data class CamapsView(val glucose: String?, val mealType: String?, val steps: List<CamapsStep>, val question: String?)

object Camaps {
    /** CamAPS FX builds (mmol/L and mg/dL, original and HX/Ypso). */
    val PACKAGES = listOf("com.camdiab.fx_alert.mmoll", "com.camdiab.fx_alert.hx.mmoll",
        "com.camdiab.fx_alert.mgdl", "com.camdiab.fx_alert.hx.mgdl")

    private fun g(n: Double) = "${if (n % 1.0 == 0.0) n.toInt().toString() else "%.1f".format(n)} g"
    private fun JSONObject.s(k: String) = if (has(k) && !isNull(k)) optString(k).trim().ifBlank { null } else null
    private fun JSONObject.d(k: String) = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
    private fun menu(s: String) = s.replace(" > ", " › ").replace(Regex("(\\d)g\\b"), "$1 g")

    fun view(pl: Plan): CamapsView {
        val j = pl.camaps
        if (j != null) {
            val arr = j.optJSONArray("steps")
            val steps = (0 until (arr?.length() ?: 0)).mapNotNull { i -> arr!!.optJSONObject(i)?.let(::step) }
            if (steps.isNotEmpty()) return CamapsView(j.s("glucose"), j.s("meal_type"), steps, j.s("question"))
        }
        return fallback(pl)
    }

    private fun step(o: JSONObject): CamapsStep? {
        val what = o.s("do") ?: return null
        val at = o.s("at")
        return when {
            what.equals("Bolus", true) -> CamapsStep(CamapsStep.Kind.Bolus, "Bolus", o.d("carbs")?.let(::g) ?: "–", at, o.s("when"))
            what.startsWith("Add meal", true) -> CamapsStep(CamapsStep.Kind.AddMeal,
                "Add meal › " + (o.s("type") ?: "Meal or snack"), o.d("carbs")?.let(::g) ?: "–", at, o.s("note")?.let(::menu))
            what.equals("Boost", true) || what.equals("Ease-off", true) -> {
                val start = o.s("start"); val end = o.s("end")
                val value = when {
                    start != null && end != null -> "$start – $end"
                    end != null -> "until $end"
                    else -> start ?: "–"
                }
                CamapsStep(CamapsStep.Kind.Mode, if (what.equals("Boost", true)) "Boost" else "Ease-off", value, start, o.s("note"))
            }
            what.startsWith("Check", true) -> CamapsStep(CamapsStep.Kind.Check, "Check glucose", at ?: "–", at, null,
                o.s("if"), o.s("then")?.let(::menu))
            else -> CamapsStep(CamapsStep.Kind.Other, what, at ?: "", at, o.s("note"))
        }
    }

    /** Plans from before the planner sent steps: rebuilt from the numbers and reminders. */
    private fun fallback(pl: Plan): CamapsView {
        val steps = mutableListOf<CamapsStep>()
        val bolusAt = pl.bolusInMin?.let { Api.hhmm(pl.madeAt + it * 60_000L) }
        pl.carbs?.let { steps += CamapsStep(CamapsStep.Kind.Bolus, "Bolus", g(it), bolusAt, pl.bolusWhen.ifBlank { null }) }
        pl.slowCarbs?.takeIf { it > 0 }?.let {
            steps += CamapsStep(CamapsStep.Kind.AddMeal, "Add meal › Slowly absorbed meal", g(it), bolusAt, null)
        }
        pl.steps.forEach { st ->
            steps += CamapsStep(CamapsStep.Kind.Other, st.text, Api.hhmm(pl.madeAt + st.inMin * 60_000L), null, null)
        }
        return CamapsView(null, null, steps, null)
    }

    fun installed(c: Context): String? = PACKAGES.firstOrNull { c.packageManager.getLaunchIntentForPackage(it) != null }

    /**
     * Opens CamAPS with the plan floating on top. Returns a reason when it can't: no permission to
     * draw over other apps (Settings opens on the right page) or CamAPS isn't installed.
     */
    fun openWithOverlay(c: Context, pl: Plan): String? {
        val pkg = installed(c) ?: return "CamAPS FX isn't installed"
        if (!Settings.canDrawOverlays(c)) {
            c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Allow Meal Log to display over other apps, then tap again"
        }
        PlanOverlay.show(c.applicationContext, pl.asked.lines().first(), view(pl))
        c.startActivity(c.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }
}

/**
 * A card over CamAPS with the steps to type in. Only the card takes touches, so CamAPS works
 * normally around it. Drag to move it out of the way, tap to dismiss. Goes by itself after 20 min.
 * Plain Views: Compose in an overlay window needs its own lifecycle plumbing for no gain here.
 */
object PlanOverlay {
    private var shown: View? = null
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { hide() }

    fun hide() {
        main.removeCallbacks(timeout)
        val v = shown ?: return
        shown = null
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(c: Context, title: String, cv: CamapsView) {
        hide()
        val wm = c.getSystemService(WindowManager::class.java)
        val dp = c.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val white = Color.WHITE
        val dim = Color.parseColor("#B8C4D6")
        val accent = Color.parseColor("#8FD3FF")

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
            background = GradientDrawable().apply { cornerRadius = 20 * dp; setColor(Color.parseColor("#F2182235")) }
            elevation = 12 * dp
        }
        card.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(title, 13f, dim).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("Tap to close", 12f, dim))
        })
        cv.steps.forEachIndexed { i, st ->
            val row = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(if (i == 0) 6 else 10), 0, 0) }
            row.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(text("${i + 1}  ${st.label}", 15f, accent, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                if (st.kind != CamapsStep.Kind.Check && st.time != null) addView(text(st.time, 14f, dim))
            })
            val big = when (st.kind) {
                CamapsStep.Kind.Bolus, CamapsStep.Kind.AddMeal -> "Carbs  ${st.value}"
                else -> st.value
            }
            if (big.isNotBlank()) row.addView(text(big, 26f, white, bold = true))
            st.sub?.let { row.addView(text(it, 13f, dim)) }
            if (st.ifText != null || st.thenText != null) {
                row.addView(text(listOfNotNull(st.ifText, st.thenText).joinToString(" → "), 14f, Color.parseColor("#FFB4A8")))
            }
            card.addView(row)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = Prefs(c).overlayY.takeIf { it >= 0 } ?: px(56)
            horizontalMargin = 0.03f
        }

        // Drag moves it (and it stays there next time); a tap closes it.
        val slop = ViewConfiguration.get(c).scaledTouchSlop
        var downY = 0f; var startY = 0; var dragging = false
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (dragging || abs(dy) > slop) {
                        dragging = true
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(card, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) Prefs(c).overlayY = lp.y else hide()
            }
            true
        }
        wm.addView(card, lp)
        shown = card
        main.postDelayed(timeout, 20 * 60_000L)
    }
}
