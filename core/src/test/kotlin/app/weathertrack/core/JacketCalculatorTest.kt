package app.weathertrack.core

import app.weathertrack.core.jacket.JacketCalculator
import app.weathertrack.core.jacket.ReasonKind
import app.weathertrack.core.jacket.Verdict
import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.model.QuarterHourPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JacketCalculatorTest {
    private val start = 1_800_000_000L - (1_800_000_000L % 3600)

    private fun hour(i: Int, prob: Double = 0.0, rain: Double = 0.0, gust: Double = 10.0, feels: Double = 20.0) =
        HourPoint(start + i * 3600L, feels, feels, prob, rain, 1, 5.0, gust, 180.0, 3.0, 20.0, 60.0, true)

    @Test fun clearDayIsNo() {
        val r = JacketCalculator.evaluate((0..10).map { hour(it) }, start + 600, 3)
        assertEquals(Verdict.NO, r.verdict)
        assertTrue(r.reasons.isEmpty())
        assertEquals(4, r.hoursConsidered) // current hour + 3 more (last one partially)
    }

    @Test fun rainInsideWindowIsYes() {
        val hours = (0..10).map { if (it == 2) hour(it, prob = 70.0, rain = 1.2) else hour(it) }
        val r = JacketCalculator.evaluate(hours, start, 3)
        assertEquals(Verdict.YES, r.verdict)
        assertEquals(ReasonKind.RAIN, r.reasons.first().kind)
    }

    @Test fun rainOutsideThreeHoursButInsideSix() {
        val hours = (0..10).map { if (it == 4) hour(it, prob = 80.0, rain = 2.0) else hour(it) }
        assertEquals(Verdict.NO, JacketCalculator.evaluate(hours, start, 3).verdict)
        assertEquals(Verdict.YES, JacketCalculator.evaluate(hours, start, 6).verdict)
    }

    @Test fun moderateWindAndCoolIsMaybe() {
        val hours = (0..10).map { hour(it, gust = 38.0, feels = 13.0) }
        val r = JacketCalculator.evaluate(hours, start, 3)
        assertEquals(Verdict.MAYBE, r.verdict)
        assertEquals(setOf(ReasonKind.WIND, ReasonKind.COLD), r.reasons.map { it.kind }.toSet())
    }

    @Test fun coldIsYes() {
        val r = JacketCalculator.evaluate((0..10).map { hour(it, feels = 8.0) }, start, 3)
        assertEquals(Verdict.YES, r.verdict)
    }

    @Test fun nowcastFindsFirstRainyQuarter() {
        val q = (0 until 16).map { QuarterHourPoint(start + it * 900L, if (it >= 5) 0.4 else 0.0) }
        val r = JacketCalculator.evaluate((0..10).map { hour(it) }, start + 100, 3, q)
        assertEquals(start + 5 * 900L, r.rainStartsAt)
    }

    @Test fun noDataGivesNoVerdictAndNulls() {
        val r = JacketCalculator.evaluate(emptyList(), start, 3)
        assertEquals(Verdict.NO, r.verdict)
        assertNull(r.maxRainProbability)
        assertNull(r.totalRainMm)
    }
}
