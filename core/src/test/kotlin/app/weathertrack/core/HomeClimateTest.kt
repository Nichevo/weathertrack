package app.weathertrack.core

import app.weathertrack.core.climate.ClimateDay
import app.weathertrack.core.climate.DegreeDayBases
import app.weathertrack.core.climate.EnergyAnalysis
import app.weathertrack.core.climate.EnergyCsv
import app.weathertrack.core.climate.HomeClimate
import app.weathertrack.core.openmeteo.OpenMeteoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class HomeClimateTest {
    private val bases = DegreeDayBases(heating = 18.0, cooling = 22.0)

    private fun day(date: LocalDate, mean: Double, rh: Double? = 70.0, dew: Double? = 10.0) =
        ClimateDay(date, mean, mean + 4, mean - 4, rh, dew, 18.0, 6 * 3600.0, 50.0)

    /** Every day from 1991 to [to] with a mean of 12° (6 HDD), except 2026 which is 2° warmer. */
    private fun series(to: LocalDate): List<ClimateDay> =
        generateSequence(LocalDate.of(1991, 1, 1)) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map { day(it, if (it.year == 2026) 14.0 else 12.0) }.toList()

    @Test fun degreeDays() {
        assertEquals(6.0, HomeClimate.hdd(12.0, bases), 1e-9)
        assertEquals(0.0, HomeClimate.hdd(20.0, bases), 1e-9)
        assertEquals(3.0, HomeClimate.cdd(25.0, bases), 1e-9)
        assertEquals(0.0, HomeClimate.cdd(21.0, bases), 1e-9)
    }

    @Test fun summaryCountsMuggyAndDampDays() {
        val d0 = LocalDate.of(2026, 1, 1)
        val days = listOf(
            day(d0, 24.0, rh = 80.0, dew = 18.0),
            day(d0.plusDays(1), 10.0, rh = 90.0, dew = 8.0),
            day(d0.plusDays(2), 16.0, rh = 60.0, dew = 9.0),
        )
        val s = HomeClimate.summarize("x", days, bases)
        assertEquals(8.0 + 2.0, s.hdd!!, 1e-9)
        assertEquals(2.0, s.cdd!!, 1e-9)
        assertEquals(1.0, s.muggyDays!!, 1e-9)
        assertEquals(1.0, s.dampColdDays!!, 1e-9)
        assertEquals(5.0, s.solarKwhPerDay!!, 1e-9) // 18 MJ = 5 kWh
        assertEquals(6.0, s.sunshineHoursPerDay!!, 1e-9)
    }

    @Test fun monthToDateComparesSameDaysWithNormal() {
        val all = series(LocalDate.of(2026, 11, 19))
        val c = HomeClimate.monthToDate(all, YearMonth.of(2026, 11), bases)!!
        assertEquals(LocalDate.of(2026, 11, 19), c.end)
        assertEquals("2026", c.years.first().label)
        assertEquals(19 * 4.0, c.years.first().hdd!!, 1e-9)
        assertEquals(19 * 6.0, c.years[1].hdd!!, 1e-9)
        assertEquals(19 * 6.0, c.normal!!.hdd!!, 1e-9)
    }

    @Test fun noNormalWithoutTwentyYears() {
        val all = series(LocalDate.of(2026, 11, 19)).filter { it.date.year >= 2005 }
        assertNull(HomeClimate.monthToDate(all, YearMonth.of(2026, 11), bases)!!.normal)
    }

    @Test fun monthsWeeksAndYears() {
        val all = series(LocalDate.of(2026, 11, 19))
        val months = HomeClimate.months(all, YearMonth.of(2026, 10), bases)
        assertEquals(12, months.size)
        assertEquals(YearMonth.of(2026, 10).atDay(1), months.first().start)
        assertEquals(31 * 4.0, months.first().summary.hdd!!, 1e-9)
        assertEquals(31 * 6.0, months.first().normal!!.hdd!!, 1e-9)
        // November 2026 is incomplete, so asking for it skips it.
        assertEquals(11, HomeClimate.months(all, YearMonth.of(2026, 11), bases).size)

        val weeks = HomeClimate.weeks(all, LocalDate.of(2026, 11, 19), bases)
        assertEquals(8, weeks.size)
        assertEquals(LocalDate.of(2026, 11, 13), weeks.first().start)
        assertEquals(28.0, weeks.first().summary.hdd!!, 1e-9)

        val years = HomeClimate.years(all, bases)
        assertEquals("1991", years.first().label)
        assertEquals("2025", years.last().label) // 2026 is not complete
    }

    @Test fun energyFitRecoversSlope() {
        val d0 = LocalDate.of(2025, 6, 1)
        val climate = (0 until 60).map { day(d0.plusDays(it.toLong()), 8.0 + (it % 15)) }
        // 10 kWh base + 1.5 kWh per HDD.
        val kwh = climate.associate { it.date to 10.0 + 1.5 * HomeClimate.hdd(it.tempMean!!, bases) }
        val fit = EnergyAnalysis.fit(climate, kwh, bases)!!
        assertEquals(10.0, fit.baseKwhPerDay, 1e-6)
        assertEquals(1.5, fit.kwhPerHdd!!, 1e-6)
        assertNull(fit.kwhPerCdd)
        assertEquals(1.0, fit.r2, 1e-9)
        assertNull(EnergyAnalysis.fit(climate.take(10), kwh, bases))

        val months = EnergyAnalysis.byMonth(climate, kwh, bases)
        assertEquals(YearMonth.of(2025, 7), months.first().month)
        assertEquals(30, months.first().days) // 1 to 30 July
    }

    @Test fun csvHalfHourlyDayFirstIsSummedPerDay() {
        val csv = """
            ICP,Read Date,Start Time,kWh,Status
            0000X,30/06/2025,00:00,0.50,Actual
            0000X,30/06/2025,00:30,0.25,Actual
            0000X,1/07/2025,00:00,1.00,Estimate
            0000X,bad,00:00,1.00,Actual
        """.trimIndent()
        val r = EnergyCsv.parse(csv)!!
        assertEquals("Read Date", r.dateColumn)
        assertEquals("kWh", r.valueColumn)
        assertEquals(0.75, r.days[LocalDate.of(2025, 6, 30)]!!, 1e-9)
        assertEquals(1.0, r.days[LocalDate.of(2025, 7, 1)]!!, 1e-9)
        assertEquals(1, r.rowsSkipped)
    }

    @Test fun csvIsoWithoutHeaderAndSemicolons() {
        val r = EnergyCsv.parse("2025-01-02T00:00;12,5\n2025-01-03T00:00;\"14\"\n".replace("12,5", "12.5"))!!
        assertEquals(12.5, r.days[LocalDate.of(2025, 1, 2)]!!, 1e-9)
        assertEquals(14.0, r.days[LocalDate.of(2025, 1, 3)]!!, 1e-9)
        assertNull(EnergyCsv.parse("name,value\nfoo,1\n"))
    }

    @Test fun parsesClimateArchive() {
        val body = """{"daily":{"time":["2025-07-01","2025-07-02"],"temperature_2m_mean":[9.5,null],
            "temperature_2m_max":[13.0,14.0],"temperature_2m_min":[6.0,7.0],"shortwave_radiation_sum":[7.2,8.0],
            "sunshine_duration":[18000.0,20000.0]}}"""
        val days = OpenMeteoParser.parseClimateDaily(body)
        assertEquals(2, days.size)
        assertEquals(9.5, days[0].tempMean!!, 1e-9)
        assertNull(days[1].tempMean)
        assertNull(days[0].humidityMean)
        assertNotNull(days[1].solarMj)
    }
}
