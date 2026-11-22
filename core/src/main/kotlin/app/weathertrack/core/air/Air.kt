package app.weathertrack.core.air

/** One hour of air quality (Open-Meteo / CAMS). Pollen is grains/m³ and only exists in Europe. */
data class AirHour(
    val time: Long,
    val europeanAqi: Double?,
    val usAqi: Double?,
    val pm25: Double?,
    val pm10: Double?,
    val ozone: Double?,
    val dust: Double?,
    val pollen: Map<String, Double>,
)

data class AirNow(
    val now: AirHour,
    /** Worst European AQI in the next 24 hours, and when. */
    val worst: AirHour?,
    /** Pollen types at a level worth mentioning now, highest first, with a word for the level. */
    val pollen: List<Pair<String, String>>,
) {
    val category: String get() = Air.category(now.europeanAqi)
}

object Air {
    val POLLEN = listOf("alder", "birch", "grass", "mugwort", "olive", "ragweed")

    /** European AQI bands: 0-20 good ... 100+ extremely poor. */
    fun category(aqi: Double?): String = when {
        aqi == null -> "Unknown"
        aqi < 20 -> "Good"
        aqi < 40 -> "Fair"
        aqi < 60 -> "Moderate"
        aqi < 80 -> "Poor"
        aqi < 100 -> "Very poor"
        else -> "Extremely poor"
    }

    /** Rough pollen levels in grains/m³ (grass and trees differ, but these suit a glance). */
    fun pollenLevel(v: Double): String? = when {
        v >= 200 -> "very high"
        v >= 50 -> "high"
        v >= 10 -> "moderate"
        v >= 1 -> "low"
        else -> null
    }

    fun now(hours: List<AirHour>, now: Long): AirNow? {
        val known = hours.filter { it.europeanAqi != null || it.pm25 != null }
        val current = known.filter { it.time <= now }.maxByOrNull { it.time } ?: known.minByOrNull { it.time } ?: return null
        val worst = hours.filter { it.time in now..(now + 24 * 3600) }.maxByOrNull { it.europeanAqi ?: -1.0 }
        val pollen = current.pollen.entries.sortedByDescending { it.value }
            .mapNotNull { (k, v) -> pollenLevel(v)?.takeIf { v >= 10 }?.let { k to it } }
        return AirNow(current, worst, pollen)
    }
}
