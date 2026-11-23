package app.weathertrack.core

import app.weathertrack.core.pollen.Pollen
import app.weathertrack.core.pollen.PollenHour
import app.weathertrack.core.pollen.PollenLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PollenTest {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val cal = Pollen.parseCalendar(
        "# comment\nmonth,trees,grass,weeds\n" + (1..12).joinToString("\n") { m -> if (m == 11) "11,0.3,1.0,0.2" else "$m,0.05,0.05,0.05" })

    private fun day(date: LocalDate, temp: Double, cloud: Double, hum: Double, rainDay: Double = 0.0, rainBefore: Double = 0.0, wind: Double = 12.0) =
        (-24 until 24).map { h ->
            val t = date.atStartOfDay(zone).toEpochSecond() + h * 3600L
            val inDay = h in 10..17
            PollenHour(t, if (inDay) temp else temp - 6, if (h < 10 && h >= -14) rainBefore / 24 else if (inDay) rainDay / 8 else 0.0, hum, wind, cloud)
        }

    @Test
    fun calendarAndHemisphere() {
        assertEquals(12, cal.size)
        val nov = LocalDate.of(2026, 11, 20)
        assertEquals("Grass", Pollen.season(cal, nov, -36.9)!!.main)
        // May in the north is November here.
        assertEquals(11, Pollen.season(cal, LocalDate.of(2026, 5, 20), 51.0)!!.month)
    }

    @Test
    fun warmSunnyDryPeakIsHighAndRainyIsLow() {
        val d = LocalDate.of(2026, 11, 20)
        val s = Pollen.season(cal, d, -36.9)
        val high = Pollen.day(s, day(d, 24.0, 20.0, 45.0), d, zone)!!
        assertEquals(PollenLevel.HIGH, high.level)
        assertTrue(high.reasons.first().contains("peak"))
        val wet = Pollen.day(s, day(d, 17.0, 95.0, 90.0, rainDay = 8.0), d, zone)!!
        assertEquals(PollenLevel.LOW, wet.level)
        // Sun after rain brings a burst.
        val after = Pollen.day(s, day(d, 22.0, 30.0, 60.0, rainBefore = 6.0), d, zone)!!
        assertTrue(after.factors.rain > 1)
        // Out of season it stays low whatever the weather.
        val may = LocalDate.of(2026, 5, 20)
        assertEquals(PollenLevel.LOW, Pollen.day(Pollen.season(cal, may, -36.9), day(may, 24.0, 20.0, 45.0), may, zone)!!.level)
    }

    @Test
    fun personalModelOnlyWithEnoughOfBoth() {
        val d = LocalDate.of(2026, 11, 20)
        val s = Pollen.season(cal, d, -36.9)
        // This person only reacts on very dry days.
        val ex = (0 until 40).map { i ->
            val hum = 40.0 + i
            Pollen.factors(s, day(d, 22.0, 30.0, hum), d, zone)!! to (hum < 55)
        }
        assertNull(Pollen.fit(ex.take(20)))
        val m = Pollen.fit(ex)
        assertNotNull(m)
        val dry = Pollen.day(s, day(d, 22.0, 30.0, 42.0), d, zone, m)!!
        val humid = Pollen.day(s, day(d, 22.0, 30.0, 78.0), d, zone, m)!!
        assertTrue(dry.personal)
        assertTrue("dry ${dry.index} humid ${humid.index}", dry.index > humid.index)
        val score = Pollen.score(listOf(dry to true, humid to false))
        assertEquals(2, score.total)
    }

    @Test
    fun labelsPerDay() {
        val t = LocalDate.of(2026, 11, 20).atTime(9, 0).atZone(zone).toEpochSecond()
        val l = Pollen.labels(listOf(t to false, t + 3600 to true, t + 86400 to false), zone)
        assertEquals(listOf(true, false), l.map { it.symptoms })
    }
}
