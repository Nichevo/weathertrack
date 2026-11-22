package app.weathertrack.core

import app.weathertrack.core.accuracy.Band
import app.weathertrack.core.accuracy.CloudRegime
import app.weathertrack.core.accuracy.Measure
import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.accuracy.RunHour
import app.weathertrack.core.accuracy.WindCloud
import app.weathertrack.core.cloud.CloudStory
import app.weathertrack.core.cloud.Sky
import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.sources.IssuedSourceHour
import app.weathertrack.core.sources.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class WindCloudTest {
    private val zone: ZoneId = ZoneOffset.UTC

    @Test
    fun angleFoldsAcrossNorth() {
        assertEquals(20.0, WindCloud.angle(10.0, 350.0), 1e-9)
        assertEquals(-20.0, WindCloud.angle(350.0, 10.0), 1e-9)
        assertEquals(180.0, WindCloud.angle(180.0, 0.0), 1e-9)
    }

    @Test
    fun windErrorsByLeadAndDirectionOnlyWhenWindy() {
        val start = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toEpochSecond()
        val hours = (0 until 48).map { start + it * 3600L }
        val obs = hours.map { ObsHour(it, 15.0, 15.0, 0.0, 30.0, windSpeed = 20.0, windDirection = 10.0, cloudCover = 50.0) }
        val runs = hours.map { RunHour(it, 1, 15.0, 0.0, windSpeed = 15.0, gusts = 25.0, windDirection = 350.0, cloudCover = 70.0) } +
            hours.map { RunHour(it, 3, 15.0, 0.0, windSpeed = 2.0, windDirection = 180.0) }
        val e = WindCloud.errors(runs, obs, start)
        val wind1 = e.getValue(Measure.WIND).first { it.lead == 1 }
        assertEquals(5.0, wind1.bias, 1e-9)
        assertEquals(48, wind1.days)
        assertEquals(20.0, e.getValue(Measure.DIRECTION).first { it.lead == 1 }.meanAbs, 1e-9)
        // Lead 3 forecast a near calm, so its direction isn't judged.
        assertTrue(e.getValue(Measure.DIRECTION).none { it.lead == 3 })
        assertEquals(-20.0, e.getValue(Measure.CLOUD).first { it.lead == 1 }.bias, 1e-9)
    }

    @Test
    fun sunnyUsesLowAndMidCloudOnly() {
        assertEquals(true, Sky.sunny(10.0, 10.0))
        assertEquals(false, Sky.sunny(80.0, 0.0))
        // Thick high cloud alone: Open-Meteo says overcast (3), we say clear.
        assertEquals(0, Sky.skyCode(3, 0.0, 5.0))
        assertEquals(61, Sky.skyCode(61, 0.0, 0.0))
        val t = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toEpochSecond() + 12 * 3600
        val obs = listOf(ObsHour(t, 20.0, 20.0, 0.0, 10.0, cloudLow = 90.0, cloudMid = 0.0, radiation = 100.0))
        val runs = listOf(RunHour(t, 1, 20.0, 0.0, cloudLow = 0.0, cloudMid = 0.0), RunHour(t, 2, 20.0, 0.0, cloudLow = 95.0, cloudMid = 0.0))
        val s = WindCloud.sunny(runs, obs, 0)
        assertEquals(0, s.first { it.lead == 1 }.right)
        assertEquals(1, s.first { it.lead == 2 }.right)
    }

    @Test
    fun regimeBandsWidenOnLowCloudDays() {
        val first = LocalDate.of(2026, 1, 1)
        val runs = mutableListOf<RunHour>()
        val obs = mutableListOf<ObsHour>()
        for (d in 0 until 30) {
            val stratus = d % 2 == 0
            for (h in 0 until 24) {
                val t = first.plusDays(d.toLong()).atStartOfDay(zone).toEpochSecond() + h * 3600L + 1800
                runs += RunHour(t, 1, 20.0, 0.0, cloudCover = if (stratus) 90.0 else 10.0, cloudLow = if (stratus) 85.0 else 5.0)
                // Stratus days end up 3 to 5 degrees cooler than forecast; clear days are spot on.
                obs += ObsHour(t, if (stratus) 20.0 - 3 - (d % 4) else 20.0, null, 0.0, null)
            }
        }
        val by = WindCloud.highErrorsByRegime(runs, obs, zone, first)
        val low = by.getValue(CloudRegime.LOW_CLOUD).first { it.lead == 1 }
        val clear = by.getValue(CloudRegime.CLEAR).first { it.lead == 1 }
        assertTrue(low.bias < -2.5)
        assertEquals(0.0, clear.bias, 1e-9)
        val base = mapOf(1 to Band(1, -1.0, -3.0, 0.5, 30))
        val swapped = WindCloud.regimeBands(base, by) { CloudRegime.LOW_CLOUD }
        assertEquals(CloudRegime.LOW_CLOUD, swapped.getValue(1).regime)
        assertTrue(swapped.getValue(1).bias < -2.5)
    }

    private fun hour(t: Long, temp: Double, low: Double, total: Double = low, high: Double = 0.0, day: Boolean = true) =
        HourPoint(t, temp, temp, 0.0, 0.0, 1, 5.0, 10.0, 200.0, 0.0, total, 70.0, day, low, 0.0, high)

    @Test
    fun cloudStoryLowCloudClearsThenClearNight() {
        val base = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toEpochSecond()
        val now = base + 7 * 3600
        val hours = (8..30).map { h ->
            val t = base + h * 3600L
            when {
                h <= 11 -> hour(t, 16.0, 90.0)
                h <= 19 -> hour(t, 22.0, 5.0, 10.0)
                else -> hour(t, 2.0, 0.0, 5.0, day = false)
            }
        }
        val lines = CloudStory.lines(hours, now, zone)
        assertEquals("Low cloud until 11 am keeps it near 16°, then sun lifts it to 22°", lines[0])
        assertEquals("Clear tonight: cold, frost possible (low 2°)", lines[1])
        assertNotNull(CloudStory.layers(hours.first()))
    }

    @Test
    fun sourcesScoreAndWeighWindAndCloudSeparately() {
        val t0 = 1_700_000_000L / 3600 * 3600
        val hours = (0 until 100).map { t0 + it * 3600L }
        val obs = hours.map { ObsHour(it, 15.0, 15.0, 0.0, null, windSpeed = 20.0, cloudCover = 50.0) }
        val good = hours.map { IssuedSourceHour("good", it - 6 * 3600, it, 18.0, 0.0, 5.0, windSpeed = 21.0, cloudCover = 55.0) }
        val bad = hours.map { IssuedSourceHour("bad", it - 6 * 3600, it, 15.0, 0.0, 5.0, windSpeed = 35.0, cloudCover = 10.0) }
        val scores = Sources.scores(good + bad, obs)
        val w = Sources.weights(listOf("good", "bad"), scores).associateBy { it.source }
        assertEquals(100, scores.first { it.source == "good" }.windHours)
        assertTrue(w.getValue("good").wind > 0.6)
        assertTrue(w.getValue("good").cloud > 0.6)
        // Temperature has its own record: "bad" is better there.
        assertTrue(w.getValue("bad").temp > 0.5)
    }
}
