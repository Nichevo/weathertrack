package app.weathertrack.core.history

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** What the History explorer charts. Running totals build up through the period; the rest are daily values. */
enum class ExploreMeasure(
    val label: String,
    val unit: String,
    val cumulative: Boolean,
    val countLabel: String,
    val countTest: (Double) -> Boolean,
    val high: String,
    val low: String,
) {
    HIGH("High", "°", false, "Days ≥ 25°", { it >= 25.0 }, "warmest", "coolest"),
    LOW("Low", "°", false, "Nights ≤ 2°", { it <= 2.0 }, "mildest", "coldest"),
    RAIN("Rain", "mm", true, "Rain days", { it >= HistoryStats.RAINY_DAY_MM }, "wettest", "driest"),
    SUN("Sun", "h", true, "Sunny days (8 h+)", { it >= 8.0 }, "sunniest", "dullest"),
    GUST("Gusts", "km/h", false, "Days ≥ 60 km/h", { it >= 60.0 }, "windiest", "calmest");

    fun of(o: DailyObs): Double? = when (this) {
        HIGH -> o.tempMax
        LOW -> o.tempMin
        RAIN -> o.precipitation
        SUN -> o.sunshineHours
        GUST -> o.gustMax
    }
}

/** How long a period the explorer looks at, anchored on a month. */
enum class ExploreSpan(val label: String) {
    MONTH("Month"), SEASON("Season"), YEAR("Year");

    /** First day of the period that contains [month]. Seasons are Dec–Feb, Mar–May, Jun–Aug, Sep–Nov. */
    fun start(month: YearMonth): LocalDate = when (this) {
        MONTH -> month.atDay(1)
        SEASON -> month.minusMonths((month.monthValue % 3).toLong()).atDay(1)
        YEAR -> LocalDate.of(month.year, 1, 1)
    }

    fun end(start: LocalDate): LocalDate = when (this) {
        MONTH -> start.plusMonths(1).minusDays(1)
        SEASON -> start.plusMonths(3).minusDays(1)
        YEAR -> start.plusYears(1).minusDays(1)
    }

    /** Moves the anchor month one period back or forward. */
    fun step(month: YearMonth, delta: Int): YearMonth = when (this) {
        MONTH -> month.plusMonths(delta.toLong())
        SEASON -> month.plusMonths(3L * delta)
        YEAR -> month.plusYears(delta.toLong())
    }
}

/** One line on the chart: value per day from the start of the period (null where there's no data). */
data class ExploreSeries(val label: String, val start: LocalDate, val values: List<Double?>)

/** The usual value on each day of the period, from 1991–2020: average and the 1-in-10 range either side. */
data class ExploreBand(val mean: List<Double?>, val p10: List<Double?>, val p90: List<Double?>, val years: Int)

/** One year's summary over the same days. */
data class ExploreRow(
    val label: String,
    val start: LocalDate,
    val days: Int,
    /** Total for running measures, average for daily ones. */
    val value: Double?,
    val vsNormal: Double?,
    /** Highest day (lowest for Low), and when. */
    val extreme: Double?,
    val extremeDate: LocalDate?,
    val count: Int?,
    /** 1 = highest value among [rankOf] years with enough data. */
    val rank: Int?,
    val rankOf: Int,
)

data class ExploreDecade(val decadeStart: Int, val years: Int, val value: Double?)

data class ExploreView(
    val measure: ExploreMeasure,
    val span: ExploreSpan,
    val start: LocalDate,
    val end: LocalDate,
    /** Days compared in every year: 1..days from the start (the newest data in the current period). */
    val days: Int,
    /** Current period first, then earlier years, newest first. */
    val series: List<ExploreSeries>,
    val band: ExploreBand?,
    val rows: List<ExploreRow>,
    val normal: Double?,
    val decades: List<ExploreDecade>,
) {
    val periodLength: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1
}

object Explorer {
    private const val COVERAGE = 0.9

    fun periodLabel(span: ExploreSpan, start: LocalDate, southern: Boolean = true): String = when (span) {
        ExploreSpan.MONTH -> "${start.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)} ${start.year}"
        ExploreSpan.SEASON -> {
            val s = Seasons.of(start.month, southern).label
            if (start.monthValue == 12) "$s ${start.year}–${(start.year + 1) % 100}" else "$s ${start.year}"
        }
        ExploreSpan.YEAR -> start.year.toString()
    }

    private fun yearLabel(span: ExploreSpan, start: LocalDate) =
        if (span == ExploreSpan.SEASON && start.monthValue == 12) "${start.year}–${"%02d".format((start.year + 1) % 100)}"
        else start.year.toString()

    /**
     * Builds the explorer view for the period of [span] containing [month], compared with the [previousYears]
     * before it over the same days. [obs] can hold any days; it is indexed by date.
     */
    fun view(
        obs: List<DailyObs>,
        measure: ExploreMeasure,
        span: ExploreSpan,
        month: YearMonth,
        previousYears: Int = 5,
        chartYears: Int = 2,
    ): ExploreView {
        val byDate = HashMap<LocalDate, Double>(obs.size * 2)
        obs.forEach { o -> measure.of(o)?.let { byDate[o.date] = it } }
        val start = span.start(month)
        val end = span.end(start)
        val length = ChronoUnit.DAYS.between(start, end).toInt() + 1

        // Compare up to the newest day with data in the current period.
        val days = (length downTo 1).firstOrNull { byDate.containsKey(start.plusDays(it - 1L)) } ?: length

        fun startOf(yearsBack: Int) = start.minusYears(yearsBack.toLong())
        fun dailies(s: LocalDate, n: Int): List<Double?> = (0 until n).map { byDate[s.plusDays(it.toLong())] }
        fun running(v: List<Double?>): List<Double?> {
            var sum = 0.0
            var seen = false
            return v.map { x -> if (x == null) null else { sum += x; seen = true; sum } }.let { if (seen) it else it.map { null } }
        }
        fun line(s: LocalDate, n: Int) = dailies(s, n).let { if (measure.cumulative) running(it) else it }
        fun summary(v: List<Double?>): Double? {
            val xs = v.filterNotNull()
            if (xs.isEmpty()) return null
            return if (measure.cumulative) xs.sum() else xs.average()
        }
        fun covered(v: List<Double?>) = v.count { it != null } >= v.size * COVERAGE

        // Every year with enough data over the compared days, for ranks and decades.
        val firstYear = obs.minOfOrNull { it.date.year } ?: start.year
        val lastYear = obs.maxOfOrNull { it.date.year } ?: start.year
        // Later years count too when looking back at an old period (a negative "back" is a later year).
        val allYears = (min(0, start.year - lastYear)..(start.year - firstYear)).mapNotNull { back ->
            val s = startOf(back)
            val v = dailies(s, days)
            if (covered(v)) Triple(back, s, summary(v)) else null
        }
        val ranked = allYears.mapNotNull { (back, _, value) -> value?.let { back to it } }.sortedByDescending { it.second }
        val rankByBack = ranked.mapIndexed { i, (back, _) -> back to i + 1 }.toMap()

        // 1991–2020 band.
        val normalStarts = (min(0, start.year - lastYear)..(start.year - firstYear)).map { startOf(it) }.filter { it.year in 1991..2020 }
        val normalLines = normalStarts.map { line(it, length) }.filter { covered(it) }
        val band = if (normalLines.size >= 20) {
            val mean = ArrayList<Double?>(length)
            val p10 = ArrayList<Double?>(length)
            val p90 = ArrayList<Double?>(length)
            for (i in 0 until length) {
                // Daily values are noisy, so pool a week either side; running totals are smooth already.
                val pool = if (measure.cumulative) normalLines.mapNotNull { it[i] }
                else normalLines.flatMap { l -> (max(0, i - 3)..min(length - 1, i + 3)).mapNotNull { l[it] } }
                mean += pool.takeIf { it.isNotEmpty() }?.average()
                p10 += quantile(pool, 0.1)
                p90 += quantile(pool, 0.9)
            }
            ExploreBand(mean, p10, p90, normalLines.size)
        } else null
        val normal = band?.let { b ->
            val window = normalLines.map { it.take(days) }
            val per = window.mapNotNull { l -> if (measure.cumulative) l.lastOrNull { it != null } else l.filterNotNull().takeIf { it.isNotEmpty() }?.average() }
            per.takeIf { it.isNotEmpty() }?.average()
        }

        val series = (0..chartYears).mapNotNull { back ->
            val s = startOf(back)
            val n = if (back == 0) days else ChronoUnit.DAYS.between(s, span.end(s)).toInt() + 1
            val v = line(s, n)
            if (v.all { it == null }) null else ExploreSeries(yearLabel(span, s), s, v)
        }

        val rows = (0..previousYears).mapNotNull { back ->
            val s = startOf(back)
            val v = dailies(s, days)
            val xs = v.withIndex().filter { it.value != null }
            if (xs.isEmpty()) return@mapNotNull null
            val pick = if (measure == ExploreMeasure.LOW) xs.minBy { it.value!! } else xs.maxBy { it.value!! }
            val value = summary(v)
            ExploreRow(
                label = yearLabel(span, s), start = s, days = xs.size, value = value,
                vsNormal = if (value != null && normal != null) value - normal else null,
                extreme = pick.value, extremeDate = s.plusDays(pick.index.toLong()),
                count = xs.count { measure.countTest(it.value!!) },
                rank = rankByBack[back], rankOf = ranked.size,
            )
        }

        val decades = allYears.filter { it.third != null }
            .groupBy { (it.second.year / 10) * 10 }
            .toSortedMap()
            .map { (d, ys) -> ExploreDecade(d, ys.size, ys.mapNotNull { it.third }.average()) }

        return ExploreView(measure, span, start, end, days, series, band, rows, normal, decades)
    }

    /** "3rd wettest" or "2nd driest", whichever end is nearer. */
    fun rankText(row: ExploreRow, measure: ExploreMeasure): String? {
        val r = row.rank ?: return null
        if (row.rankOf < 3) return null
        val fromBottom = row.rankOf - r + 1
        return if (r <= fromBottom) "${ordinal(r)} ${measure.high}" else "${ordinal(fromBottom)} ${measure.low}"
    }

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    /** The table and chart lines on screen, as CSV. */
    fun csv(view: ExploreView): String = buildString {
        val m = view.measure
        val kind = if (m.cumulative) "total" else "mean"
        appendLine("# ${m.label} (${m.unit}), ${view.span.label.lowercase()} from ${view.start} to ${view.end}, first ${view.days} days compared")
        appendLine("year,period_start,days,${kind}_${unitKey(m)},vs_normal,extreme,extreme_date,${m.countLabel.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')},rank,rank_of")
        view.rows.forEach { r ->
            appendLine(listOf(r.label, r.start, r.days, n(r.value), n(r.vsNormal), n(r.extreme), r.extremeDate ?: "", r.count ?: "", r.rank ?: "", r.rankOf).joinToString(","))
        }
        view.normal?.let { appendLine("1991-2020,,,${n(it)},,,,,,") }
        appendLine()
        val header = mutableListOf("day")
        view.series.forEach { header += "y${it.label}" }
        if (view.band != null) header += listOf("normal_mean", "normal_p10", "normal_p90")
        appendLine(header.joinToString(","))
        for (i in 0 until view.periodLength) {
            val row = mutableListOf((i + 1).toString())
            view.series.forEach { row += n(it.values.getOrNull(i)) }
            view.band?.let { b -> row += listOf(n(b.mean[i]), n(b.p10[i]), n(b.p90[i])) }
            appendLine(row.joinToString(","))
        }
    }

    /** Every stored day in the period for the years in the table, all measures. */
    fun rawCsv(obs: List<DailyObs>, view: ExploreView): String = buildString {
        appendLine("date,temperature_max,temperature_min,precipitation_sum,sunshine_hours,wind_gusts_max")
        val ranges = view.rows.map { it.start to view.span.end(it.start) }
        obs.filter { o -> ranges.any { (a, b) -> !o.date.isBefore(a) && !o.date.isAfter(b) } }
            .sortedBy { it.date }
            .forEach { o -> appendLine(listOf(o.date, n(o.tempMax), n(o.tempMin), n(o.precipitation), n(o.sunshineHours), n(o.gustMax)).joinToString(",")) }
    }

    private fun unitKey(m: ExploreMeasure) = when (m.unit) { "°" -> "c"; "km/h" -> "kmh"; else -> m.unit }
    private fun n(v: Double?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: ""

    private fun quantile(xs: List<Double>, q: Double): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val pos = q * (s.size - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }
}
