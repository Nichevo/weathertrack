package app.weathertrack.core

import app.weathertrack.core.comfort.Comfort
import app.weathertrack.core.comfort.Feeling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfortTest {
    private val zone = java.time.ZoneOffset.UTC
    private fun f(kind: Feeling.Kind, feels: Double, dir: Double = 315.0, month: Int = 10) = Feeling(
        java.time.LocalDate.of(2026, month, 10).atStartOfDay(zone).toEpochSecond(), kind, feels + 1, feels, 15.0, dir, 60.0)

    @Test fun chillyPointNeedsAFewTaps() {
        assertNull(Comfort.chillyPoint(listOf(f(Feeling.Kind.CHILLY, 12.0), f(Feeling.Kind.CHILLY, 14.0))))
        assertEquals(14.0, Comfort.chillyPoint(listOf(12.0, 14.0, 15.0).map { f(Feeling.Kind.CHILLY, it) })!!, 0.01)
    }

    @Test fun pollenPattern() {
        val taps = listOf(f(Feeling.Kind.POLLEN, 20.0), f(Feeling.Kind.POLLEN, 21.0), f(Feeling.Kind.POLLEN, 22.0, month = 11),
            f(Feeling.Kind.POLLEN, 22.0, dir = 180.0, month = 11))
        val p = Comfort.pollen(taps, zone)!!
        assertEquals(4, p.taps)
        assertEquals("NW", p.windFrom)
        assertTrue(Comfort.summary(taps, zone).single().contains("NW winds"))
    }
}
