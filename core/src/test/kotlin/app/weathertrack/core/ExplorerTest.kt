package app.weathertrack.core

import app.weathertrack.core.history.DailyObs
import app.weathertrack.core.history.ExploreMeasure
import app.weathertrack.core.history.ExploreSpan
import app.weathertrack.core.history.Explorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ExplorerTest {
    /** Every day from [from] to [to]: high = 15 + year offset, rain 3 mm on every 3rd day of the month, 6 h sun. */
    private fun days(from: LocalDate, to: LocalDate): List<DailyObs> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            val max = 15 + (d.year - 1940) * 0.05
            DailyObs(d, max, max - 8, if (d.dayOfMonth % 3 == 0) 3.0 else 0.0, sunshineHours = 6.0, gustMax = 40.0)
        }.toList()

    private val obs = days(LocalDate.of(1985, 1, 1), LocalDate.of(2026, 11, 16))

    @Test fun rainBuildsUpThroughTheMonth() {
        val v = Explorer.view(obs, ExploreMeasure.RAIN, ExploreSpan.MONTH, YearMonth.of(2026, 11))
        assertEquals(16, v.days)
        val now = v.series.first()
        assertEquals("2026", now.label)
        assertEquals(16, now.values.size)
        assertEquals(15.0, now.values.last()!!, 1e-9) // days 3,6,9,12,15
        assertEquals(3.0, now.values[2]!!, 1e-9)
        // Earlier years run the full month on the chart.
        assertEquals(30, v.series[1].values.size)
        assertEquals(listOf("2026", "2025", "2024"), v.series.map { it.label })
        // The table compares the same 16 days.
        assertEquals(15.0, v.rows[1].value!!, 1e-9)
        assertEquals(5, v.rows[0].count)
        assertEquals(15.0, v.normal!!, 1e-9)
        assertEquals(0.0, v.rows[0].vsNormal!!, 1e-9)
        assertNotNull(v.band)
        assertEquals(30, v.band!!.years)
    }

    @Test fun dailyMeasureAveragesAndRanks() {
        val v = Explorer.view(obs, ExploreMeasure.HIGH, ExploreSpan.MONTH, YearMonth.of(2026, 11))
        val r = v.rows.first()
        assertEquals(15 + 86 * 0.05, r.value!!, 1e-9)
        assertEquals(1, r.rank) // warming trend makes the newest year warmest
        assertEquals("1st warmest", Explorer.rankText(r, ExploreMeasure.HIGH))
        assertEquals(42, r.rankOf)
        assertEquals("2nd warmest", Explorer.rankText(v.rows[1], ExploreMeasure.HIGH))
        assertTrue(v.decades.first().decadeStart == 1980 && v.decades.last().decadeStart == 2020)
    }

    @Test fun lowEndRankReadsFromTheBottom() {
        val v = Explorer.view(obs, ExploreMeasure.LOW, ExploreSpan.MONTH, YearMonth.of(1986, 11))
        assertEquals("2nd coldest", Explorer.rankText(v.rows.first(), ExploreMeasure.LOW))
        // An old period still gets the 1991–2020 normal from later years.
        assertNotNull(v.normal)
    }

    @Test fun seasonsCrossTheNewYear() {
        assertEquals(LocalDate.of(2025, 12, 1), ExploreSpan.SEASON.start(YearMonth.of(2026, 2)))
        assertEquals(LocalDate.of(2026, 9, 1), ExploreSpan.SEASON.start(YearMonth.of(2026, 11)))
        assertEquals(LocalDate.of(2026, 2, 28), ExploreSpan.SEASON.end(LocalDate.of(2025, 12, 1)))
        assertEquals("Summer 2025–26", Explorer.periodLabel(ExploreSpan.SEASON, LocalDate.of(2025, 12, 1), southern = true))
        val v = Explorer.view(obs, ExploreMeasure.SUN, ExploreSpan.SEASON, YearMonth.of(2026, 1))
        assertEquals("2025–26", v.rows.first().label)
        assertEquals(90 * 6.0, v.rows.first().value!!, 1e-9)
        assertEquals(YearMonth.of(2026, 4), ExploreSpan.SEASON.step(YearMonth.of(2026, 1), 1))
    }

    @Test fun yearSpanAndNoNormalWithoutEnoughYears() {
        val short = days(LocalDate.of(2015, 1, 1), LocalDate.of(2026, 11, 16))
        val v = Explorer.view(short, ExploreMeasure.RAIN, ExploreSpan.YEAR, YearMonth.of(2026, 5))
        assertEquals(LocalDate.of(2026, 1, 1), v.start)
        assertEquals(320, v.days)
        assertNull(v.band)
        assertNull(v.normal)
        assertNull(v.rows.first().vsNormal)
    }

    @Test fun csvHasTableAndDailyLines() {
        val v = Explorer.view(obs, ExploreMeasure.RAIN, ExploreSpan.MONTH, YearMonth.of(2026, 11))
        val lines = Explorer.csv(v).lines()
        assertTrue(lines[1].startsWith("year,period_start,days,total_mm"))
        assertTrue(lines[2].startsWith("2026,2026-11-01,16,15.00"))
        assertTrue(lines.any { it.startsWith("day,y2026,y2025,y2024,normal_mean") })
        val raw = Explorer.rawCsv(obs, v).lines().filter { it.isNotBlank() }
        assertEquals(1 + 16 + 5 * 30, raw.size)
    }
}
