package app.weathertrack.core.accuracy

import app.weathertrack.core.jacket.JacketCalculator
import app.weathertrack.core.jacket.JacketThresholds
import app.weathertrack.core.jacket.Verdict
import app.weathertrack.core.model.HourPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** What happened in one hour (Open-Meteo's model analysis for the spot). */
data class ObsHour(val time: Long, val temperature: Double?, val feelsLike: Double?, val precipitation: Double?, val gusts: Double?)

/** One hour as forecast [lead] days earlier (Open-Meteo Previous Runs; lead 0 is the latest run). */
data class RunHour(val time: Long, val lead: Int, val temperature: Double?, val precipitation: Double?)

/** One hour from a forecast this app stored when it was issued. */
data class IssuedHour(
    val issuedAt: Long, val time: Long, val temperature: Double?, val feelsLike: Double?,
    val rainChance: Double?, val precipitation: Double?, val gusts: Double?,
)

data class DayActual(val high: Double, val low: Double, val rainMm: Double, val hours: Int)
data class LeadError(val lead: Int, val days: Int, val meanAbs: Double, val bias: Double, val p10: Double, val p90: Double)
data class Score(val right: Int, val total: Int) {
    val share: Double? get() = if (total == 0) null else right.toDouble() / total
}
data class JacketScore(val right: Int, val wrong: Int, val maybes: Int) {
    val decided get() = right + wrong
    val share: Double? get() = if (decided == 0) null else right.toDouble() / decided
}
data class Reliability(val label: String, val forecastMean: Double, val observedShare: Double?, val hours: Int)
enum class MissKind { WET, DRY, WARM, COLD }
data class Miss(val date: LocalDate, val kind: MissKind, val size: Double, val text: String)

/** A likely range for a forecast value: forecast + bias, with 80% of past errors inside low..high. */
data class Band(val lead: Int, val bias: Double, val low: Double, val high: Double, val days: Int) {
    val width get() = high - low
    val confidence: Confidence get() = when {
        days < MIN_BAND_DAYS -> Confidence.UNKNOWN
        width <= 2.5 -> Confidence.HIGH
        width <= 4.5 -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    companion object { const val MIN_BAND_DAYS = 10 }
}
enum class Confidence(val label: String) { HIGH("High"), MEDIUM("Medium"), LOW("Low"), UNKNOWN("Not enough data") }

object ForecastCheck {
    /** A day counts as rainy at this many mm. */
    const val RAIN_DAY_MM = 1.0
    /** An hour counts as wet at this many mm. */
    const val WET_HOUR_MM = 0.1
    val LEADS = listOf(0, 1, 2, 3, 5, 7)

    fun day(epoch: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate()

    /** Daily high, low and rain from hourly actuals; days with fewer than 20 hours are left out. */
    fun dailyActuals(obs: List<ObsHour>, zone: ZoneId): Map<LocalDate, DayActual> =
        obs.groupBy { day(it.time, zone) }.mapNotNull { (d, hours) ->
            val temps = hours.mapNotNull { it.temperature }
            if (temps.size < 20) return@mapNotNull null
            d to DayActual(temps.max(), temps.min(), hours.sumOf { it.precipitation ?: 0.0 }, temps.size)
        }.toMap()

    /** Daily forecast high and rain for each (day, lead), from Previous Runs hours. */
    fun dailyRuns(runs: List<RunHour>, zone: ZoneId): Map<Pair<LocalDate, Int>, Pair<Double, Double>> =
        runs.groupBy { day(it.time, zone) to it.lead }.mapNotNull { (key, hours) ->
            val temps = hours.mapNotNull { it.temperature }
            if (temps.size < 20) return@mapNotNull null
            key to (temps.max() to hours.sumOf { it.precipitation ?: 0.0 })
        }.toMap()

    /** How far off the daily highs were, by lead, for days on or after [from]. Error = actual − forecast. */
    fun highErrors(runs: List<RunHour>, obs: List<ObsHour>, zone: ZoneId, from: LocalDate, leads: List<Int> = LEADS): List<LeadError> {
        val actual = dailyActuals(obs, zone)
        val fc = dailyRuns(runs, zone)
        return leads.mapNotNull { lead ->
            val errs = actual.filterKeys { !it.isBefore(from) }.mapNotNull { (d, a) -> fc[d to lead]?.let { a.high - it.first } }
            if (errs.isEmpty()) null else LeadError(lead, errs.size, errs.map { abs(it) }.average(), errs.average(), quantile(errs, 0.1), quantile(errs, 0.9))
        }
    }

    /** Likely-range bands for daily highs by lead, from the same errors. */
    fun bands(errors: List<LeadError>): Map<Int, Band> =
        errors.associate { it.lead to Band(it.lead, it.bias, it.p10, it.p90, it.days) }

    /** Rain or dry, [lead] days ahead: how often the forecast got the day right. */
    fun rainCalls(runs: List<RunHour>, obs: List<ObsHour>, zone: ZoneId, from: LocalDate, lead: Int = 1): Score {
        val actual = dailyActuals(obs, zone).filterKeys { !it.isBefore(from) }
        val fc = dailyRuns(runs, zone)
        var right = 0
        var total = 0
        for ((d, a) in actual) {
            val f = fc[d to lead] ?: continue
            total++
            if ((f.second >= RAIN_DAY_MM) == (a.rainMm >= RAIN_DAY_MM)) right++
        }
        return Score(right, total)
    }

    private val buckets = listOf(Triple("0–10%", 0.0, 10.0), Triple("10–30%", 10.0, 30.0), Triple("30–50%", 30.0, 50.0),
        Triple("50–70%", 50.0, 70.0), Triple("70%+", 70.0, 100.1))

    /** When the stored forecast said X% (1 to 24 hours ahead), how often that hour was wet. */
    fun reliability(issued: List<IssuedHour>, obs: List<ObsHour>, fromEpoch: Long): List<Reliability> {
        val wet = obs.associate { it.time to ((it.precipitation ?: return@associate it.time to null) >= WET_HOUR_MM) }
        val pairs = issued.filter { it.time >= fromEpoch && it.time - it.issuedAt in 3600..86400 }
            .mapNotNull { h -> val p = h.rainChance ?: return@mapNotNull null; val w = wet[h.time] ?: return@mapNotNull null; p to w }
        return buckets.map { (label, lo, hi) ->
            val inB = pairs.filter { it.first >= lo && it.first < hi }
            Reliability(label, inB.map { it.first }.average().takeIf { inB.isNotEmpty() } ?: (lo + minOf(hi, 100.0)) / 2,
                if (inB.isEmpty()) null else inB.count { it.second }.toDouble() / inB.size, inB.size)
        }
    }

    /**
     * Each morning's jacket call (from the stored forecast issued closest to [morningHour] local, within
     * 2 hours of it), against what actually happened in the next [windowHours] hours.
     */
    fun jacket(
        issued: List<IssuedHour>, obs: List<ObsHour>, zone: ZoneId, from: LocalDate,
        thresholds: JacketThresholds, morningHour: Int = 7, windowHours: Int = 3,
    ): JacketScore {
        val obsByTime = obs.associateBy { it.time }
        var right = 0
        var wrong = 0
        var maybes = 0
        issued.groupBy { it.issuedAt }.entries
            .groupBy { day(it.key, zone) }
            .filterKeys { !it.isBefore(from) }
            .forEach { (d, pulls) ->
                val target = d.atTime(morningHour, 0).atZone(zone).toEpochSecond()
                val pull = pulls.minByOrNull { abs(it.key - target) }?.takeIf { abs(it.key - target) <= 2 * 3600 } ?: return@forEach
                val issuedAt = pull.key
                val hours = pull.value.map {
                    HourPoint(it.time, it.temperature, it.feelsLike, it.rainChance, it.precipitation, null, null, it.gusts, null, null, null, null, null)
                }
                val verdict = JacketCalculator.evaluate(hours, issuedAt, windowHours, thresholds = thresholds).verdict
                val window = obsByTime.values.filter { it.time + 3600 > issuedAt && it.time < issuedAt + windowHours * 3600L }
                if (window.size < windowHours) return@forEach
                val needed = window.sumOf { it.precipitation ?: 0.0 } >= thresholds.rainTotalMmYes ||
                    (window.mapNotNull { it.gusts }.maxOrNull() ?: 0.0) >= thresholds.gustKmhYes ||
                    (window.mapNotNull { it.feelsLike }.minOrNull() ?: 99.0) <= thresholds.feelsLikeYes
                when (verdict) {
                    Verdict.MAYBE -> maybes++
                    Verdict.YES -> if (needed) right++ else wrong++
                    Verdict.NO -> if (needed) wrong++ else right++
                }
            }
        return JacketScore(right, wrong, maybes)
    }

    /** The worst days: rain a day ahead that missed badly, and highs off by 3° or more two days ahead. */
    fun misses(runs: List<RunHour>, obs: List<ObsHour>, zone: ZoneId, from: LocalDate, count: Int = 3): List<Miss> {
        val actual = dailyActuals(obs, zone).filterKeys { !it.isBefore(from) }
        val fc = dailyRuns(runs, zone)
        val out = mutableListOf<Miss>()
        for ((d, a) in actual) {
            fc[d to 1]?.let { (_, rain) ->
                if (rain < RAIN_DAY_MM && a.rainMm >= 5) {
                    out += Miss(d, MissKind.WET, a.rainMm - rain, "Forecast ${mm(rain)} the day before. ${mm(a.rainMm)} fell.")
                }
                if (rain >= 5 && a.rainMm < 0.5) {
                    out += Miss(d, MissKind.DRY, rain - a.rainMm, "Forecast ${mm(rain)} the day before. Stayed dry.")
                }
            }
            fc[d to 2]?.let { (high, _) ->
                val err = a.high - high
                if (abs(err) >= 3) {
                    out += Miss(d, if (err > 0) MissKind.WARM else MissKind.COLD, abs(err) * 2,
                        "High forecast ${high.roundToInt()}° two days out. Reached ${String.format(java.util.Locale.US, "%.1f", a.high)}°.")
                }
            }
        }
        return out.sortedByDescending { it.size }.take(count)
    }

    private fun mm(v: Double) = if (v < 0.95) "under 1 mm" else "${v.roundToInt()} mm"

    fun quantile(values: List<Double>, q: Double): Double {
        val s = values.sorted()
        if (s.size == 1) return s[0]
        val pos = q * (s.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }
}
