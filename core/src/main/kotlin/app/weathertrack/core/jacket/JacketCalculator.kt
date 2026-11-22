package app.weathertrack.core.jacket

import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.model.QuarterHourPoint

enum class Verdict { NO, MAYBE, YES }

enum class ReasonKind { RAIN, WIND, COLD }

data class Reason(val kind: ReasonKind, val level: Verdict, val text: String)

/**
 * Thresholds for the jacket call. A reason fires at YES when its value reaches the "yes"
 * threshold and at MAYBE when it reaches the "maybe" threshold.
 */
data class JacketThresholds(
    val rainProbabilityYes: Double = 50.0,
    val rainProbabilityMaybe: Double = 25.0,
    val rainTotalMmYes: Double = 0.5,
    val rainTotalMmMaybe: Double = 0.1,
    val gustKmhYes: Double = 45.0,
    val gustKmhMaybe: Double = 35.0,
    val feelsLikeYes: Double = 11.0,
    val feelsLikeMaybe: Double = 14.0,
)

data class JacketResult(
    val verdict: Verdict,
    val reasons: List<Reason>,
    val windowHours: Int,
    val windowStart: Long,
    val windowEnd: Long,
    val hoursConsidered: Int,
    val maxRainProbability: Double?,
    val totalRainMm: Double?,
    val maxGustKmh: Double?,
    val minFeelsLike: Double?,
    /** Epoch seconds when the 15-minute nowcast first shows rain, if within the window. */
    val rainStartsAt: Long?,
    /** Epoch seconds when that rain is next shown to stop, if the nowcast reaches that far. */
    val rainEndsAt: Long? = null,
) {
    /** True when the rain has already started (the first wet 15-minute step began at or before now). */
    val rainingNow: Boolean get() = rainStartsAt != null && rainStartsAt <= windowStart
}

object JacketCalculator {
    /** Minimum rain in a 15-minute step to count as "rain starting". */
    const val NOWCAST_RAIN_MM = 0.1

    /**
     * Evaluates the next [windowHours] hours from [nowEpochSeconds].
     * The current hour (the step that started before now) is included.
     */
    fun evaluate(
        hours: List<HourPoint>,
        nowEpochSeconds: Long,
        windowHours: Int,
        quarterHours: List<QuarterHourPoint> = emptyList(),
        thresholds: JacketThresholds = JacketThresholds(),
    ): JacketResult {
        val windowEnd = nowEpochSeconds + windowHours * 3600L
        val window = hours.filter { it.time + 3600 > nowEpochSeconds && it.time < windowEnd }

        val maxProb = window.mapNotNull { it.precipitationProbability }.maxOrNull()
        val totalRain = window.mapNotNull { it.precipitation }.takeIf { it.isNotEmpty() }?.sum()
        val maxGust = window.mapNotNull { it.windGusts }.maxOrNull()
        val minFeels = window.mapNotNull { it.apparentTemperature }.minOrNull()
        val upcoming = quarterHours.filter { it.time + 900 > nowEpochSeconds }.sortedBy { it.time }
        val rainStart = upcoming
            .filter { it.time < windowEnd }
            .firstOrNull { (it.precipitation ?: 0.0) >= NOWCAST_RAIN_MM }
            ?.time
        val rainEnd = rainStart?.let { start ->
            upcoming.firstOrNull { it.time > start && (it.precipitation ?: 0.0) < NOWCAST_RAIN_MM }?.time
        }

        val t = thresholds
        val reasons = buildList {
            val rainLevel = maxOf(
                level(maxProb, t.rainProbabilityYes, t.rainProbabilityMaybe),
                level(totalRain, t.rainTotalMmYes, t.rainTotalMmMaybe),
            )
            if (rainLevel != Verdict.NO) {
                add(Reason(ReasonKind.RAIN, rainLevel, rainText(maxProb, totalRain)))
            }
            val windLevel = level(maxGust, t.gustKmhYes, t.gustKmhMaybe)
            if (windLevel != Verdict.NO) {
                add(Reason(ReasonKind.WIND, windLevel, "Gusts to ${maxGust!!.toInt()} km/h"))
            }
            val coldLevel = levelBelow(minFeels, t.feelsLikeYes, t.feelsLikeMaybe)
            if (coldLevel != Verdict.NO) {
                add(Reason(ReasonKind.COLD, coldLevel, "Feels like ${Math.round(minFeels!!)}°"))
            }
        }

        return JacketResult(
            verdict = reasons.maxOfOrNull { it.level } ?: Verdict.NO,
            reasons = reasons.sortedByDescending { it.level },
            windowHours = windowHours,
            windowStart = nowEpochSeconds,
            windowEnd = windowEnd,
            hoursConsidered = window.size,
            maxRainProbability = maxProb,
            totalRainMm = totalRain,
            maxGustKmh = maxGust,
            minFeelsLike = minFeels,
            rainStartsAt = rainStart,
            rainEndsAt = rainEnd,
        )
    }

    private fun rainText(prob: Double?, total: Double?): String {
        val parts = mutableListOf<String>()
        if (prob != null) parts += "${prob.toInt()}% chance of rain"
        if (total != null && total > 0) parts += String.format(java.util.Locale.US, "%.1f mm", total)
        return parts.joinToString(", ").ifEmpty { "Rain possible" }
    }

    private fun level(value: Double?, yes: Double, maybe: Double): Verdict = when {
        value == null -> Verdict.NO
        value >= yes -> Verdict.YES
        value >= maybe -> Verdict.MAYBE
        else -> Verdict.NO
    }

    private fun levelBelow(value: Double?, yes: Double, maybe: Double): Verdict = when {
        value == null -> Verdict.NO
        value <= yes -> Verdict.YES
        value <= maybe -> Verdict.MAYBE
        else -> Verdict.NO
    }
}
