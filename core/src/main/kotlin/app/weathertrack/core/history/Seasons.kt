package app.weathertrack.core.history

import java.time.Month
import java.time.YearMonth

enum class Season(val label: String) { SUMMER("Summer"), AUTUMN("Autumn"), WINTER("Winter"), SPRING("Spring") }

/** One month's rain and mean temperature against its long-term average. */
data class MonthCompare(
    val month: YearMonth,
    val season: Season,
    val days: Int,
    val rain: Double?,
    val normalRain: Double?,
    /** Mean of daily (max + min) / 2. */
    val temp: Double?,
    val normalTemp: Double?,
)

data class SeasonCompare(
    val season: Season,
    val from: YearMonth,
    val to: YearMonth,
    val rain: Double?,
    val normalRain: Double?,
    val temp: Double?,
    val normalTemp: Double?,
)

data class SeasonView(
    val months: List<MonthCompare>,
    val seasons: List<SeasonCompare>,
    /** What the averages cover, e.g. "1991–2020". */
    val normalLabel: String,
)

object Seasons {
    /** A month counts when it has at least this many days of data. */
    const val MIN_DAYS = 25

    /** Meteorological seasons, flipped for the southern hemisphere. */
    fun of(month: Month, southern: Boolean): Season {
        val north = when (month) {
            Month.DECEMBER, Month.JANUARY, Month.FEBRUARY -> Season.WINTER
            Month.MARCH, Month.APRIL, Month.MAY -> Season.SPRING
            Month.JUNE, Month.JULY, Month.AUGUST -> Season.SUMMER
            else -> Season.AUTUMN
        }
        if (!southern) return north
        return when (north) {
            Season.WINTER -> Season.SUMMER
            Season.SUMMER -> Season.WINTER
            Season.SPRING -> Season.AUTUMN
            Season.AUTUMN -> Season.SPRING
        }
    }

    private class MonthSum(val days: Int, val rain: Double?, val temp: Double?)

    private fun sum(obs: List<DailyObs>): MonthSum {
        val rain = obs.mapNotNull { it.precipitation }
        val temps = obs.mapNotNull { o -> if (o.tempMax != null && o.tempMin != null) (o.tempMax + o.tempMin) / 2 else null }
        return MonthSum(obs.size, rain.takeIf { it.isNotEmpty() }?.sum(), temps.takeIf { it.isNotEmpty() }?.average())
    }

    /**
     * The 12 months ending [last], each against the average of the same calendar month over
     * 1991–2020 (or every earlier year when that period isn't stored), grouped into seasons.
     */
    fun view(obs: List<DailyObs>, last: YearMonth, southern: Boolean): SeasonView {
        val byMonth = obs.groupBy { YearMonth.from(it.date) }.mapValues { sum(it.value) }
        val full = byMonth.filterValues { it.days >= MIN_DAYS }
        val normalYears = 1991..2020
        val useNormal = (1..12).all { m -> normalYears.count { y -> full.containsKey(YearMonth.of(y, m)) } >= 20 }
        fun inNormal(ym: YearMonth) = if (useNormal) ym.year in normalYears else ym.isBefore(last.minusMonths(11))
        val normals = (1..12).associateWith { m ->
            val ms = full.filterKeys { it.monthValue == m && inNormal(it) }.values
            Pair(ms.mapNotNull { it.rain }.takeIf { it.isNotEmpty() }?.average(), ms.mapNotNull { it.temp }.takeIf { it.isNotEmpty() }?.average())
        }
        val months = (11 downTo 0).map { back ->
            val ym = last.minusMonths(back.toLong())
            val s = byMonth[ym]
            val n = normals[ym.monthValue]
            MonthCompare(ym, of(ym.month, southern), s?.days ?: 0, s?.rain, n?.first, s?.temp, n?.second)
        }
        val seasons = mutableListOf<SeasonCompare>()
        var run = mutableListOf<MonthCompare>()
        fun flush() {
            if (run.isEmpty()) return
            val complete = run.filter { it.days >= MIN_DAYS }
            fun total(f: (MonthCompare) -> Double?) = complete.mapNotNull(f).takeIf { it.size == complete.size && it.isNotEmpty() }
            seasons += SeasonCompare(
                run.first().season, run.first().month, run.last().month,
                total { it.rain }?.sum(), total { it.normalRain }?.sum(),
                total { it.temp }?.average(), total { it.normalTemp }?.average(),
            )
            run = mutableListOf()
        }
        for (m in months) {
            if (run.isNotEmpty() && run.last().season != m.season) flush()
            run += m
        }
        flush()
        val label = if (useNormal) "1991–2020" else "earlier years"
        return SeasonView(months, seasons, label)
    }
}
