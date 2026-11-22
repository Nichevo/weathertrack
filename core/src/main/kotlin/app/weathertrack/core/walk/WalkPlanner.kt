package app.weathertrack.core.walk

import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.model.WeatherCodes
import app.weathertrack.core.outdoor.Pavement
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** A regular walk, for example "Morning" from 07:00 to 09:00. Times are local to the place. */
data class WalkWindow(val name: String, val start: LocalTime, val end: LocalTime)

enum class WalkRating { GOOD, FAIR, POOR }

enum class WalkIssueKind { PET_HEAT, HOT_PAVEMENT, COLD, RAIN, THUNDER, WIND, MUGGY, UV, DARK }

data class WalkIssue(val kind: WalkIssueKind, val rating: WalkRating, val text: String)

data class WalkThresholds(
    /** Feels-like at or above this is too hot for a dog (POOR); 2° below is FAIR. */
    val petHotFeelsLike: Double = 24.0,
    val coldFeelsLike: Double = 5.0,
    val rainChancePoor: Double = 50.0,
    val rainChanceFair: Double = 25.0,
    val gustPoor: Double = 50.0,
    val gustFair: Double = 40.0,
    /** Humidity at or above this, with temperature at or above [muggyMinTemp], is muggy. */
    val muggyHumidity: Double = 80.0,
    val muggyMinTemp: Double = 18.0,
    val uvHigh: Double = 8.0,
    /** Sunny (total cloud below this %) and at least [pavementMinTemp] warns about hot paws. */
    val pavementMaxCloud: Double = 30.0,
    val pavementMinTemp: Double = 22.0,
)

data class WalkHour(val point: HourPoint, val rating: WalkRating, val issues: List<WalkIssue>)

data class WalkForecast(
    val window: WalkWindow,
    val date: LocalDate,
    /** Epoch seconds; the start is now if the walk window has already begun. */
    val start: Long,
    val end: Long,
    val rating: WalkRating,
    /** Distinct issues across the window, worst first. */
    val issues: List<WalkIssue>,
    val hours: List<WalkHour>,
    /** Start of the best hour to go, if the window has several. */
    val bestHour: Long?,
    val feelsMin: Double?,
    val feelsMax: Double?,
    val rainChanceMax: Double?,
    val humidityMin: Double?,
    val humidityMax: Double?,
    val cloudLow: Double?,
    val cloudMid: Double?,
    val cloudHigh: Double?,
)

object WalkPlanner {
    fun rateHour(h: HourPoint, t: WalkThresholds = WalkThresholds()): WalkHour {
        val issues = buildList {
            h.apparentTemperature?.let { f ->
                when {
                    f >= t.petHotFeelsLike -> add(WalkIssue(WalkIssueKind.PET_HEAT, WalkRating.POOR, "Too hot for the pup (feels ${f.roundToInt()}°)"))
                    f >= t.petHotFeelsLike - 2 -> add(WalkIssue(WalkIssueKind.PET_HEAT, WalkRating.FAIR, "Warm for the pup (feels ${f.roundToInt()}°)"))
                    f <= t.coldFeelsLike -> add(WalkIssue(WalkIssueKind.COLD, WalkRating.FAIR, "Cold (feels ${f.roundToInt()}°)"))
                }
            }
            val pave = Pavement.estimate(h.temperature, h.shortwaveRadiation, h.windSpeed)
            if (pave != null) {
                when {
                    pave >= Pavement.TOO_HOT -> add(WalkIssue(WalkIssueKind.HOT_PAVEMENT, WalkRating.POOR, "Pavement about ${pave.roundToInt()}°: too hot for paws"))
                    pave >= Pavement.WARM -> add(WalkIssue(WalkIssueKind.HOT_PAVEMENT, WalkRating.FAIR, "Pavement about ${pave.roundToInt()}°: test it with your hand"))
                }
            } else {
                val sunny = h.cloudCover?.let { it < t.pavementMaxCloud } ?: false
                if (sunny && h.isDay != false && (h.temperature ?: -99.0) >= t.pavementMinTemp) {
                    add(WalkIssue(WalkIssueKind.HOT_PAVEMENT, WalkRating.FAIR, "Sunny: pavement may be hot for paws"))
                }
            }
            if (h.isDay == false) add(WalkIssue(WalkIssueKind.DARK, WalkRating.GOOD, "After dark: take a light"))
            if (WeatherCodes.isThunder(h.weatherCode)) {
                add(WalkIssue(WalkIssueKind.THUNDER, WalkRating.POOR, "Thunderstorms"))
            }
            val prob = h.precipitationProbability ?: 0.0
            val mm = h.precipitation ?: 0.0
            when {
                prob >= t.rainChancePoor || mm >= 0.5 -> add(WalkIssue(WalkIssueKind.RAIN, WalkRating.POOR, "Rain likely (${prob.roundToInt()}%)"))
                prob >= t.rainChanceFair || mm >= 0.1 -> add(WalkIssue(WalkIssueKind.RAIN, WalkRating.FAIR, "Chance of rain (${prob.roundToInt()}%)"))
            }
            h.windGusts?.let { g ->
                when {
                    g >= t.gustPoor -> add(WalkIssue(WalkIssueKind.WIND, WalkRating.POOR, "Strong gusts (${g.roundToInt()} km/h)"))
                    g >= t.gustFair -> add(WalkIssue(WalkIssueKind.WIND, WalkRating.FAIR, "Gusty (${g.roundToInt()} km/h)"))
                }
            }
            val hum = h.humidity
            if (hum != null && hum >= t.muggyHumidity && (h.temperature ?: -99.0) >= t.muggyMinTemp) {
                add(WalkIssue(WalkIssueKind.MUGGY, WalkRating.FAIR, "Muggy (${hum.roundToInt()}% humidity)"))
            }
            h.uvIndex?.let { uv ->
                if (uv >= t.uvHigh) add(WalkIssue(WalkIssueKind.UV, WalkRating.FAIR, "UV very high (${uv.roundToInt()})"))
            }
        }
        return WalkHour(h, issues.maxOfOrNull { it.rating } ?: WalkRating.GOOD, issues)
    }

    /** Forecasts for the next [count] walks (current one included) from [nowEpochSeconds]. */
    fun upcoming(
        hours: List<HourPoint>,
        windows: List<WalkWindow>,
        zone: ZoneId,
        nowEpochSeconds: Long,
        t: WalkThresholds = WalkThresholds(),
        count: Int = 2,
    ): List<WalkForecast> {
        val today = Instant.ofEpochSecond(nowEpochSeconds).atZone(zone).toLocalDate()
        return (0L..2L).flatMap { d ->
            val date = today.plusDays(d)
            windows.map { w ->
                val start = date.atTime(w.start).atZone(zone).toEpochSecond()
                var end = date.atTime(w.end).atZone(zone).toEpochSecond()
                if (end <= start) end += 24 * 3600 // window past midnight
                Triple(w, date, start to end)
            }
        }
            .filter { (_, _, range) -> range.second > nowEpochSeconds }
            .sortedBy { it.third.first }
            .mapNotNull { (w, date, range) -> forecast(hours, w, date, maxOf(range.first, nowEpochSeconds), range.second, t) }
            .take(count)
    }

    fun forecast(hours: List<HourPoint>, w: WalkWindow, date: LocalDate, start: Long, end: Long, t: WalkThresholds): WalkForecast? {
        val rated = hours.filter { it.time + 3600 > start && it.time < end }.map { rateHour(it, t) }
        if (rated.isEmpty()) return null
        val issues = rated.flatMap { it.issues }
            .groupBy { it.kind }
            .map { (_, list) -> list.maxBy { it.rating.ordinal * 1000 + it.text.length } }
            .sortedByDescending { it.rating }
        val best = rated.minWithOrNull(
            compareBy<WalkHour> { it.rating }
                .thenBy { it.issues.size }
                .thenBy { it.point.apparentTemperature ?: 0.0 },
        )
        val pts = rated.map { it.point }
        return WalkForecast(
            window = w,
            date = date,
            start = start,
            end = end,
            rating = rated.maxOf { it.rating },
            issues = issues,
            hours = rated,
            bestHour = best?.takeIf { rated.size > 1 && it.rating < rated.maxOf { r -> r.rating } }?.point?.time?.let { maxOf(it, start) },
            feelsMin = pts.mapNotNull { it.apparentTemperature }.minOrNull(),
            feelsMax = pts.mapNotNull { it.apparentTemperature }.maxOrNull(),
            rainChanceMax = pts.mapNotNull { it.precipitationProbability }.maxOrNull(),
            humidityMin = pts.mapNotNull { it.humidity }.minOrNull(),
            humidityMax = pts.mapNotNull { it.humidity }.maxOrNull(),
            cloudLow = pts.mapNotNull { it.cloudCoverLow }.averageOrNull(),
            cloudMid = pts.mapNotNull { it.cloudCoverMid }.averageOrNull(),
            cloudHigh = pts.mapNotNull { it.cloudCoverHigh }.averageOrNull(),
        )
    }

    private fun List<Double>.averageOrNull() = if (isEmpty()) null else average()
}
