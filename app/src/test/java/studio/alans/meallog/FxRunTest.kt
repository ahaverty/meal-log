package studio.alans.meallog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxRunTest {
    private fun st(kind: CamapsStep.Kind, label: String, value: String) = CamapsStep(kind, label, value, null, null)
    private fun cv(vararg s: CamapsStep) = CamapsView(null, null, s.toList(), null)
    private val now = 11 * 60 + 40

    @Test fun slowThenBoostThenMealInThatOrder() {
        val r = FxRunPlan.build(cv(
            st(CamapsStep.Kind.Bolus, "Bolus", "45 g"),
            st(CamapsStep.Kind.Mode, "Boost", "11:45 to 13:45"),
            st(CamapsStep.Kind.AddMeal, "Add meal › Slowly absorbed meal", "15 g"),
        ), now, "k")
        assertEquals("SLOW:15;BOOST:120;MEAL:45", r.steps)
        assertTrue(r.notIncluded.isEmpty())
        assertTrue(r.hasMeal)
    }

    @Test fun aBoostThatStartsLaterIsLeftOutAndListed() {
        val r = FxRunPlan.build(cv(st(CamapsStep.Kind.Bolus, "Bolus", "45 g"), st(CamapsStep.Kind.Mode, "Boost", "20:30 to 22:30")), now, "k")
        assertEquals("MEAL:45", r.steps)
        assertEquals(listOf("Boost 20:30 to 22:30"), r.notIncluded)
    }

    @Test fun untilEndStartsNow() {
        val r = FxRunPlan.build(cv(st(CamapsStep.Kind.Mode, "Ease-off", "until 13:40")), now, "k")
        assertEquals("EASE:120", r.steps)
    }

    @Test fun checksAndOtherAddMealTypesAreNeverRun() {
        val r = FxRunPlan.build(cv(
            st(CamapsStep.Kind.AddMeal, "Add meal › Meal or snack", "10 g"),
            st(CamapsStep.Kind.Check, "Check glucose", "13:00"),
            st(CamapsStep.Kind.Other, "Walk", ""),
        ), now, "k")
        assertEquals("", r.steps)
        assertEquals(3, r.notIncluded.size)
    }

    @Test fun theKeyIsStableForTheSamePlanAndChangesWithTheSteps() {
        val a = cv(st(CamapsStep.Kind.Bolus, "Bolus", "45 g"))
        val b = cv(st(CamapsStep.Kind.Bolus, "Bolus", "50 g"))
        assertEquals(FxRunPlan.build(a, now, "k").key, FxRunPlan.build(a, now + 3, "k").key)
        assertTrue(FxRunPlan.build(a, now, "k").key != FxRunPlan.build(b, now, "k").key)
        assertTrue(FxRunPlan.build(a, now, "k").key != FxRunPlan.build(a, now, "other plan").key)
        assertTrue(FxRunPlan.build(a, now, "k").key.matches(Regex("[A-Za-z0-9]{21}")))
    }

    @Test fun outOfRangeAmountsAreLeftOut() {
        val r = FxRunPlan.build(cv(st(CamapsStep.Kind.Bolus, "Bolus", "200 g"), st(CamapsStep.Kind.AddMeal, "Add meal › Slowly absorbed meal", "2 g")), now, "k")
        assertEquals("", r.steps)
    }
}
