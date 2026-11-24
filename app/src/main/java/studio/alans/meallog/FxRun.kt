package studio.alans.meallog

import android.content.Context
import android.content.Intent
import java.security.MessageDigest

/**
 * The plan as the steps FX Watcher can run in CamAPS, in the order it runs them: the slowly absorbed meal,
 * then Boost or Ease-off (starting now), then the bolus meal, ending on CamAPS's bolus screen where Alan taps
 * Next and Deliver himself. Anything FX Watcher can't do safely (a Boost that starts later, a glucose check, other
 * Add meal types) is left out and listed, never guessed.
 *
 * The key is made from the steps and the plan, so tapping the same plan twice is the same key and FX Watcher
 * says "already run" instead of starting Boost or a slow meal a second time.
 */
data class FxRun(val steps: String, val key: String, val notIncluded: List<String>) {
    val hasSteps: Boolean get() = steps.isNotBlank()
    val hasMeal: Boolean get() = steps.split(";").any { it.startsWith("MEAL:") }
}

object FxRunPlan {
    private val CLOCK = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)""")
    private fun mins(h: String, m: String) = h.toInt() * 60 + m.toInt()

    /** "15 g" or "7.5 g" to whole grams, or null. */
    private fun grams(v: String): Int? = Regex("""^(\d+(?:\.\d+)?)\s*g""").find(v.trim())?.groupValues?.get(1)?.toDouble()?.let { Math.round(it).toInt() }

    /** Both clocks are given: from start to end. Only an end ("until 22:30"): from now. Otherwise unknown. */
    private fun modeMinutes(value: String, nowMin: Int): Pair<Int, Boolean>? {
        val clocks = CLOCK.findAll(value).map { mins(it.groupValues[1], it.groupValues[2]) }.toList()
        val until = value.trim().startsWith("until", true)
        val start: Int; val end: Int
        when {
            clocks.size >= 2 -> { start = clocks[0]; end = clocks[1] }
            clocks.size == 1 && until -> { start = nowMin; end = clocks[0] }
            else -> return null
        }
        // it must start about now: from half an hour ago to a quarter of an hour ahead
        val off = ((start - nowMin + 1440 + 720) % 1440) - 720
        if (off < -30 || off > 15) return null
        val d = ((end - start) % 1440 + 1440) % 1440
        val rounded = (d + 2) / 5 * 5
        return if (rounded in 15..360) rounded to true else null
    }

    fun build(cv: CamapsView, nowMin: Int, planKey: String): FxRun {
        val parts = mutableListOf<Pair<Int, String>>()      // rank, "KIND:n"
        val skipped = mutableListOf<String>()
        var slow = false; var mode = false; var meal = false
        for (st in cv.steps) {
            when (st.kind) {
                CamapsStep.Kind.Bolus -> {
                    val g = grams(st.value)
                    if (g != null && g in 1..150 && !meal) { parts += 3 to "MEAL:$g"; meal = true }
                    else skipped += "Bolus ${st.value}"
                }
                CamapsStep.Kind.AddMeal -> {
                    val g = grams(st.value)
                    if (st.label.endsWith("Slowly absorbed meal") && g != null && g in 5..120 && !slow) { parts += 1 to "SLOW:$g"; slow = true }
                    else skipped += "${st.label} ${st.value}"
                }
                CamapsStep.Kind.Mode -> {
                    val name = if (st.label.equals("Boost", true)) "BOOST" else "EASE"
                    val m = modeMinutes(st.value, nowMin)
                    if (m != null && !mode) { parts += 2 to "$name:${m.first}"; mode = true }
                    else skipped += "${st.label} ${st.value}"
                }
                CamapsStep.Kind.Check -> skipped += "Glucose check ${st.value}"
                CamapsStep.Kind.Other -> skipped += st.label
            }
        }
        val steps = parts.sortedBy { it.first }.joinToString(";") { it.second }
        val digest = MessageDigest.getInstance("SHA-256").digest("$steps|$planKey".toByteArray())
        val key = "p" + digest.joinToString("") { "%02x".format(it) }.take(20)
        return FxRun(steps, key, skipped)
    }

    /** Opens FX Watcher's "Run in CamAPS?" screen. Nothing runs until Alan taps Run there. Returns a reason it couldn't. */
    fun open(c: Context, run: FxRun): String? {
        if (!run.hasSteps) return "Nothing in this plan can be run for you"
        return runCatching {
            c.startActivity(Intent("studio.alans.fxwatcher.action.RUN_PLAN").setPackage("studio.alans.fxwatcher")
                .putExtra("steps", run.steps).putExtra("key", run.key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }.getOrElse { "FX Watcher (the latest build) isn't there to run it" }
    }
}
