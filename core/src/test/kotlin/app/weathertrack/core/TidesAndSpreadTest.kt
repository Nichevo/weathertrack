package app.weathertrack.core

import app.weathertrack.core.accuracy.RainAdjust
import app.weathertrack.core.accuracy.Reliability
import app.weathertrack.core.accuracy.Spread
import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.tides.TidePoint
import app.weathertrack.core.tides.TideSeries
import app.weathertrack.core.tides.Tides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

class TidesTest {
    // Semi-diurnal tide, period 12.42 h, high at t = 0.
    private val period = 12.42 * 3600
    private fun series(hours: Int) = TideSeries((0..hours).map { h -> TidePoint(h * 3600L, 1.2 * cos(2 * PI * h * 3600 / period)) }, -36.8, 174.8)

    @Test fun findsHighsAndLowsAtTheRightTimes() {
        val ev = series(30).events
        val firstLow = ev.first { !it.high }
        assertTrue(abs(firstLow.time - period / 2) < 15 * 60) // within 15 minutes
        assertEquals(-1.2, firstLow.height, 0.05)
        val nextHigh = ev.first { it.high }
        assertTrue(abs(nextHigh.time - period) < 15 * 60)
    }

    @Test fun nowKnowsDirection() {
        val n = Tides.now(series(30), 2 * 3600L)!!
        assertFalse(n.rising) // falling towards the first low
        assertFalse(n.next!!.high)
        assertTrue(n.after!!.high)
    }

    @Test fun parsesMarineResponse() {
        val body = """{"latitude":-36.84,"longitude":174.88,"hourly":{"time":[0,3600,7200,10800,14400,18000,21600],
            "sea_level_height_msl":[1.0,0.6,0.0,-0.6,-1.0,-0.6,null]}}"""
        val s = OpenMeteoParser.parseTides(body)!!
        assertEquals(6, s.points.size)
        assertEquals(174.88, s.longitude, 1e-9)
        assertNull(OpenMeteoParser.parseTides("""{"hourly":{"time":[0,3600],"sea_level_height_msl":[null,null]}}"""))
    }
}

class SpreadTest {
    @Test fun parsesAndFlagsDisagreement() {
        val body = """{"daily":{"time":["2026-11-23","2026-11-24"],
            "temperature_2m_max_ecmwf_ifs025":[19.0,18.0],"temperature_2m_max_gfs_seamless":[20.0,23.0],
            "precipitation_sum_ecmwf_ifs025":[0.0,8.0],"precipitation_sum_gfs_seamless":[0.2,0.0]}}"""
        val days = Spread.of(OpenMeteoParser.parseModelSpread(body, listOf("ecmwf_ifs025", "gfs_seamless")))
        assertFalse(days[0].highsDisagree)
        assertFalse(days[0].rainDisagrees)
        assertTrue(days[1].highsDisagree)
        assertTrue(days[1].rainDisagrees)
        assertEquals(2, days[1].models)
    }

    @Test fun adjustedChanceShrinksTowardsRecord() {
        val rel = listOf(Reliability("50–70%", 60.0, 0.3, 50))
        // Half weight at 50 hours: 60% forecast, 30% happened -> 45%.
        assertEquals(45.0, RainAdjust.adjusted(60.0, rel)!!, 1e-9)
        assertEquals(20.0, RainAdjust.adjusted(20.0, rel)!!, 1e-9) // no bucket -> unchanged
        assertNotNull(RainAdjust.adjusted(55.0, rel))
    }
}
