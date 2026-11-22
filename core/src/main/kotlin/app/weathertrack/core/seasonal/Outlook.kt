package app.weathertrack.core.seasonal

import app.weathertrack.core.history.DailyObs
import app.weathertrack.core.history.Season
import app.weathertrack.core.history.Seasons
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.time.YearMonth
import kotlin.math.abs

/** One month of the ECMWF seasonal forecast, as a shift from the model's own climate. */
data class ModelMonth(
    val month: YearMonth,
    /** °C warmer (+) or cooler (−) than the model's normal for the month. */
    val tempAnomaly: Double?,
    /** Forecast rain as a share of the model's normal (1.0 = normal). */
    val rainRatio: Double?,
)

/** What one calendar month has done at this place in El Niño or La Niña years, against its normal. */
data class EnsoEffect(val phase: EnsoPhase, val years: Int, val tempShift: Double?, val rainRatio: Double?)

data class OutlookMonth(
    val month: YearMonth,
    val season: Season,
    val normalRain: Double?,
    val normalTemp: Double?,
    /** From the ECMWF seasonal model, or null beyond its range. */
    val model: ModelMonth?,
    val elNino: EnsoEffect?,
    val laNina: EnsoEffect?,
) {
    val rain: Double? get() = model?.rainRatio?.let { r -> normalRain?.times(r) }
    val temp: Double? get() = model?.tempAnomaly?.let { a -> normalTemp?.plus(a) }
    fun effect(phase: EnsoPhase): EnsoEffect? = when (phase) {
        EnsoPhase.EL_NINO -> elNino
        EnsoPhase.LA_NINA -> laNina
        EnsoPhase.NEUTRAL -> null
    }
}

data class OutlookView(
    val months: List<OutlookMonth>,
    val enso: EnsoNow?,
    /** How many months the seasonal model covers. */
    val modelMonths: Int,
)

object Outlook {
    const val SEASONAL_BASE = "https://seasonal-api.open-meteo.com/v1/seasonal"
    val MONTHLY_VARS = listOf("temperature_2m_mean", "temperature_2m_anomaly", "precipitation_mean", "precipitation_anomaly")
    /** A month's El Niño or La Niña effect needs at least this many past years. */
    const val MIN_YEARS = 3

    private val json = Json { ignoreUnknownKeys = true }

    fun url(latitude: Double, longitude: Double): String =
        "$SEASONAL_BASE?latitude=${"%.4f".format(java.util.Locale.US, latitude)}" +
            "&longitude=${"%.4f".format(java.util.Locale.US, longitude)}" +
            "&monthly=${MONTHLY_VARS.joinToString(",")}&timezone=auto"

    /**
     * Reads the monthly block of an Open-Meteo seasonal response. Accepts a single series per
     * variable or one per ensemble member (`name_memberNN`), which are averaged.
     */
    fun parseSeasonal(body: String): List<ModelMonth> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val m = root["monthly"] as? JsonObject ?: return emptyList()
        val times = (m["time"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull } ?: return emptyList()
        fun series(name: String): List<Double?> {
            val keys = m.keys.filter { it == name || it.startsWith("${name}_member") }
            val cols = keys.mapNotNull { k -> (m[k] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } }
            return times.indices.map { i -> cols.mapNotNull { it.getOrNull(i) }.takeIf { it.isNotEmpty() }?.average() }
        }
        val tAnom = series("temperature_2m_anomaly")
        val pMean = series("precipitation_mean")
        val pAnom = series("precipitation_anomaly")
        return times.mapIndexedNotNull { i, t ->
            val ym = t?.takeIf { it.length >= 7 }?.let { runCatching { YearMonth.parse(it.substring(0, 7)) }.getOrNull() }
                ?: return@mapIndexedNotNull null
            val mean = pMean.getOrNull(i)
            val anom = pAnom.getOrNull(i)
            val clim = if (mean != null && anom != null) mean - anom else null
            val ratio = if (mean != null && clim != null && clim > 0.01) (mean / clim).coerceIn(0.2, 4.0) else null
            ModelMonth(ym, tAnom.getOrNull(i), ratio)
        }.filter { it.tempAnomaly != null || it.rainRatio != null }
    }

    /**
     * The 12 months starting [first]: normals from [obs], the seasonal model where it reaches, and
     * what El Niño and La Niña years have done here in each calendar month.
     */
    fun build(
        obs: List<DailyObs>,
        first: YearMonth,
        southern: Boolean,
        model: List<ModelMonth>,
        oni: List<OniMonth>,
    ): OutlookView {
        val past = Seasons.view(obs, first.minusMonths(1), southern)
        val normals = past.months.associate { it.month.monthValue to (it.normalRain to it.normalTemp) }
        val sums = obs.groupBy { YearMonth.from(it.date) }.mapValues { Seasons.sum(it.value) }
            .filterValues { it.days >= Seasons.MIN_DAYS }
        val phaseOf = oni.associate { it.month to it.phase }
        fun effect(monthValue: Int, phase: EnsoPhase): EnsoEffect? {
            val (nRain, nTemp) = normals[monthValue] ?: return null
            val hits = sums.filter { (ym, _) -> ym.monthValue == monthValue && phaseOf[ym] == phase }.values
            if (hits.size < MIN_YEARS) return null
            val tShift = nTemp?.let { n -> hits.mapNotNull { it.temp }.takeIf { it.size >= MIN_YEARS }?.average()?.minus(n) }
            val rRatio = nRain?.takeIf { it > 0.1 }?.let { n -> hits.mapNotNull { it.rain }.takeIf { it.size >= MIN_YEARS }?.average()?.div(n) }
            return EnsoEffect(phase, hits.size, tShift, rRatio)
        }
        val byMonth = model.associateBy { it.month }
        val months = (0 until 12).map { k ->
            val ym = first.plusMonths(k.toLong())
            val n = normals[ym.monthValue]
            OutlookMonth(
                ym, Seasons.of(ym.month, southern), n?.first, n?.second, byMonth[ym],
                effect(ym.monthValue, EnsoPhase.EL_NINO), effect(ym.monthValue, EnsoPhase.LA_NINA),
            )
        }
        return OutlookView(months, Enso.now(oni), months.count { it.model != null })
    }

    /** "+0.6°" style shift, or null when too small to mention. */
    fun shift(d: Double?): String? = d?.takeIf { abs(it) >= 0.05 }?.let { "${if (it > 0) "+" else "−"}${"%.1f".format(java.util.Locale.US, abs(it))}°" }
}
