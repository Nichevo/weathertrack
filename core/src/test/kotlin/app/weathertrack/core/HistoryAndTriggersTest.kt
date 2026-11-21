package app.weathertrack.core

import app.weathertrack.core.alerts.TriggerCalculator
import app.weathertrack.core.alerts.TriggerKind
import app.weathertrack.core.history.DailyObs
import app.weathertrack.core.history.HistoryStats
import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.model.QuarterHourPoint
import app.weathertrack.core.openmeteo.OpenMeteoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class HistoryAndTriggersTest {
    /** Synthetic November: max = 15 + (year - 1940) * 0.05, min = max - 8, rain 2 mm every 3rd day. */
    private fun synthetic(from: Int, to: Int, month: Int = 11): List<DailyObs> =
        (from..to).flatMap { y ->
            (1..YearMonth.of(y, month).lengthOfMonth()).map { d ->
                val max = 15 + (y - 1940) * 0.05
                DailyObs(LocalDate.of(y, month, d), max, max - 8, if (d % 3 == 0) 2.0 else 0.0)
            }
        }

    @Test fun yearOnYearComparesSameDays() {
        val obs = synthetic(1940, 2025) + synthetic(2026, 2026).filter { it.date.dayOfMonth <= 16 }
        val yoy = HistoryStats.yearOnYear(obs, YearMonth.of(2026, 11))
        assertEquals(16, yoy.lastDay)
        assertEquals(listOf("2026", "2025", "2024", "2023", "2022", "2021"), yoy.years.map { it.label })
        assertEquals(16, yoy.years[1].days)
        assertEquals(10.0, yoy.years[0].totalRain!!, 1e-9) // days 3,6,9,12,15
        assertEquals(5.0, yoy.years[0].rainyDays!!, 1e-9)
        val normal = assertNotNullAndGet(yoy.normal)
        assertEquals(15 + (2005.5 - 1940) * 0.05, normal.meanMax!!, 1e-9)
    }

    @Test fun noNormalWithoutEnoughYears() {
        val yoy = HistoryStats.yearOnYear(synthetic(2015, 2026), YearMonth.of(2026, 11))
        assertNull(yoy.normal)
    }

    @Test fun decadesSkipIncompleteYears() {
        val obs = synthetic(1940, 2025) + synthetic(2026, 2026).take(10)
        val decades = HistoryStats.byDecade(obs, 11)
        assertEquals(1940, decades.first().decadeStart)
        assertEquals(2020, decades.last().decadeStart)
        assertEquals(6, decades.last().years) // 2020..2025, 2026 incomplete
        assertEquals(20.0, decades.last().meanMonthlyRain!!, 1e-9) // 10 rainy days x 2 mm
        assertTrue(decades.last().meanMax!! > decades.first().meanMax!!)
    }

    @Test fun parsesArchive() {
        val body = """{"latitude":-36.875,"longitude":174.75,"timezone":"Pacific/Auckland","utc_offset_seconds":46800,
          "daily":{"time":["1940-01-01","1940-01-02"],"temperature_2m_max":[22.1,null],"temperature_2m_min":[15.0,null],
          "temperature_2m_mean":[18.2,null],"apparent_temperature_max":[23.0,null],"apparent_temperature_min":[14.0,null],
          "precipitation_sum":[0.4,null],"rain_sum":[0.4,null],"precipitation_hours":[2,null],"wind_gusts_10m_max":[40.3,null],
          "sunshine_duration":[30000.5,null],"weather_code":[51,null]}}"""
        val days = OpenMeteoParser.parseArchiveDaily(body)
        assertEquals(2, days.size)
        assertEquals("1940-01-01", days[0].date)
        assertEquals(51, days[0].weatherCode)
        assertTrue(days[0].hasData)
        assertTrue(!days[1].hasData)
    }

    private val start = 1_800_000_000L - (1_800_000_000L % 3600)
    private fun hour(i: Int, feels: Double) =
        HourPoint(start + i * 3600L, feels, feels, 0.0, 0.0, 1, 5.0, 10.0, 0.0, 3.0, 10.0, 50.0, true)

    @Test fun rainSoonFiresWithinWindowOnly() {
        val q = (0 until 16).map { QuarterHourPoint(start + it * 900L, if (it == 2) 0.5 else 0.0) }
        val soon = TriggerCalculator.evaluate(emptyList(), q, start)
        assertEquals(TriggerKind.RAIN_SOON, soon.single().kind)
        assertEquals("Rain in about 30 min", soon.single().text)
        val later = TriggerCalculator.evaluate(emptyList(), q.map { if (it.time == start + 1800) it.copy(time = start + 3600) else it }, start)
        assertTrue(later.isEmpty())
    }

    @Test fun rainingNow() {
        val q = listOf(QuarterHourPoint(start, 0.3))
        assertEquals("Raining now", TriggerCalculator.evaluate(emptyList(), q, start + 300).single().text)
    }

    @Test fun hotAndCold() {
        val hours = listOf(hour(0, 20.0), hour(1, 28.4), hour(2, 4.0), hour(8, 40.0))
        val t = TriggerCalculator.evaluate(hours, emptyList(), start) { "T$it" }
        assertEquals(setOf(TriggerKind.TOO_HOT, TriggerKind.TOO_COLD), t.map { it.kind }.toSet())
        assertEquals(28.4, t.first { it.kind == TriggerKind.TOO_HOT }.value, 0.0) // hour 8 is outside 6 h
        assertTrue(TriggerCalculator.evaluate(listOf(hour(0, 18.0)), emptyList(), start).isEmpty())
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
