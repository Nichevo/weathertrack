package app.weathertrack.core

import app.weathertrack.core.accuracy.ForecastCheck
import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.accuracy.RunHour
import app.weathertrack.core.air.Air
import app.weathertrack.core.air.AirHour
import app.weathertrack.core.model.DayPoint
import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.outdoor.DryingDay
import app.weathertrack.core.outdoor.Frost
import app.weathertrack.core.outdoor.Garden
import app.weathertrack.core.outdoor.Pavement
import app.weathertrack.core.outdoor.Sun
import app.weathertrack.core.outdoor.UvBurn
import app.weathertrack.core.warnings.Cap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class OutdoorTest {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val date = LocalDate.of(2026, 12, 21)
    private fun at(h: Int) = date.atTime(h, 0).atZone(zone).toEpochSecond()
    private fun hour(t: Long, temp: Double = 20.0, hum: Double = 50.0, wind: Double = 15.0, sw: Double? = 600.0, prob: Double = 0.0, cloud: Double = 20.0) =
        HourPoint(t, temp, temp, prob, 0.0, 1, wind, wind + 10, 200.0, 5.0, cloud, hum, true, shortwaveRadiation = sw)

    @Test fun pavementGetsHotInSun() {
        val p = Pavement.estimate(28.0, 900.0, 5.0)!!
        assertTrue(p > Pavement.TOO_HOT)
        assertEquals(15.0, Pavement.estimate(15.0, 0.0, 10.0)!!, 0.01)
        assertNull(Pavement.estimate(20.0, null, 5.0))
    }

    @Test fun uvBurnTime() {
        assertEquals(17, UvBurn.minutes(10.0, 2))
        assertNull(UvBurn.minutes(0.5))
        assertTrue(UvBurn.minutes(10.0, 4)!! > UvBurn.minutes(10.0, 1)!!)
    }

    @Test fun aucklandSummerSunTimes() {
        val s = Sun.day(-36.85, 174.76, date, zone)
        fun local(e: Long?) = java.time.Instant.ofEpochSecond(e!!).atZone(zone).toLocalTime()
        fun near(t: LocalTime, h: Int, m: Int) = abs(t.toSecondOfDay() - LocalTime.of(h, m).toSecondOfDay()) <= 6 * 60
        assertTrue("sunrise ${local(s.sunrise)}", near(local(s.sunrise), 5, 59))
        assertTrue("sunset ${local(s.sunset)}", near(local(s.sunset), 20, 41))
        assertTrue(s.dusk!! > s.sunset!! && s.dusk!! - s.sunset!! in 1500..2700)
        assertTrue(s.goldenFrom!! < s.sunset!!)
    }

    @Test fun dryingDay() {
        val good = (9..16).map { hour(at(it), temp = 24.0, hum = 40.0, wind = 20.0, sw = 700.0) }
        assertTrue(DryingDay.of(good, date, zone)!!.score >= 7)
        val wet = (9..16).map { hour(at(it), prob = if (it in 12..14) 70.0 else 10.0) }
        val d = DryingDay.of(wet, date, zone)!!
        assertTrue(d.score <= 3)
        assertEquals(3, d.wetHours)
        assertNotNull(d.bestFrom)
    }

    @Test fun gardenWatering() {
        fun day(i: Long, rain: Double, et: Double) = DayPoint(date.plusDays(i).toString(), 0, 1, 25.0, 15.0, rain, 10.0, 30.0, 8.0, null, null, et)
        val w = Garden.watering(listOf(day(0, 0.0, 5.0), day(1, 1.0, 5.0), day(2, 0.0, 5.0)), date)!!
        assertEquals(14.0, w.deficit, 0.01)
        assertTrue(w.text.startsWith("Water"))
        assertNull(Garden.watering(listOf(day(0, 0.0, 5.0)), date))
    }

    @Test fun frostOnClearCalmNight() {
        val evening = date.atTime(20, 0).atZone(zone).toEpochSecond()
        val hours = (0..14).map { i -> hour(evening + i * 3600L, temp = if (i == 9) 1.5 else 6.0, wind = 3.0, cloud = 5.0) }
        val n = Frost.tonight(hours, evening, zone, threshold = 2.0)!!
        assertTrue(n.frost)
        assertEquals(1.5, n.low, 0.01)
        assertNull(Frost.tonight(hours.map { it.copy(temperature = 8.0) }, evening, zone, 2.0))
        val cloudy = hours.map { it.copy(cloudCover = 90.0, windSpeed = 20.0) }
        assertFalse(Frost.tonight(cloudy, evening, zone, 2.0)!!.frost)
    }
}

class AirAndWarningsTest {
    @Test fun airNowAndPollen() {
        val hours = (0..30).map { i -> AirHour(i * 3600L, if (i == 20) 70.0 else 15.0, 30.0, 5.0, 10.0, 50.0, 0.0, mapOf("grass" to 60.0, "birch" to 0.0)) }
        val a = Air.now(hours, 5 * 3600L)!!
        assertEquals("Good", a.category)
        assertEquals(70.0, a.worst!!.europeanAqi!!, 0.01)
        assertEquals(listOf("grass" to "high"), a.pollen)
    }

    private val cap = """<?xml version="1.0" encoding="UTF-8"?>
<alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
  <identifier>abc-123</identifier><sender>test</sender><sent>2026-11-22T10:00:00+13:00</sent>
  <status>Actual</status><msgType>Alert</msgType><scope>Public</scope>
  <info>
    <language>en-NZ</language><category>Met</category><event>Heavy Rain</event>
    <urgency>Expected</urgency><severity>Moderate</severity><certainty>Likely</certainty>
    <onset>2026-11-22T12:00:00+13:00</onset><expires>2026-11-23T06:00:00+13:00</expires>
    <headline>Heavy Rain Warning - Orange</headline>
    <description>Expect 80 to 100 mm of rain.</description>
    <parameter><valueName>ColourCode</valueName><value>Orange</value></parameter>
    <area><areaDesc>Auckland</areaDesc><polygon>-36.5,174.5 -36.5,175.0 -37.1,175.0 -37.1,174.5 -36.5,174.5</polygon></area>
  </info>
</alert>"""

    @Test fun parsesCapAlert() {
        val w = Cap.alert(cap)!!
        assertEquals("Heavy Rain", w.event)
        assertEquals("Orange", w.colour)
        assertTrue(w.serious)
        assertTrue(w.covers(-36.85, 174.76))
        assertFalse(w.covers(-41.29, 174.78))
        assertTrue(w.active(w.onset!!))
        assertFalse(w.active(w.expires!! + 1))
    }

    @Test fun parsesRssAndAtomFeeds() {
        val rss = """<rss version="2.0"><channel><title>x</title><item><title>Heavy Rain</title><link>https://example.org/cap/1</link><guid>1</guid></item></channel></rss>"""
        assertEquals(listOf(Cap.FeedItem("Heavy Rain", "https://example.org/cap/1", "1")), Cap.feed(rss))
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>e1</id><title>Wind</title><link href="https://example.org/cap/2"/></entry></feed>"""
        assertEquals("https://example.org/cap/2", Cap.feed(atom).single().link)
        assertTrue(Cap.feed("""<rss version="2.0"><channel><title>x</title></channel></rss>""").isEmpty())
    }
}

class SeasonalBandsTest {
    private val zone = ZoneId.of("UTC")

    private fun data(days: List<LocalDate>, err: (LocalDate) -> Double): Pair<List<RunHour>, List<ObsHour>> {
        val runs = mutableListOf<RunHour>()
        val obs = mutableListOf<ObsHour>()
        days.forEach { d ->
            (0 until 24).forEach { h ->
                val t = d.atStartOfDay(zone).toEpochSecond() + h * 3600
                runs += RunHour(t, 1, 20.0, 0.0)
                obs += ObsHour(t, 20.0 + err(d), null, 0.0, null)
            }
        }
        return runs to obs
    }

    @Test fun seasonalUsesSameTimeOfYear() {
        val today = LocalDate.of(2026, 12, 1)
        val summer = (0L until 30).map { today.minusDays(it) } + (0L until 30).map { today.minusYears(1).minusDays(it) }
        val winter = (0L until 40).map { LocalDate.of(2026, 7, 1).plusDays(it) }
        val (r, o) = data(summer + winter) { if (it.monthValue in 6..8) 5.0 else 0.0 }
        val e = ForecastCheck.seasonalHighErrors(r, o, zone, today, leads = listOf(1)).single()
        assertEquals(0.0, e.p90, 0.01)
        val all = ForecastCheck.highErrors(r, o, zone, LocalDate.of(2020, 1, 1), leads = listOf(1)).single()
        assertTrue(all.p90 > 1)
    }

    @Test fun coverageCountsDaysInsideRange() {
        val start = LocalDate.of(2026, 9, 1)
        val days = (0L until 60).map { start.plusDays(it) }
        val testFrom = start.plusDays(40)
        val (r, o) = data(days) { d -> if (d.isBefore(testFrom)) (d.dayOfMonth % 5 - 2).toDouble() else if (d.dayOfMonth % 2 == 0) 10.0 else 0.0 }
        val c = ForecastCheck.coverage(r, o, zone, testFrom, leads = listOf(1)).single()
        assertEquals(20, c.total)
        assertEquals(10, c.inside)
    }
}
