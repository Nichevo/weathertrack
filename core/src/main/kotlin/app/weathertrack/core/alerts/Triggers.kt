package app.weathertrack.core.alerts

import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.model.QuarterHourPoint

enum class TriggerKind { TOO_HOT, TOO_COLD, RAIN_SOON }

/** A fired trigger. [at] is when it applies (epoch seconds): peak heat, coldest hour, or rain start. */
data class Trigger(val kind: TriggerKind, val at: Long, val value: Double, val text: String)

data class TriggerThresholds(
    /** Feels-like temperature at or above this is "too hot". */
    val hotFeelsLike: Double = 27.0,
    /** Feels-like temperature at or below this is "too cold". */
    val coldFeelsLike: Double = 5.0,
    /** How far ahead "raining soon" looks, in minutes. */
    val rainSoonMinutes: Int = 30,
    /** Rain in one 15-minute step that counts as raining. */
    val rainSoonMm: Double = 0.1,
    /** How far ahead the hot and cold checks look, in hours. */
    val temperatureWindowHours: Int = 6,
)

object TriggerCalculator {
    /**
     * Returns the triggers that fire now. Rain uses the 15-minute nowcast; heat and cold use the
     * hourly feels-like temperature over the next [TriggerThresholds.temperatureWindowHours].
     */
    fun evaluate(
        hours: List<HourPoint>,
        quarterHours: List<QuarterHourPoint>,
        nowEpochSeconds: Long,
        t: TriggerThresholds = TriggerThresholds(),
        formatTime: (Long) -> String = { it.toString() },
    ): List<Trigger> = buildList {
        val rainEnd = nowEpochSeconds + t.rainSoonMinutes * 60L
        quarterHours
            .filter { it.time + 900 > nowEpochSeconds && it.time <= rainEnd }
            .firstOrNull { (it.precipitation ?: 0.0) >= t.rainSoonMm }
            ?.let { q ->
                val minutes = ((q.time - nowEpochSeconds) / 60).coerceAtLeast(0)
                val text = if (minutes <= 0) "Raining now" else "Rain in about ${roundTo5(minutes)} min"
                add(Trigger(TriggerKind.RAIN_SOON, maxOf(q.time, nowEpochSeconds), q.precipitation ?: 0.0, text))
            }

        val tempEnd = nowEpochSeconds + t.temperatureWindowHours * 3600L
        val window = hours.filter { it.time + 3600 > nowEpochSeconds && it.time < tempEnd && it.apparentTemperature != null }
        window.maxByOrNull { it.apparentTemperature!! }?.takeIf { it.apparentTemperature!! >= t.hotFeelsLike }?.let { h ->
            add(Trigger(TriggerKind.TOO_HOT, h.time, h.apparentTemperature!!,
                "Too hot: feels ${Math.round(h.apparentTemperature)}° at ${formatTime(maxOf(h.time, nowEpochSeconds))}"))
        }
        window.minByOrNull { it.apparentTemperature!! }?.takeIf { it.apparentTemperature!! <= t.coldFeelsLike }?.let { h ->
            add(Trigger(TriggerKind.TOO_COLD, h.time, h.apparentTemperature!!,
                "Too cold: feels ${Math.round(h.apparentTemperature)}° at ${formatTime(maxOf(h.time, nowEpochSeconds))}"))
        }
    }

    private fun roundTo5(minutes: Long): Long = maxOf(5, ((minutes + 2) / 5) * 5)
}
