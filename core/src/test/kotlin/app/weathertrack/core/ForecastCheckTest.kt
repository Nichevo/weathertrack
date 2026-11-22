package app.weathertrack.core

import app.weathertrack.core.accuracy.Confidence
import app.weathertrack.core.accuracy.ForecastCheck
import app.weathertrack.core.accuracy.IssuedHour
import app.weathertrack.core.accuracy.MissKind
import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.accuracy.RunHour
import app.weathertrack.core.jacket.JacketThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ForecastCheckTest {
    private val zone = ZoneOffset.UTC
    private val d0 = LocalDate.of(2026, 10, 1)
    private fun t(day: Int, hour: Int) = d0.plusDays(day.toLong()).atStartOfDay(zone).toEpochSecond() + hour * 3600L

    /** 20 days; actual high 20°, rain 2 mm on even days. Lead-1 forecast high 19° (1° low), lead-2 high 22°. */
    private val obs = (0 until 20).flatMap { d ->
        (0 until 24).map { h -> ObsHour(t(d, h), if (h == 14) 20.0 else 15.0, 14.0, if (d % 2 == 0 && h == 10) 2.0 else 0.0, 20.0) }
    }
    private val runs = (0 until 20).flatMap { d ->
        (0 until 24).flatMap { h ->
            listOf(
                RunHour(t(d, h), 1, if (h == 14) 19.0 else 15.0, if (h == 10) 2.0 else 0.0), // always says rain
                RunHour(t(d, h), 2, if (h == 14) (if (d == 5) 25.0 else 22.0) else 15.0, 0.0),
            )
        }
    }

    @Test fun highErrorsByLead() {
        val e = ForecastCheck.highErrors(runs, obs, zone, d0, leads = listOf(1, 2))
        assertEquals(1.0, e[0].meanAbs, 1e-9)
        assertEquals(1.0, e[0].bias, 1e-9)
        assertEquals(20, e[0].days)
        assertEquals(-2.0, e[1].p90, 1e-9)
        val bands = ForecastCheck.bands(e)
        assertEquals(Confidence.HIGH, bands.getValue(1).confidence)
    }

    @Test fun rainCallsAndMisses() {
        val s = ForecastCheck.rainCalls(runs, obs, zone, d0, lead = 1)
        assertEquals(20, s.total)
        assertEquals(10, s.right)
        val misses = ForecastCheck.misses(runs, obs, zone, d0)
        assertEquals(MissKind.COLD, misses.first().kind) // day 5: forecast 25°, reached 20°
        assertEquals(d0.plusDays(5), misses.first().date)
    }

    @Test fun reliabilityBuckets() {
        // Issued 2 h ahead: 80% on even-day 10:00 hours (wet), 80% on odd days (dry).
        val issued = (0 until 20).map { d -> IssuedHour(t(d, 8), t(d, 10), 15.0, 14.0, 80.0, 1.0, 20.0) }
        val r = ForecastCheck.reliability(issued, obs, t(0, 0))
        val top = r.last()
        assertEquals(20, top.hours)
        assertEquals(0.5, top.observedShare!!, 1e-9)
        assertNull(r.first().observedShare)
    }

    @Test fun jacketScoredAgainstActuals() {
        // 07:00 pull each day saying 80% rain for 07–10. Actual: 2 mm at 10:00 on even days only, so 07-10 window
        // (07, 08, 09) is dry every day: the YES call is wrong every time.
        val issued = (0 until 5).flatMap { d -> (7..12).map { h -> IssuedHour(t(d, 7), t(d, h), 15.0, 14.0, 80.0, 1.0, 20.0) } }
        val s = ForecastCheck.jacket(issued, obs, zone, d0, JacketThresholds(feelsLikeYes = 5.0, feelsLikeMaybe = 8.0))
        assertEquals(0, s.right)
        assertEquals(5, s.wrong)
    }
}

class PreviousRunsParseTest {
    @Test fun parsesLeads() {
        val body = """{"hourly":{"time":[0,3600],"temperature_2m":[10.0,11.0],"temperature_2m_previous_day1":[9.0,null],
            "precipitation":[0.0,0.2],"precipitation_previous_day1":[0.1,null]}}"""
        val rows = app.weathertrack.core.openmeteo.OpenMeteoParser.parsePreviousRuns(body)
        assertEquals(3, rows.size) // lead 0 twice, lead 1 once (second hour empty)
        assertEquals(9.0, rows.first { it.lead == 1 }.temperature!!, 1e-9)
    }
}

class FanTest {
    private val e = listOf(
        app.weathertrack.core.accuracy.LeadError(1, 30, 1.0, 0.0, -1.0, 1.5),
        app.weathertrack.core.accuracy.LeadError(3, 30, 2.0, 0.0, -2.0, 3.0),
        app.weathertrack.core.accuracy.LeadError(7, 30, 3.0, 0.0, -4.0, 4.0),
    )
    private val today = java.time.LocalDate.of(2026, 11, 22)

    @org.junit.Test fun widensWithLead() {
        val f = app.weathertrack.core.accuracy.ForecastCheck.fan(
            listOf(today.plusDays(1) to 20.0, today.plusDays(2) to 20.0, today.plusDays(9) to 20.0), today, e)
        org.junit.Assert.assertEquals(19.0, f[0].low, 1e-9)
        org.junit.Assert.assertEquals(21.5, f[0].high, 1e-9)
        org.junit.Assert.assertEquals(18.0, f[1].low, 1e-9) // lead 2 uses lead 3
        org.junit.Assert.assertEquals(24.0, f[2].high, 1e-9) // beyond 7 uses 7
    }

    @org.junit.Test fun rainNeverBelowZero() {
        val f = app.weathertrack.core.accuracy.ForecastCheck.fan(listOf(today.plusDays(1) to 0.5), today, e, floorZero = true)
        org.junit.Assert.assertEquals(0.0, f[0].low, 1e-9)
        org.junit.Assert.assertEquals(2.0, f[0].high, 1e-9)
    }
}

class FeedbackTest {
    private val F = app.weathertrack.core.accuracy.Feedback
    private fun r(t: Long, raining: Boolean, mm: Double?, radar: Double?) = app.weathertrack.core.accuracy.RainReport(t, raining, mm, radar)

    @org.junit.Test fun scoresForecastAndRadarSeparately() {
        val s = F.score(listOf(r(1, true, 0.0, 0.8), r(2, false, 0.5, 0.0), r(3, true, 0.4, null)))
        org.junit.Assert.assertEquals(3, s.reports)
        org.junit.Assert.assertEquals(1, s.forecastRight); org.junit.Assert.assertEquals(3, s.forecastJudged)
        org.junit.Assert.assertEquals(2, s.radarRight); org.junit.Assert.assertEquals(2, s.radarJudged)
    }

    @org.junit.Test fun onlyFreshReportCountsAsNow() {
        val list = listOf(r(1000, true, null, null), r(5000, false, null, null))
        org.junit.Assert.assertEquals(false, F.current(list, 5000 + 600)?.raining)
        org.junit.Assert.assertNull(F.current(list, 5000 + 3 * 3600))
    }

    @org.junit.Test fun calibratedUsesBucket() {
        val rel = listOf(
            app.weathertrack.core.accuracy.Reliability("50–70%", 60.0, 0.4, 50),
            app.weathertrack.core.accuracy.Reliability("70%+", 80.0, 0.9, 5),
        )
        org.junit.Assert.assertEquals(0.4, F.calibrated(rel, 55.0)!!.first, 1e-9)
        org.junit.Assert.assertNull(F.calibrated(rel, 75.0)) // too few hours
    }
}
