package app.weathertrack.core.sources

import app.weathertrack.core.accuracy.ObsHour
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a forecast is checked on for "settled". */
enum class Variable(val label: String) { RAIN("Rain or dry"), TEMP("Temperature"), WIND("Wind"), CLOUD("Cloud") }

/**
 * How far ahead one source's forecasts for [variable] got it right and then stayed right, over [hours]
 * past hours: the median, and the share already settled 3, 6, 12 and 24 hours out.
 */
data class Settled(
    val source: String,
    val variable: Variable,
    val hours: Int,
    val medianLeadH: Double,
    /** Share of hours right and staying right from 3, 6, 12 and 24 hours out. */
    val by3: Double,
    val by6: Double,
    val by12: Double,
    val by24: Double,
)

/** A forecast hour that changed a lot since an earlier run. */
data class ForecastChange(val time: Long, val kind: Kind, val from: Double, val to: Double, val sinceIssued: Long) {
    enum class Kind { RAIN, TEMP }

    fun text(zone: ZoneId): String {
        val at = hourText(time, zone)
        val since = hourText(sinceIssued, zone)
        return when (kind) {
            Kind.RAIN -> "Rain at $at ${if (to > from) "up" else "down"} from ${from.roundToInt()}% to ${to.roundToInt()}% since $since"
            Kind.TEMP -> "$at now ${to.roundToInt()}°, was ${from.roundToInt()}° at $since"
        }
    }

    private fun hourText(t: Long, zone: ZoneId) =
        Instant.ofEpochSecond(t).atZone(zone).format(DateTimeFormatter.ofPattern("h a", Locale.ENGLISH)).lowercase()
}

/** How forecasts change from run to run as an hour gets closer. */
object Drift {
    /** Within this many degrees, km/h or cloud points a forecast counts as right. */
    const val TEMP_OK = 2.0
    const val WIND_OK = 6.0
    const val CLOUD_OK = 25.0
    /** Only hours with runs from at least this far out are counted, so "settled at 24 h" is possible. */
    const val NEED_LEAD_H = 24

    private fun right(v: Variable, f: IssuedSourceHour, a: ObsHour): Boolean? = when (v) {
        Variable.TEMP -> if (f.temperature != null && a.temperature != null) abs(f.temperature - a.temperature) <= TEMP_OK else null
        Variable.WIND -> if (f.windSpeed != null && a.windSpeed != null) abs(f.windSpeed - a.windSpeed) <= WIND_OK else null
        Variable.CLOUD -> if (f.cloudCover != null && a.cloudCover != null) abs(f.cloudCover - a.cloudCover) <= CLOUD_OK else null
        Variable.RAIN -> {
            val wet = a.precipitation?.let { it >= Sources.WET_MM }
            val said = f.precipitation?.let { it >= Sources.WET_MM } ?: f.rainChance?.let { it >= 50 }
            if (wet != null && said != null) wet == said else null
        }
    }

    /**
     * For each past hour, the earliest lead from which every later run was right ("settled"); 0 if even
     * the last run before the hour was wrong. Hours without runs from [NEED_LEAD_H] out are skipped.
     */
    fun settled(runs: List<IssuedSourceHour>, obs: List<ObsHour>): List<Settled> {
        val actual = obs.associateBy { it.time }
        return runs.filter { it.time > it.issuedAt + 1800 }.groupBy { it.source }.flatMap { (src, rows) ->
            val byHour = rows.groupBy { it.time }
            Variable.entries.mapNotNull { v ->
                val leads = byHour.mapNotNull { (time, rs) ->
                    val a = actual[time] ?: return@mapNotNull null
                    // Latest run first; leads in whole hours.
                    val checked = rs.sortedByDescending { it.issuedAt }.mapNotNull { r -> right(v, r, a)?.let { ((time - r.issuedAt) / 3600.0) to it } }
                    if (checked.isEmpty() || checked.last().first < NEED_LEAD_H) return@mapNotNull null
                    val firstWrong = checked.indexOfFirst { !it.second }
                    when (firstWrong) {
                        -1 -> checked.last().first
                        0 -> 0.0
                        else -> checked[firstWrong - 1].first
                    }
                }
                if (leads.size < 6) return@mapNotNull null
                val sorted = leads.sorted()
                val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
                fun share(h: Int) = leads.count { it >= h - 0.5 }.toDouble() / leads.size
                Settled(src, v, leads.size, median, share(3), share(6), share(12), share(24))
            }
        }
    }

    /**
     * Hours in the next [aheadH] where the latest run differs a lot from the run in force at [since]:
     * rain chance by [rainPts] points or more (and one side at least 40%), or temperature by [tempDeg]°.
     * Biggest changes first, at most [max].
     */
    fun changes(
        runs: List<IssuedSourceHour>, now: Long, since: Long, aheadH: Int = 18,
        rainPts: Double = 30.0, tempDeg: Double = 3.0, max: Int = 3,
    ): List<ForecastChange> {
        val ahead = runs.filter { it.time > now && it.time <= now + aheadH * 3600L }
        val latestIssue = ahead.maxOfOrNull { it.issuedAt } ?: return emptyList()
        val earlier = ahead.filter { it.issuedAt <= since }
        val baseIssue = earlier.maxOfOrNull { it.issuedAt } ?: return emptyList()
        if (latestIssue - baseIssue < 3600) return emptyList()
        val base = earlier.filter { it.issuedAt == baseIssue }.associateBy { it.time }
        val out = mutableListOf<Pair<Double, ForecastChange>>()
        ahead.filter { it.issuedAt == latestIssue }.forEach { n ->
            val b = base[n.time] ?: return@forEach
            if (n.rainChance != null && b.rainChance != null) {
                val d = n.rainChance - b.rainChance
                if (abs(d) >= rainPts && maxOf(n.rainChance, b.rainChance) >= 40)
                    out += abs(d) / rainPts to ForecastChange(n.time, ForecastChange.Kind.RAIN, b.rainChance, n.rainChance, baseIssue)
            }
            if (n.temperature != null && b.temperature != null) {
                val d = n.temperature - b.temperature
                if (abs(d) >= tempDeg) out += abs(d) / tempDeg to ForecastChange(n.time, ForecastChange.Kind.TEMP, b.temperature, n.temperature, baseIssue)
            }
        }
        // One line per kind for neighbouring hours: keep the biggest change, then at least 3 hours apart.
        val kept = mutableListOf<ForecastChange>()
        out.sortedByDescending { it.first }.map { it.second }.forEach { c ->
            if (kept.none { it.kind == c.kind && abs(it.time - c.time) < 3 * 3600 }) kept += c
        }
        return kept.take(max).sortedBy { it.time }
    }

    /**
     * The run to compare with on Today: the morning's (6 am) once it's at least 2 hours old, otherwise
     * the one from 3 hours ago.
     */
    fun baseline(now: Long, zone: ZoneId): Long {
        val morning = Instant.ofEpochSecond(now).atZone(zone).toLocalDate().atTime(6, 0).atZone(zone).toEpochSecond()
        return if (now - morning >= 2 * 3600) morning else now - 3 * 3600
    }
}
