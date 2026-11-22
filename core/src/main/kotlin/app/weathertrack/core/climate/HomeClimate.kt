package app.weathertrack.core.climate

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.max

/** One observed day of the weather that drives home heating and cooling. */
data class ClimateDay(
    val date: LocalDate,
    val tempMean: Double?,
    val tempMax: Double?,
    val tempMin: Double?,
    /** Mean relative humidity, %. */
    val humidityMean: Double?,
    /** Mean dew point, °C. The best single measure of how muggy it feels. */
    val dewPointMean: Double?,
    /** Solar energy reaching the ground (shortwave radiation), MJ/m² for the day. */
    val solarMj: Double?,
    val sunshineSeconds: Double?,
    /** Mean total cloud cover, %. */
    val cloudMean: Double?,
)

/**
 * Degree-day base temperatures. A day with a mean of 12° against a heating base of 18° has 6
 * heating degree days (HDD): roughly how much heating the house needs. Cooling degree days (CDD)
 * count how far the mean goes above the cooling base.
 */
data class DegreeDayBases(val heating: Double = 18.0, val cooling: Double = 22.0)

/** Totals and averages for one period (a month so far, a week, a year…). */
data class ClimateSummary(
    val label: String,
    val days: Int,
    val hdd: Double?,
    val cdd: Double?,
    val meanTemp: Double?,
    val meanHumidity: Double?,
    val meanDewPoint: Double?,
    /** Average daily solar energy, kWh per m². */
    val solarKwhPerDay: Double?,
    val sunshineHoursPerDay: Double?,
    val muggyDays: Double?,
    val dampColdDays: Double?,
)

data class ClimateComparison(
    val start: LocalDate,
    val end: LocalDate,
    /** Newest year first. */
    val years: List<ClimateSummary>,
    /** Average of 1991 to 2020 over the same dates, when at least 20 of those years have the data. */
    val normal: ClimateSummary?,
)

data class PeriodRow(val start: LocalDate, val end: LocalDate, val summary: ClimateSummary, val normal: ClimateSummary?)

object HomeClimate {
    /** A day is muggy (cooling or dehumidifying helps) when the mean dew point reaches this. */
    const val MUGGY_DEW_POINT = 16.0
    /** A day is damp and cold (heating or a dehumidifier helps) at this humidity and below [DAMP_COLD_MAX_TEMP]. */
    const val DAMP_HUMIDITY = 85.0
    const val DAMP_COLD_MAX_TEMP = 12.0

    private val NORMAL_YEARS = 1991..2020
    private const val MIN_NORMAL_YEARS = 20
    private const val MIN_COVERAGE = 0.9

    fun hdd(mean: Double, bases: DegreeDayBases) = max(0.0, bases.heating - mean)
    fun cdd(mean: Double, bases: DegreeDayBases) = max(0.0, mean - bases.cooling)

    fun summarize(label: String, days: List<ClimateDay>, bases: DegreeDayBases): ClimateSummary {
        val means = days.mapNotNull { it.tempMean }
        fun avg(f: (ClimateDay) -> Double?) = days.mapNotNull(f).averageOrNull()
        val dew = days.filter { it.dewPointMean != null }
        val damp = days.filter { it.humidityMean != null && it.tempMean != null }
        return ClimateSummary(
            label = label,
            days = days.size,
            hdd = means.takeIf { it.isNotEmpty() }?.sumOf { hdd(it, bases) },
            cdd = means.takeIf { it.isNotEmpty() }?.sumOf { cdd(it, bases) },
            meanTemp = means.averageOrNull(),
            meanHumidity = avg { it.humidityMean },
            meanDewPoint = avg { it.dewPointMean },
            solarKwhPerDay = avg { it.solarMj }?.let { it / 3.6 },
            sunshineHoursPerDay = avg { it.sunshineSeconds }?.let { it / 3600 },
            muggyDays = dew.takeIf { it.isNotEmpty() }?.count { it.dewPointMean!! >= MUGGY_DEW_POINT }?.toDouble(),
            dampColdDays = damp.takeIf { it.isNotEmpty() }
                ?.count { it.humidityMean!! >= DAMP_HUMIDITY && it.tempMean!! < DAMP_COLD_MAX_TEMP }?.toDouble(),
        )
    }

    /** Days from [start] to [end] inclusive that have a mean temperature, or null if fewer than 90% do. */
    private fun span(byDate: Map<LocalDate, ClimateDay>, start: LocalDate, end: LocalDate): List<ClimateDay>? {
        val length = ChronoUnit.DAYS.between(start, end) + 1
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
            .mapNotNull { byDate[it] }.filter { it.tempMean != null }.toList()
        return days.takeIf { it.isNotEmpty() && it.size >= length * MIN_COVERAGE }
    }

    /** The 1991–2020 average for the same calendar dates as [start]..[end]. */
    fun normal(byDate: Map<LocalDate, ClimateDay>, start: LocalDate, end: LocalDate, bases: DegreeDayBases): ClimateSummary? {
        val per = NORMAL_YEARS.mapNotNull { y ->
            val shift = (y - start.year).toLong()
            span(byDate, start.plusYears(shift), end.plusYears(shift))?.let { summarize(y.toString(), it, bases) }
        }
        if (per.size < MIN_NORMAL_YEARS) return null
        fun avg(f: (ClimateSummary) -> Double?) = per.mapNotNull(f).averageOrNull()
        return ClimateSummary(
            label = "1991–2020",
            days = (ChronoUnit.DAYS.between(start, end) + 1).toInt(),
            hdd = avg { it.hdd }, cdd = avg { it.cdd }, meanTemp = avg { it.meanTemp },
            meanHumidity = avg { it.meanHumidity }, meanDewPoint = avg { it.meanDewPoint },
            solarKwhPerDay = avg { it.solarKwhPerDay }, sunshineHoursPerDay = avg { it.sunshineHoursPerDay },
            muggyDays = avg { it.muggyDays }, dampColdDays = avg { it.dampColdDays },
        )
    }

    /**
     * Compares days 1..lastDay of [month] with the same days in the [previousYears] before it.
     * [lastDay] defaults to the last day of [month] that has data.
     */
    fun monthToDate(
        all: List<ClimateDay>, month: YearMonth, bases: DegreeDayBases,
        previousYears: Int = 5, lastDay: Int? = null,
    ): ClimateComparison? {
        val byDate = all.associateBy { it.date }
        val day = lastDay ?: all.filter { YearMonth.from(it.date) == month && it.tempMean != null }
            .maxOfOrNull { it.date.dayOfMonth } ?: return null
        val start = month.atDay(1)
        val end = month.atDay(day)
        val years = (0..previousYears).mapNotNull { back ->
            val s = start.minusYears(back.toLong())
            val e = end.minusYears(back.toLong())
            span(byDate, s, e)?.let { summarize(s.year.toString(), it, bases) }
        }
        if (years.isEmpty()) return null
        return ClimateComparison(start, end, years, normal(byDate, start, end, bases))
    }

    /** The [count] complete months up to and including [last], newest first, each with its normal. */
    fun months(all: List<ClimateDay>, last: YearMonth, bases: DegreeDayBases, count: Int = 12): List<PeriodRow> {
        val byDate = all.associateBy { it.date }
        return (0 until count).mapNotNull { back ->
            val m = last.minusMonths(back.toLong())
            val s = m.atDay(1)
            val e = m.atEndOfMonth()
            span(byDate, s, e)?.let { PeriodRow(s, e, summarize(m.toString(), it, bases), normal(byDate, s, e, bases)) }
        }
    }

    /** [count] 7-day weeks ending on [lastDate], newest first, each with its normal. */
    fun weeks(all: List<ClimateDay>, lastDate: LocalDate, bases: DegreeDayBases, count: Int = 8): List<PeriodRow> {
        val byDate = all.associateBy { it.date }
        return (0 until count).mapNotNull { back ->
            val e = lastDate.minusWeeks(back.toLong())
            val s = e.minusDays(6)
            span(byDate, s, e)?.let { PeriodRow(s, e, summarize(s.toString(), it, bases), normal(byDate, s, e, bases)) }
        }
    }

    /** Totals for every calendar year with at least 95% of its days, oldest first. */
    fun years(all: List<ClimateDay>, bases: DegreeDayBases): List<ClimateSummary> =
        all.filter { it.tempMean != null }.groupBy { it.date.year }.toSortedMap()
            .filter { (year, days) -> days.size >= java.time.Year.of(year).length() * 0.95 }
            .map { (year, days) -> summarize(year.toString(), days, bases) }

    internal fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
