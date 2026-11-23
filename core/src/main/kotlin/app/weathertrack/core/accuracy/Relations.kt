package app.weathertrack.core.accuracy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One link between two things in what actually happened here, e.g. "more low cloud by day, lower high".
 * [slope] is the change in the outcome per [per] of the driver, after taking the season out of both.
 */
data class Relation(
    val key: String,
    val driver: String,
    val outcome: String,
    val slope: Double,
    val per: Double,
    val driverUnit: String,
    val outcomeUnit: String,
    /** Correlation, -1..1. */
    val r: Double,
    val days: Int,
    /** Each day as (driver, outcome), both relative to the season, for a scatter chart. */
    val points: List<Pair<Double, Double>> = emptyList(),
) {
    val strength: String get() = when (abs(r)) {
        in 0.0..0.2 -> "no clear link"
        in 0.2..0.4 -> "weak"
        in 0.4..0.6 -> "moderate"
        else -> "strong"
    }

    fun sentence(): String {
        if (abs(r) < 0.2) return "$driver and $outcome: no clear link here ($days days)."
        val change = slope * per
        val dir = if (change >= 0) "higher" else "lower"
        val perText = if (per == 1.0) "1$driverUnit" else "${fmt(per)}$driverUnit"
        return "Each $perText more $driver: $outcome about ${fmt(abs(change))}$outcomeUnit $dir ($strength link, r ${String.format(java.util.Locale.US, "%+.2f", r)}, $days days)."
    }

    private fun fmt(v: Double) = if (abs(v - v.roundToInt()) < 0.05) "${v.roundToInt()}" else String.format(java.util.Locale.US, "%.1f", v)
}

/** One local day of actuals, split into daytime (10 am to 4 pm) and the night after (9 pm to 6 am). */
data class DayRecord(
    val date: LocalDate,
    val high: Double?,
    /** Lowest from 9 pm to 6 am the next morning. */
    val nightLow: Double?,
    val dayCloud: Double?,
    val dayLowCloud: Double?,
    val nightCloud: Double?,
    val dayWind: Double?,
    val nightWind: Double?,
    /** Sunshine energy 10 am to 4 pm, kWh/m². */
    val sun: Double?,
    val rain: Double,
)

/** Relationships between weather factors in the actuals stored for a place. */
object Relations {
    const val MIN_DAYS = 20
    /** Days either side for the seasonal mean that each value is compared with. */
    const val SEASON_HALF = 7

    fun days(obs: List<ObsHour>, zone: ZoneId): List<DayRecord> {
        val byDay = obs.groupBy { Instant.ofEpochSecond(it.time).atZone(zone).toLocalDate() }
        fun hour(o: ObsHour) = Instant.ofEpochSecond(o.time).atZone(zone).hour
        return byDay.keys.sorted().mapNotNull { d ->
            val day = byDay.getValue(d).filter { hour(it) in 10..16 }
            if (day.size < 5) return@mapNotNull null
            val night = byDay.getValue(d).filter { hour(it) >= 21 } + byDay[d.plusDays(1)].orEmpty().filter { hour(it) <= 6 }
            fun avg(l: List<Double>) = l.takeIf { it.size >= 3 }?.average()
            DayRecord(
                d,
                byDay.getValue(d).mapNotNull { it.temperature }.maxOrNull(),
                night.mapNotNull { it.temperature }.takeIf { it.size >= 6 }?.minOrNull(),
                avg(day.mapNotNull { it.cloudCover }),
                avg(day.mapNotNull { it.cloudLow }),
                avg(night.mapNotNull { it.cloudCover }),
                avg(day.mapNotNull { it.windSpeed }),
                avg(night.mapNotNull { it.windSpeed }),
                day.mapNotNull { it.radiation }.takeIf { it.size >= 5 }?.sum()?.div(1000),
                byDay.getValue(d).sumOf { it.precipitation ?: 0.0 },
            )
        }
    }

    /** Each value minus the mean of the same value over the surrounding [SEASON_HALF] days. */
    private fun anomalies(days: List<DayRecord>, pick: (DayRecord) -> Double?): Map<LocalDate, Double> {
        val vals = days.mapNotNull { d -> pick(d)?.let { d.date to it } }.toMap()
        return vals.mapNotNull { (date, v) ->
            val around = (-SEASON_HALF..SEASON_HALF).mapNotNull { vals[date.plusDays(it.toLong())] }
            if (around.size < SEASON_HALF) null else date to v - around.average()
        }.toMap()
    }

    private data class Fit(val slope: Double, val r: Double, val points: List<Pair<Double, Double>>)

    private fun fit(x: Map<LocalDate, Double>, y: Map<LocalDate, Double>): Fit? {
        val pairs = x.keys.intersect(y.keys).map { x.getValue(it) to y.getValue(it) }
        if (pairs.size < MIN_DAYS) return null
        val mx = pairs.sumOf { it.first } / pairs.size
        val my = pairs.sumOf { it.second } / pairs.size
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for ((a, b) in pairs) { sxy += (a - mx) * (b - my); sxx += (a - mx) * (a - mx); syy += (b - my) * (b - my) }
        if (sxx <= 1e-9 || syy <= 1e-9) return null
        return Fit(sxy / sxx, sxy / sqrt(sxx * syy), pairs)
    }

    /**
     * The links worth knowing for heating, cooling and walks, from [obs] since [from]. The season is
     * taken out of both sides so "summer is sunnier and warmer" doesn't count as a link.
     */
    fun all(obs: List<ObsHour>, zone: ZoneId, from: LocalDate = LocalDate.MIN): List<Relation> {
        val all = days(obs, zone)
        val keep = all.filter { !it.date.isBefore(from) }.map { it.date }.toSet()
        // Anomalies use the full record so the window edges still have a season to compare with.
        fun an(pick: (DayRecord) -> Double?) = anomalies(all, pick).filterKeys { it in keep }
        val high = an { it.high }
        val low = an { it.nightLow }
        data class Spec(val key: String, val driver: String, val outcome: String, val x: Map<LocalDate, Double>, val y: Map<LocalDate, Double>,
                        val per: Double, val du: String, val ou: String = "°")
        val specs = listOf(
            Spec("cloud_high", "cloud by day", "the high", an { it.dayCloud }, high, 10.0, "%"),
            Spec("lowcloud_high", "low cloud by day", "the high", an { it.dayLowCloud }, high, 10.0, "%"),
            Spec("sun_high", "sunshine by day", "the high", an { it.sun }, high, 1.0, " kWh/m²"),
            Spec("wind_high", "wind by day", "the high", an { it.dayWind }, high, 5.0, " km/h"),
            Spec("rain_high", "rain in the day", "the high", an { it.rain }, high, 5.0, " mm"),
            Spec("cloud_low", "cloud overnight", "the overnight low", an { it.nightCloud }, low, 10.0, "%"),
            Spec("wind_low", "wind overnight", "the overnight low", an { it.nightWind }, low, 5.0, " km/h"),
        )
        return specs.mapNotNull { s ->
            fit(s.x, s.y)?.let { f -> Relation(s.key, s.driver, s.outcome, f.slope, s.per, s.du, s.ou, f.r, f.points.size, f.points) }
        }
    }
}
