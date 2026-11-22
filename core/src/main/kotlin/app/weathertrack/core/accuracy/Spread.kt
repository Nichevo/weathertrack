package app.weathertrack.core.accuracy

import app.weathertrack.core.openmeteo.OpenMeteoParser

/** How far apart the weather models are for one day. */
data class DaySpread(
    val date: String,
    val models: Int,
    val highMin: Double?,
    val highMax: Double?,
    val rainMin: Double?,
    val rainMax: Double?,
    /** How many models have 1 mm or more. */
    val wetModels: Int,
) {
    val highGap: Double? get() = if (highMin != null && highMax != null) highMax - highMin else null
    val highsDisagree: Boolean get() = (highGap ?: 0.0) >= HIGH_GAP
    /** Some models dry, some clearly wet. */
    val rainDisagrees: Boolean get() = models >= 2 && wetModels in 1 until models && (rainMax ?: 0.0) >= RAIN_GAP

    companion object {
        const val HIGH_GAP = 4.0
        const val RAIN_GAP = 3.0
    }
}

object Spread {
    fun of(days: List<OpenMeteoParser.ModelDay>): List<DaySpread> = days.map { d ->
        val n = maxOf(d.highs.size, d.rain.size)
        DaySpread(
            d.date, n, d.highs.values.minOrNull(), d.highs.values.maxOrNull(),
            d.rain.values.minOrNull(), d.rain.values.maxOrNull(), d.rain.values.count { it >= ForecastCheck.RAIN_DAY_MM },
        )
    }
}

object RainAdjust {
    /** Hours of history at which the record counts as much as the forecast itself. */
    const val PRIOR_HOURS = 50.0

    /**
     * The forecast rain chance pulled towards how often it actually rained here when forecasts gave
     * the same chance. With little history it stays close to the forecast.
     */
    fun adjusted(chance: Double?, reliability: List<Reliability>): Double? {
        chance ?: return null
        val cal = Feedback.calibrated(reliability, chance, minHours = 1) ?: return chance
        val (share, hours) = cal
        val w = hours / (hours + PRIOR_HOURS)
        return (chance * (1 - w) + share * 100 * w).coerceIn(0.0, 100.0)
    }
}
