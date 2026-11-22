package app.weathertrack.core.accuracy

import app.weathertrack.core.cloud.Sky
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** What kind of cloud a day had by day (09:00 to 17:00), which changes how far off temperatures run. */
enum class CloudRegime(val label: String) {
    LOW_CLOUD("Low cloud days"), CLEAR("Clear days"), MIXED("Mixed days");
}

/** Hourly measures checked against what happened, beyond temperature and rain. */
enum class Measure(val label: String, val unit: String) {
    WIND("Wind speed", "km/h"),
    GUSTS("Gusts", "km/h"),
    DIRECTION("Wind direction", "°"),
    CLOUD("Cloud cover", "%"),
    LOW_CLOUD("Low cloud", "%"),
}

/** How often "sunny or not" (low + mid cloud) was right, per lead, over daylight hours. */
data class SunScore(val lead: Int, val right: Int, val total: Int, val sunnyForecast: Int, val sunnyActual: Int) {
    val share: Double? get() = if (total == 0) null else right.toDouble() / total
}

object WindCloud {
    /** Wind direction is only judged when both forecast and actual wind are at least this strong (km/h). */
    const val DIRECTION_MIN_KMH = 5.0
    /** Daylight hours, for the sunny check: enough sun reaching the ground to tell. */
    const val DAYLIGHT_WM2 = 20.0
    /** Day hours used to call a day's cloud regime. */
    val DAY_HOURS = 9..17
    /** A day counts as low cloud from this mean low cloud by day, and clear up to this mean total cloud. */
    const val LOW_DAY = 60.0
    const val CLEAR_DAY = 25.0

    /** Signed difference a − b in degrees, folded into −180..180. */
    fun angle(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    private fun pick(m: Measure, r: RunHour, o: ObsHour): Double? = when (m) {
        Measure.WIND -> if (r.windSpeed != null && o.windSpeed != null) o.windSpeed - r.windSpeed else null
        Measure.GUSTS -> if (r.gusts != null && o.gusts != null) o.gusts - r.gusts else null
        Measure.DIRECTION -> if (r.windDirection != null && o.windDirection != null &&
            (r.windSpeed ?: 0.0) >= DIRECTION_MIN_KMH && (o.windSpeed ?: 0.0) >= DIRECTION_MIN_KMH) angle(o.windDirection, r.windDirection) else null
        Measure.CLOUD -> if (r.cloudCover != null && o.cloudCover != null) o.cloudCover - r.cloudCover else null
        Measure.LOW_CLOUD -> if (r.cloudLow != null && o.cloudLow != null) o.cloudLow - r.cloudLow else null
    }

    /**
     * Hour-by-hour errors (actual − forecast) for each measure and lead, from [fromEpoch] on. The
     * [LeadError.days] field holds the number of hours. p10..p90 is the range 8 in 10 hours landed in.
     */
    fun errors(runs: List<RunHour>, obs: List<ObsHour>, fromEpoch: Long, leads: List<Int> = ForecastCheck.LEADS): Map<Measure, List<LeadError>> {
        val actual = obs.filter { it.time >= fromEpoch }.associateBy { it.time }
        val byLead = runs.filter { it.time >= fromEpoch }.groupBy { it.lead }
        return Measure.entries.associateWith { m ->
            leads.mapNotNull { lead ->
                val errs = byLead[lead].orEmpty().mapNotNull { r -> actual[r.time]?.let { pick(m, r, it) } }
                if (errs.isEmpty()) null
                else LeadError(lead, errs.size, errs.map { abs(it) }.average(), errs.average(),
                    ForecastCheck.quantile(errs, 0.1), ForecastCheck.quantile(errs, 0.9))
            }
        }.filterValues { it.isNotEmpty() }
    }

    /** Sunny or not, per lead, over daylight hours: forecast low + mid cloud against the analysis. */
    fun sunny(runs: List<RunHour>, obs: List<ObsHour>, fromEpoch: Long, leads: List<Int> = ForecastCheck.LEADS): List<SunScore> {
        val actual = obs.filter { it.time >= fromEpoch && (it.radiation ?: 0.0) >= DAYLIGHT_WM2 }.associateBy { it.time }
        val byLead = runs.filter { it.time >= fromEpoch }.groupBy { it.lead }
        return leads.mapNotNull { lead ->
            var right = 0; var total = 0; var sf = 0; var sa = 0
            for (r in byLead[lead].orEmpty()) {
                val o = actual[r.time] ?: continue
                val f = Sky.sunny(r.cloudLow, r.cloudMid) ?: continue
                val a = Sky.sunny(o.cloudLow, o.cloudMid) ?: continue
                total++
                if (f == a) right++
                if (f) sf++
                if (a) sa++
            }
            if (total == 0) null else SunScore(lead, right, total, sf, sa)
        }
    }

    /** Calls a day's cloud regime from mean low and total cloud over [DAY_HOURS]. */
    fun regime(low: Double?, total: Double?): CloudRegime? = when {
        low != null && low >= LOW_DAY -> CloudRegime.LOW_CLOUD
        total != null && total <= CLEAR_DAY -> CloudRegime.CLEAR
        low == null && total == null -> null
        else -> CloudRegime.MIXED
    }

    /** The regime each (day, lead) was forecast to have, from Previous Runs hours. */
    fun forecastRegimes(runs: List<RunHour>, zone: ZoneId): Map<Pair<LocalDate, Int>, CloudRegime> =
        runs.filter { java.time.Instant.ofEpochSecond(it.time - 1800).atZone(zone).hour in DAY_HOURS }
            .groupBy { ForecastCheck.day(it.time, zone) to it.lead }
            .mapNotNull { (k, hours) ->
                val low = hours.mapNotNull { it.cloudLow }.takeIf { it.size >= 4 }?.average()
                val total = hours.mapNotNull { it.cloudCover }.takeIf { it.size >= 4 }?.average()
                regime(low, total)?.let { k to it }
            }.toMap()

    /** The regime of each day in a forecast, from (hour end, low cloud, total cloud). */
    fun dayRegimes(hours: List<Triple<Long, Double?, Double?>>, zone: ZoneId): Map<LocalDate, CloudRegime> =
        hours.filter { java.time.Instant.ofEpochSecond(it.first - 1800).atZone(zone).hour in DAY_HOURS }
            .groupBy { ForecastCheck.day(it.first, zone) }
            .mapNotNull { (d, h) ->
                val low = h.mapNotNull { it.second }.takeIf { it.size >= 4 }?.average()
                val total = h.mapNotNull { it.third }.takeIf { it.size >= 4 }?.average()
                regime(low, total)?.let { d to it }
            }.toMap()

    /**
     * Daily high errors by lead, grouped by the cloud regime that was forecast for the day. Low cloud
     * that hangs around longer than forecast (sea stratus) is the usual cause of a big miss on highs.
     */
    fun highErrorsByRegime(runs: List<RunHour>, obs: List<ObsHour>, zone: ZoneId, from: LocalDate,
                           leads: List<Int> = ForecastCheck.LEADS): Map<CloudRegime, List<LeadError>> {
        val actual = ForecastCheck.dailyActuals(obs, zone).filterKeys { !it.isBefore(from) }
        val fc = ForecastCheck.dailyRuns(runs, zone)
        val regimes = forecastRegimes(runs, zone)
        return CloudRegime.entries.associateWith { reg ->
            leads.mapNotNull { lead ->
                val errs = actual.mapNotNull { (d, a) ->
                    if (regimes[d to lead] != reg) null else fc[d to lead]?.let { a.high - it.first }
                }
                if (errs.isEmpty()) null
                else LeadError(lead, errs.size, errs.map { abs(it) }.average(), errs.average(),
                    ForecastCheck.quantile(errs, 0.1), ForecastCheck.quantile(errs, 0.9))
            }
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * Bands for each lead, swapped for the regime's own band when that day's forecast regime has at
     * least [Band.MIN_BAND_DAYS] checked days at that lead (or a longer one).
     */
    fun regimeBands(base: Map<Int, Band>, byRegime: Map<CloudRegime, List<LeadError>>, regimeOfLead: (Int) -> CloudRegime?): Map<Int, Band> =
        base.mapValues { (lead, band) ->
            val reg = regimeOfLead(lead) ?: return@mapValues band
            val e = ForecastCheck.errorFor(byRegime[reg].orEmpty().filter { it.days >= Band.MIN_BAND_DAYS }, lead) ?: return@mapValues band
            Band(lead, e.bias, e.p10, e.p90, e.days, reg)
        }
}
