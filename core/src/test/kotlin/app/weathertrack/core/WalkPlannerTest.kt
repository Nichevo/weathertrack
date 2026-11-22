package app.weathertrack.core

import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.walk.WalkIssueKind
import app.weathertrack.core.walk.WalkPlanner
import app.weathertrack.core.walk.WalkRating
import app.weathertrack.core.walk.WalkWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class WalkPlannerTest {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val date = LocalDate.of(2026, 12, 20)
    private fun at(h: Int, m: Int = 0) = date.atTime(h, m).atZone(zone).toEpochSecond()

    private fun hour(
        h: Int, feels: Double = 18.0, temp: Double = feels, prob: Double = 0.0, gust: Double = 15.0,
        hum: Double = 60.0, cloud: Double = 60.0, uv: Double = 2.0, code: Int = 2,
    ) = HourPoint(at(h), temp, feels, prob, 0.0, code, 8.0, gust, 200.0, uv, cloud, hum, true, cloud / 6, cloud * 2 / 3, 20.0)

    private val morning = WalkWindow("Morning", LocalTime.of(7, 0), LocalTime.of(9, 0))
    private val evening = WalkWindow("Evening", LocalTime.of(17, 0), LocalTime.of(19, 0))

    @Test fun mildHourIsGood() {
        val r = WalkPlanner.rateHour(hour(7))
        assertEquals(WalkRating.GOOD, r.rating)
        assertTrue(r.issues.isEmpty())
    }

    @Test fun hotHourIsPoorForThePup() {
        val r = WalkPlanner.rateHour(hour(17, feels = 26.0, cloud = 10.0))
        assertEquals(WalkRating.POOR, r.rating)
        assertTrue(r.issues.any { it.kind == WalkIssueKind.PET_HEAT })
        assertTrue(r.issues.any { it.kind == WalkIssueKind.HOT_PAVEMENT })
    }

    @Test fun muggyRainyGustyUv() {
        val r = WalkPlanner.rateHour(hour(17, feels = 20.0, prob = 30.0, gust = 42.0, hum = 88.0, uv = 9.0))
        assertEquals(WalkRating.FAIR, r.rating)
        assertEquals(setOf(WalkIssueKind.RAIN, WalkIssueKind.WIND, WalkIssueKind.MUGGY, WalkIssueKind.UV), r.issues.map { it.kind }.toSet())
    }

    @Test fun upcomingPicksNextWindowsAndBestHour() {
        val hours = (0..47).map { i ->
            val h = i % 24
            if (i < 24 && h == 17) hour(h, feels = 26.0) else hour(h)
        }.mapIndexed { i, p -> if (i >= 24) p.copy(time = p.time + 86400) else p }
        val now = at(12)
        val walks = WalkPlanner.upcoming(hours, listOf(morning, evening), zone, now)
        assertEquals(2, walks.size)
        assertEquals("Evening", walks[0].window.name)
        assertEquals(WalkRating.POOR, walks[0].rating)
        assertEquals(at(18), walks[0].bestHour)
        assertEquals("Morning", walks[1].window.name)
        assertEquals(date.plusDays(1), walks[1].date)
        assertEquals(10.0, walks[0].cloudLow!!, 0.0)
    }

    @Test fun windowInProgressStartsNow() {
        val hours = (0..23).map { hour(it) }
        val walks = WalkPlanner.upcoming(hours, listOf(morning), zone, at(8, 15), count = 1)
        assertEquals(at(8, 15), walks.single().start)
        assertEquals(1, walks.single().hours.size)
        assertNull(walks.single().bestHour)
    }
}
