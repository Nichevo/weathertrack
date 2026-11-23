package app.weathertrack.core.history

import java.time.LocalDate
import java.time.YearMonth

/** One observed day from the archive. */
data class DailyObs(
    val date: LocalDate,
    val tempMax: Double?,
    val tempMin: Double?,
    val precipitation: Double?,
    /** Hours of sunshine in the day. */
    val sunshineHours: Double? = null,
    /** Strongest gust, km/h. */
    val gustMax: Double? = null,
)

/** Summary of one period (for example 1 to 21 November in one year). */
data class PeriodStats(
    val label: String,
    val days: Int,
    val meanMax: Double?,
    val meanMin: Double?,
    val totalRain: Double?,
    val rainyDays: Double?,
)

data class YearOnYear(
    val month: YearMonth,
    /** Days 1..lastDay are compared in every year. */
    val lastDay: Int,
    val years: List<PeriodStats>,
    /** Average of 1991 to 2020 over the same days, if enough data. */
    val normal: PeriodStats?,
)

data class DecadeStats(
    val decadeStart: Int,
    val years: Int,
    val meanMax: Double?,
    val meanMin: Double?,
    val meanMonthlyRain: Double?,
)

object HistoryStats {
    /** Rain at or above this (mm) makes a day "rainy". */
    const val RAINY_DAY_MM = 1.0

    fun period(label: String, obs: List<DailyObs>): PeriodStats = PeriodStats(
        label = label,
        days = obs.size,
        meanMax = obs.mapNotNull { it.tempMax }.averageOrNull(),
        meanMin = obs.mapNotNull { it.tempMin }.averageOrNull(),
        totalRain = obs.mapNotNull { it.precipitation }.takeIf { it.isNotEmpty() }?.sum(),
        rainyDays = obs.mapNotNull { it.precipitation }.takeIf { it.isNotEmpty() }?.count { it >= RAINY_DAY_MM }?.toDouble(),
    )

    /**
     * Compares days 1..lastDay of [month] across [month]'s year and the [previousYears] before it.
     * [lastDay] defaults to the last day with data in [month]'s year (or the month's length).
     * [obs] should contain at least the days of this calendar month in all years.
     */
    fun yearOnYear(obs: List<DailyObs>, month: YearMonth, previousYears: Int = 5, lastDay: Int? = null): YearOnYear {
        val inMonth = obs.filter { it.date.monthValue == month.monthValue }
        val day = lastDay ?: inMonth.filter { it.date.year == month.year && it.tempMax != null }
            .maxOfOrNull { it.date.dayOfMonth } ?: month.lengthOfMonth()
        fun slice(year: Int) = inMonth.filter { it.date.year == year && it.date.dayOfMonth <= day }

        val years = (month.year downTo month.year - previousYears).mapNotNull { y ->
            slice(y).takeIf { it.isNotEmpty() }?.let { period(y.toString(), it) }
        }
        val normalYears = (1991..2020).map { slice(it) }.filter { it.size >= day * 0.9 }
        val normal = if (normalYears.size >= 20) {
            val per = normalYears.map { period("", it) }
            PeriodStats(
                label = "1991–2020",
                days = day,
                meanMax = per.mapNotNull { it.meanMax }.averageOrNull(),
                meanMin = per.mapNotNull { it.meanMin }.averageOrNull(),
                totalRain = per.mapNotNull { it.totalRain }.averageOrNull(),
                rainyDays = per.mapNotNull { it.rainyDays }.averageOrNull(),
            )
        } else null
        return YearOnYear(month, day, years, normal)
    }

    /**
     * Averages calendar month [monthValue] by decade, using only years where at least 90% of
     * that month's days have data.
     */
    fun byDecade(obs: List<DailyObs>, monthValue: Int): List<DecadeStats> {
        val perYear = obs.filter { it.date.monthValue == monthValue }
            .groupBy { it.date.year }
            .filter { (year, days) ->
                days.count { it.tempMax != null } >= YearMonth.of(year, monthValue).lengthOfMonth() * 0.9
            }
            .mapValues { (year, days) -> period(year.toString(), days) }
        return perYear.entries.groupBy { (it.key / 10) * 10 }
            .toSortedMap()
            .map { (decade, entries) ->
                val stats = entries.map { it.value }
                DecadeStats(
                    decadeStart = decade,
                    years = stats.size,
                    meanMax = stats.mapNotNull { it.meanMax }.averageOrNull(),
                    meanMin = stats.mapNotNull { it.meanMin }.averageOrNull(),
                    meanMonthlyRain = stats.mapNotNull { it.totalRain }.averageOrNull(),
                )
            }
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
