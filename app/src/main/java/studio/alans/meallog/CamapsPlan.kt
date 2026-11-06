package studio.alans.meallog

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        // FX Watcher (3.12+, if its "Open the Bolus tab when an app asks" is on) taps CamAPS's
        // Bolus tab once, so the calculator is open. Ignored otherwise.
        c.sendBroadcast(Intent("studio.alans.fxwatcher.action.OPEN_BOLUS_TAB").setPackage("studio.alans.fxwatcher"))
        c.startActivity(c.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }
}

/**
 * The plan over CamAPS. Two sizes: the card (every step) and a pill (just the bolus grams) for
 * when the card is in the way. Only the card or pill takes touches, so CamAPS works around it.
 * Card: drag to move, tap to close, "–" shrinks it to the pill. Pill: drag to move, tap to open
 * the card again. Positions are remembered. Goes by itself after 20 min.
 * The card starts in the empty space under the calculator's Total Units (clear of the tabs, the
 * Meal size slider, Glucose, Carbs, Total Units and Cancel/Next). When a CamAPS entry box opens
 * (Carbohydrates with the keyboard, which Android draws over overlays), FX Watcher 3.12.1+ says
 * so and the card turns into the pill in the gap between the box and the keyboard; it comes back
 * when the box closes. Plain Views: Compose in an overlay window needs its own
 * lifecycle plumbing for no gain here.
 */
object PlanOverlay {
    private var shown: View? = null
    private var title = ""
    private var steps: CamapsView? = null
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { hide() }
    private var isPill = false
    private var autoPill = false   // pill because an entry box is open, not because it was asked for
    private var receiver: BroadcastReceiver? = null

    fun hide() {
        main.removeCallbacks(timeout)
        remove()
        steps = null
        receiver?.let { r -> runCatching { shownCtx?.unregisterReceiver(r) } }
        receiver = null
    }

    private var shownCtx: Context? = null

    /** FX Watcher (read-only) says whether a CamAPS entry dialog is open. */
    private fun listen(c: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (steps == null) return
                val dialog = i.getBooleanExtra("dialog", false)
                if (dialog && !isPill) { pill(c); autoPill = true }
                else if (!dialog && isPill && autoPill) card(c)
            }
        }
        c.registerReceiver(r, IntentFilter("studio.alans.fxwatcher.action.CAMAPS_SCREEN"), Context.RECEIVER_EXPORTED)
        receiver = r
        shownCtx = c
    }

    private fun remove() {
        val v = shown ?: return
        shown = null
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
    }

    fun show(c: Context, title: String, cv: CamapsView) {
        main.removeCallbacks(timeout)
        this.title = title
        steps = cv
        card(c)
        runCatching { listen(c) }
        main.postDelayed(timeout, 20 * 60_000L)
    }

    private fun params(c: Context, pill: Boolean): WindowManager.LayoutParams {
        val p = Prefs(c)
        val h = c.resources.displayMetrics.heightPixels
        val saved = if (pill) p.overlayPillY else p.overlayY
        return WindowManager.LayoutParams(
            if (pill) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or (if (pill) Gravity.END else Gravity.CENTER_HORIZONTAL)
            y = saved.takeIf { it >= 0 } ?: (h * if (pill) 0.49f else 0.585f).toInt()
            horizontalMargin = 0.03f
        }
    }

    /** Drag moves the window (and remembers where); a tap without a drag calls onTap. */
    @SuppressLint("ClickableViewAccessibility")
    private fun movable(c: Context, v: View, lp: WindowManager.LayoutParams, pill: Boolean, onTap: () -> Unit) {
        val wm = c.getSystemService(WindowManager::class.java)
        val slop = ViewConfiguration.get(c).scaledTouchSlop
        var downY = 0f; var startY = 0; var dragging = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (dragging || abs(dy) > slop) {
                        dragging = true
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    if (pill) Prefs(c).overlayPillY = lp.y else Prefs(c).overlayY = lp.y
                } else onTap()
            }
            true
        }
    }

    private fun text(c: Context, s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(c: Context) {
        remove()
        val cv = steps ?: return
        isPill = false; autoPill = false
        val wm = c.getSystemService(WindowManager::class.java)
        val dp = c.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val white = Color.WHITE
        val dim = Color.parseColor("#B8C4D6")
        val accent = Color.parseColor("#8FD3FF")

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(8), px(6), px(10))
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(Color.parseColor("#F2182235")) }
            elevation = 12 * dp
        }
        val lp = params(c, pill = false)
        card.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(c, "$title · tap to close", 12f, dim).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // Shrink to the pill. Its own touch handling, so it isn't a "tap to close".
            addView(text(c, "–", 22f, white, bold = true).apply {
                setPadding(px(16), 0, px(10), 0)
                contentDescription = "Make smaller"
                setOnClickListener { pill(c) }
            })
        })
        cv.steps.forEachIndexed { i, st ->
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(if (i == 0) 2 else 6), px(8), 0)
            }
            val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            left.addView(text(c, "${i + 1}  ${st.label}", 14f, accent, bold = true))
            val sub = listOfNotNull(
                st.sub,
                if (st.ifText != null || st.thenText != null) listOfNotNull(st.ifText, st.thenText).joinToString(" → ") else null,
            ).joinToString(" · ")
            if (sub.isNotBlank()) left.addView(text(c, sub, 12f,
                if (st.thenText != null) Color.parseColor("#FFB4A8") else dim).apply { maxLines = 2 })
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            val big = when (st.kind) {
                CamapsStep.Kind.Bolus, CamapsStep.Kind.AddMeal -> st.value
                CamapsStep.Kind.Other -> ""
                else -> st.value
            }
            if (big.isNotBlank()) row.addView(text(c, big, 24f, white, bold = true).apply { setPadding(px(10), 0, 0, 0) })
            card.addView(row)
        }
        movable(c, card, lp, pill = false) { hide() }
        wm.addView(card, lp)
        shown = card
    }

    private fun pill(c: Context) {
        remove()
        val cv = steps ?: return
        isPill = true; autoPill = false
        val wm = c.getSystemService(WindowManager::class.java)
        val dp = c.resources.displayMetrics.density
        val first = cv.steps.firstOrNull { it.kind == CamapsStep.Kind.Bolus } ?: cv.steps.firstOrNull()
        val label = first?.let { if (it.kind == CamapsStep.Kind.Bolus) "Bolus ${it.value}" else it.label } ?: "Plan"
        val pill = text(c, "$label  ▾", 16f, Color.WHITE, bold = true).apply {
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 40 * dp; setColor(Color.parseColor("#F2182235")) }
            elevation = 10 * dp
            contentDescription = "Meal plan, tap to open"
        }
        val lp = params(c, pill = true)
        movable(c, pill, lp, pill = true) { card(c) }
        wm.addView(pill, lp)
        shown = pill
    }
}
