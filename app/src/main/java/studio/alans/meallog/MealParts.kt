package studio.alans.meallog

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * A meal in CamAPS is often two actions: the bolus (calculator carbs) and Add meal > Slowly
 * absorbed meal for the slow part, and the slow part sometimes goes in first while the bolus is
 * still delivering. Both used to open the meal sheet (Alan, 17 Nov 2026: "it should be aware that
 * slow carb is basically always combined with a bolus"). Now an Add meal part never asks on its own:
 *  - a slowly absorbed meal is always part of a meal bolus (one in the last 90 min, or the next one);
 *  - a Meal or snack entry is part of the meal when the plan has an Add meal step of about that size.
 * When the bolus comes after, its sheet shows the whole meal's carbs. If no bolus follows, a quiet
 * "log what you ate" reminder 45 min later still catches it (closed when the bolus arrives).
 */
object MealParts {
    private const val BEFORE_MS = 90 * 60_000L   // part entered after a bolus up to this long
    private const val AHEAD_MS = 60 * 60_000L    // part entered before the bolus up to this long
    private const val FALLBACK_MIN = 45

    /** True when these Add meal carbs belong to a meal bolus (so no sheet). */
    fun partOfMeal(c: android.content.Context, type: String, at: Long, g: Double): Boolean {
        val p = Prefs(c)
        if (type != "slow" && !planned(p, at, g)) return false
        if (p.lastBolusAt in (at - BEFORE_MS)..at && p.lastBolusCarbs >= 0) return true   // bolus already asked
        // Bolus still to come: remember the part, and keep a fallback in case it never does.
        val sameMeal = p.addMealAt != 0L && at - p.addMealAt < AHEAD_MS
        p.addMealCarbs = (if (sameMeal) p.addMealCarbs else 0f) + g.toFloat()
        if (!sameMeal) p.addMealAt = at
        Reminders.logLater(c, p.addMealAt, p.addMealCarbs.toDouble(), inMin = FALLBACK_MIN)
        return true
    }

    /** Add meal carbs waiting for this bolus (entered up to an hour before it), then forgotten. */
    fun takeBefore(c: android.content.Context, bolusAt: Long): Double {
        val p = Prefs(c)
        val at = p.addMealAt
        if (at == 0L) return 0.0
        p.addMealAt = 0L
        val g = p.addMealCarbs.toDouble()
        p.addMealCarbs = 0f
        if (bolusAt - at !in 0..AHEAD_MS) return 0.0
        Reminders.mealLogged(c, at)   // the fallback reminder: the bolus asks for the meal now
        return g
    }

    /** The current plan has an Add meal step (or slow carbs) of about g grams. */
    private fun planned(p: Prefs, at: Long, g: Double): Boolean {
        if (p.planJson.isBlank() || at < p.planAt - 5 * 60_000L || at > p.planAt + 4 * 3600_000L) return false
        val plan = runCatching { Plan.of(JSONObject(p.planJson)) }.getOrNull() ?: return false
        val grams = Camaps.view(plan).steps.filter { it.kind == CamapsStep.Kind.AddMeal }
            .mapNotNull { Regex("""(\d+(?:\.\d+)?)""").find(it.value)?.value?.toDoubleOrNull() } +
            listOfNotNull(plan.slowCarbs)
        return grams.any { it > 0 && abs(it - g) <= max(5.0, it * 0.2) }
    }
}
