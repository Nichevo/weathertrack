package app.weathertrack.core.seasonal

import java.time.YearMonth

enum class EnsoPhase(val label: String) { EL_NINO("El Niño"), LA_NINA("La Niña"), NEUTRAL("Neutral") }

/** One Oceanic Niño Index value: the 3-month mean sea temperature anomaly centred on [month]. */
data class OniMonth(val month: YearMonth, val anomaly: Double, val phase: EnsoPhase)

/** Where ENSO stands now, from the latest ONI values. */
data class EnsoNow(
    val month: YearMonth,
    val anomaly: Double,
    val phase: EnsoPhase,
    /** True when the run has lasted the 5 overlapping seasons NOAA needs to call it an episode. */
    val official: Boolean,
    /** Change over the last 3 values: positive means warming toward El Niño. */
    val trend: Double?,
)

/**
 * Parses NOAA CPC's ONI table (https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii.txt) and labels
 * episodes the NOAA way: at least 5 overlapping seasons in a row at +0.5 or above (El Niño) or
 * -0.5 or below (La Niña).
 */
object Enso {
    const val THRESHOLD = 0.5
    const val MIN_RUN = 5
    val ONI_URL = "https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii.txt"

    private val SEASONS = listOf("DJF", "JFM", "FMA", "MAM", "AMJ", "MJJ", "JJA", "JAS", "ASO", "SON", "OND", "NDJ")

    fun parseOni(text: String): List<OniMonth> {
        val raw = text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) return@mapNotNull null
            val idx = SEASONS.indexOf(parts[0].uppercase())
            val year = parts[1].toIntOrNull()
            val anom = parts[3].toDoubleOrNull()
            if (idx < 0 || year == null || anom == null || anom < -9 || anom > 9) null
            else YearMonth.of(year, idx + 1) to anom
        }.toMap().toSortedMap()
        return label(raw.entries.map { it.key to it.value })
    }

    /** Labels each month from a chronological list of (centre month, anomaly). */
    fun label(values: List<Pair<YearMonth, Double>>): List<OniMonth> {
        val phases = Array(values.size) { EnsoPhase.NEUTRAL }
        var i = 0
        while (i < values.size) {
            val sign = signOf(values[i].second)
            if (sign == EnsoPhase.NEUTRAL) { i++; continue }
            var j = i
            while (j + 1 < values.size && signOf(values[j + 1].second) == sign &&
                values[j + 1].first == values[j].first.plusMonths(1)) j++
            if (j - i + 1 >= MIN_RUN) for (k in i..j) phases[k] = sign
            i = j + 1
        }
        return values.mapIndexed { k, (m, a) -> OniMonth(m, a, phases[k]) }
    }

    private fun signOf(a: Double) = when {
        a >= THRESHOLD -> EnsoPhase.EL_NINO
        a <= -THRESHOLD -> EnsoPhase.LA_NINA
        else -> EnsoPhase.NEUTRAL
    }

    fun now(oni: List<OniMonth>): EnsoNow? {
        val last = oni.lastOrNull() ?: return null
        val phase = signOf(last.anomaly)
        var run = 0
        for (k in oni.indices.reversed()) { if (signOf(oni[k].anomaly) == phase) run++ else break }
        val trend = if (oni.size >= 3) last.anomaly - oni[oni.size - 3].anomaly else null
        return EnsoNow(last.month, last.anomaly, phase, phase != EnsoPhase.NEUTRAL && run >= MIN_RUN, trend)
    }
}
