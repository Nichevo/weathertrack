package app.weathertrack.core

import app.weathertrack.core.history.DailyObs
import app.weathertrack.core.history.Season
import app.weathertrack.core.history.Seasons
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

class SeasonsTest {
    private fun days(from: LocalDate, to: LocalDate, rain: (LocalDate) -> Double, temp: (LocalDate) -> Double) =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map { DailyObs(it, temp(it) + 5, temp(it) - 5, rain(it)) }.toList()

    @Test fun southernSeasons() {
        assertEquals(Season.SUMMER, Seasons.of(Month.JANUARY, southern = true))
        assertEquals(Season.SPRING, Seasons.of(Month.NOVEMBER, southern = true))
        assertEquals(Season.WINTER, Seasons.of(Month.JANUARY, southern = false))
    }

    @Test fun comparesAgainstNormal() {
        // 1990..2026: 2 mm a day and 15° normally; 2026 is twice as wet and a degree warmer.
        val obs = days(LocalDate.of(1990, 1, 1), LocalDate.of(2026, 10, 31),
            rain = { if (it.year == 2026 || (it.year == 2025 && it.monthValue >= 11)) 4.0 else 2.0 },
            temp = { if (it.year == 2026 || (it.year == 2025 && it.monthValue >= 11)) 16.0 else 15.0 })
        val v = Seasons.view(obs, YearMonth.of(2026, 10), southern = true)
        assertEquals("1991–2020", v.normalLabel)
        assertEquals(12, v.months.size)
        val oct = v.months.last()
        assertEquals(31 * 4.0, oct.rain!!, 1e-6)
        assertEquals(31 * 2.0, oct.normalRain!!, 1e-6)
        assertEquals(16.0, oct.temp!!, 1e-6)
        assertEquals(15.0, oct.normalTemp!!, 1e-6)
        // Nov–Oct splits into Spring (Nov), Summer, Autumn, Winter, Spring (Sep–Oct).
        assertEquals(listOf(Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER, Season.SPRING), v.seasons.map { it.season })
    }
}
