package app.weathertrack.core

import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.accuracy.Relations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RelationsTest {
    @Test
    fun cloudierDaysAreCoolerOnceSeasonIsOut() {
        val zone = ZoneId.of("UTC")
        val start = LocalDate.of(2026, 1, 1)
        // A warming season, plus cloud that knocks 1° off the high per 10% (cloud varies day to day).
        val obs = (0 until 90).flatMap { d ->
            val cloud = ((d * 37) % 100).toDouble()
            val nightCloud = ((d * 53) % 100).toDouble()
            (0 until 24).map { h ->
                val t = start.plusDays(d.toLong()).atStartOfDay(zone).toEpochSecond() + h * 3600L
                val night = h >= 21 || h <= 6
                val temp = 15 + d * 0.05 + (if (h in 10..16) 12 - cloud / 10 else 0.0) + (if (night) -3 + nightCloud / 25 else 0.0)
                ObsHour(t, temp, temp, 0.0, 10.0, windSpeed = 10.0, cloudCover = if (night) nightCloud else cloud, cloudLow = cloud / 2)
            }
        }
        val r = Relations.all(obs, zone).associateBy { it.key }
        val ch = r.getValue("cloud_high")
        assertEquals(-1.0, ch.slope * ch.per, 0.15)
        assertTrue(ch.r < -0.8)
        assertTrue(ch.sentence().startsWith("Each 10% more cloud by day: the high about 1° lower"))
        assertTrue(r.getValue("cloud_low").slope > 0)
    }
}
