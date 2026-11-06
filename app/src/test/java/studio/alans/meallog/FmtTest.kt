package studio.alans.meallog

import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    @Test
    fun quantities() {
        assertEquals("1", fmt(1.0))
        assertEquals("0.75", fmt(0.75))
        assertEquals("1.5", fmt(1.50))
    }
}
