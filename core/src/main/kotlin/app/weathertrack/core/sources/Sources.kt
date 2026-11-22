package app.weathertrack.core.sources

import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.accuracy.RainReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs

/** One forecast source: a weather model, an ensemble, or another provider. */
data class Source(val id: String, val label: String, val kind: Kind) {
    enum class Kind { MODEL, ENSEMBLE, PROVIDER }
}

/** One hour from one source. [rainChance] is 0..100; for single-run models it is derived from the rain amount. */
data class SourceHour(val source: String, val time: Long, val temperature: Double?, val precipitation: Double?, val rainChance: Double?)

/** A stored source hour with when it was issued, for scoring. */
data class IssuedSourceHour(val source: String, val issuedAt: Long, val time: Long, val temperature: Double?, val precipitation: Double?, val rainChance: Double?)

/** One past hour: what happened, and each source's forecast for it. */
data class PastHour(val time: Long, val temperature: Double?, val precipitation: Double?, val forecasts: Map<String, IssuedSourceHour>)

/**
 * One source over one period (a day, or a week when summed): hours checked, temperature misses and
 * rain or no rain calls. Kept as sums so periods add up exactly.
 */
data class DayScore(
    /** The day, or the Monday that starts the week. */
    val date: java.time.LocalDate,
    val source: String,
    val hours: Int,
    val tempAbsSum: Double,
    /** Forecast minus actual, summed: positive means it ran warm. */
    val tempErrSum: Double,
    val tempN: Int,
    val rainRight: Int,
    val rainN: Int,
) {
    val tempMae: Double? get() = if (tempN > 0) tempAbsSum / tempN else null
    val tempBias: Double? get() = if (tempN > 0) tempErrSum / tempN else null
    val wetDryRight: Double? get() = if (rainN > 0) rainRight.toDouble() / rainN else null

    operator fun plus(o: DayScore) = copy(hours = hours + o.hours, tempAbsSum = tempAbsSum + o.tempAbsSum,
        tempErrSum = tempErrSum + o.tempErrSum, tempN = tempN + o.tempN, rainRight = rainRight + o.rainRight, rainN = rainN + o.rainN)
}

data class SourceScore(
    val source: String,
    /** Hours compared against actuals. */
    val hours: Int,
    val tempMae: Double?,
    /** Share of hours where the source got wet or dry right. */
    val wetDryRight: Double?,
    /** Mean squared error of the rain chance (0 perfect, 0.25 is a coin toss). */
    val brier: Double?,
    /** Your Raining/Dry reports this source got right, and how many it was checked on. */
    val reportsRight: Int = 0,
    val reportsTotal: Int = 0,
)

data class SourceWeight(val source: String, val temp: Double, val rain: Double)

data class BlendHour(
    val time: Long,
    val temperature: Double?,
    val rainChance: Double?,
    /** Lowest and highest rain chance across sources for the hour. */
    val chanceLow: Double?,
    val chanceHigh: Double?,
    val sources: Int,
)

object Sources {
    /** Open-Meteo single-run models with global coverage. Unknown names fall back to [MODELS_SAFE]. */
    val MODELS = listOf(
        Source("ecmwf_ifs025", "ECMWF (Europe)", Source.Kind.MODEL),
        Source("gfs_seamless", "GFS (USA)", Source.Kind.MODEL),
        Source("icon_seamless", "ICON (Germany)", Source.Kind.MODEL),
        Source("ukmo_seamless", "UK Met Office", Source.Kind.MODEL),
        Source("gem_seamless", "GEM (Canada)", Source.Kind.MODEL),
        Source("jma_seamless", "JMA (Japan)", Source.Kind.MODEL),
        Source("meteofrance_seamless", "Météo-France", Source.Kind.MODEL),
        Source("cma_grapes_global", "CMA (China)", Source.Kind.MODEL),
    )
    val MODELS_SAFE = MODELS.take(3)

    /** Open-Meteo ensembles: the share of members with rain is a real probability. */
    val ENSEMBLES = listOf(
        Source("ecmwf_ifs025", "ECMWF ensemble", Source.Kind.ENSEMBLE),
        Source("gfs025", "GFS ensemble", Source.Kind.ENSEMBLE),
        Source("icon_seamless", "ICON ensemble", Source.Kind.ENSEMBLE),
    )

    val METNO = Source("metno", "MET Norway", Source.Kind.PROVIDER)
    /** The app's main forecast (Open-Meteo best match), for comparison. */
    val MAIN = Source("main", "Main forecast", Source.Kind.PROVIDER)

    fun key(s: Source) = if (s.kind == Source.Kind.ENSEMBLE) "ens_${s.id}" else s.id

    val ALL: List<Source> = listOf(MAIN) + MODELS + ENSEMBLES + METNO

    fun label(key: String): String = ALL.firstOrNull { key(it) == key }?.label ?: key

    const val WET_MM = 0.1

    /** A rain chance for a single-run model, from its amount (it either rains in the model or not). */
    fun chanceFromMm(mm: Double?): Double? = when {
        mm == null -> null
        mm >= 0.5 -> 90.0
        mm >= WET_MM -> 70.0
        else -> 5.0
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray
    private fun JsonArray.d(i: Int) = (getOrNull(i) as? JsonPrimitive)?.doubleOrNull
    private fun JsonArray.l(i: Int) = (getOrNull(i) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun checkError(root: JsonObject) {
        if ((root["error"] as? JsonPrimitive)?.contentOrNull == "true") error((root["reason"] as? JsonPrimitive)?.contentOrNull ?: "API error")
    }

    fun modelsUrl(lat: Double, lon: Double, models: List<Source>): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${c(lat)}&longitude=${c(lon)}" +
            "&hourly=temperature_2m,precipitation,precipitation_probability&models=${models.joinToString(",") { it.id }}" +
            "&forecast_days=3&past_hours=1&timezone=GMT&timeformat=unixtime"

    fun ensembleUrl(lat: Double, lon: Double, model: Source): String =
        "https://ensemble-api.open-meteo.com/v1/ensemble?latitude=${c(lat)}&longitude=${c(lon)}" +
            "&hourly=temperature_2m,precipitation&models=${model.id}&forecast_days=3&timezone=GMT&timeformat=unixtime"

    fun metnoUrl(lat: Double, lon: Double): String =
        "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${String.format(Locale.US, "%.3f", lat)}&lon=${String.format(Locale.US, "%.3f", lon)}"

    private fun c(v: Double) = String.format(Locale.US, "%.4f", v)

    /** Multi-model response: keys are suffixed with the model name, e.g. temperature_2m_gfs_seamless. */
    fun parseModels(body: String, models: List<Source>): List<SourceHour> {
        val root = json.parseToJsonElement(body).jsonObject
        checkError(root)
        val h = root["hourly"] as? JsonObject ?: return emptyList()
        val times = h.arr("time") ?: return emptyList()
        return models.flatMap { m ->
            val t = h.arr("temperature_2m_${m.id}") ?: (if (models.size == 1) h.arr("temperature_2m") else null)
            val p = h.arr("precipitation_${m.id}") ?: (if (models.size == 1) h.arr("precipitation") else null)
            val pp = h.arr("precipitation_probability_${m.id}") ?: (if (models.size == 1) h.arr("precipitation_probability") else null)
            if (t == null && p == null) return@flatMap emptyList()
            (0 until times.size).mapNotNull { i ->
                val time = times.l(i) ?: return@mapNotNull null
                val temp = t?.d(i)
                val mm = p?.d(i)
                if (temp == null && mm == null) return@mapNotNull null
                SourceHour(key(m), time, temp, mm, pp?.d(i) ?: chanceFromMm(mm))
            }
        }
    }

    /** Ensemble response: one key per member (precipitation, precipitation_member01, ...). */
    fun parseEnsemble(body: String, model: Source): List<SourceHour> {
        val root = json.parseToJsonElement(body).jsonObject
        checkError(root)
        val h = root["hourly"] as? JsonObject ?: return emptyList()
        val times = h.arr("time") ?: return emptyList()
        val rain = h.entries.filter { it.key == "precipitation" || it.key.startsWith("precipitation_member") }.mapNotNull { it.value as? JsonArray }
        val temp = h.entries.filter { it.key == "temperature_2m" || it.key.startsWith("temperature_2m_member") }.mapNotNull { it.value as? JsonArray }
        if (rain.isEmpty()) return emptyList()
        return (0 until times.size).mapNotNull { i ->
            val time = times.l(i) ?: return@mapNotNull null
            val mm = rain.mapNotNull { it.d(i) }
            if (mm.isEmpty()) return@mapNotNull null
            val temps = temp.mapNotNull { it.d(i) }
            SourceHour(key(model), time, temps.takeIf { it.isNotEmpty() }?.average(), mm.sorted()[mm.size / 2],
                100.0 * mm.count { it >= WET_MM } / mm.size)
        }
    }

    /** MET Norway Locationforecast (compact). */
    fun parseMetno(body: String): List<SourceHour> {
        val root = json.parseToJsonElement(body).jsonObject
        val series = ((root["properties"] as? JsonObject)?.get("timeseries") as? JsonArray) ?: return emptyList()
        val temps = HashMap<Long, Double>()
        val rain = LinkedHashMap<Long, Pair<Double?, Double?>>()
        series.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val time = (o["time"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() } ?: return@forEach
            val data = o["data"] as? JsonObject ?: return@forEach
            (((data["instant"] as? JsonObject)?.get("details") as? JsonObject)?.get("air_temperature") as? JsonPrimitive)?.doubleOrNull
                ?.let { temps[time] = it }
            val next = ((data["next_1_hours"] as? JsonObject)?.get("details") as? JsonObject) ?: return@forEach
            val mm = (next["precipitation_amount"] as? JsonPrimitive)?.doubleOrNull
            val prob = (next["probability_of_precipitation"] as? JsonPrimitive)?.doubleOrNull
            // MET's next_1_hours covers the hour starting at [time]; Open-Meteo hours cover the hour ending at theirs.
            rain[time + 3600] = mm to (prob ?: chanceFromMm(mm))
        }
        // Temperature is an instant reading, so it stays at its own time.
        return rain.map { (t, r) -> SourceHour(METNO.id, t, temps[t], r.first, r.second) }
    }

    /**
     * How each source did over past hours, for forecasts issued [minLeadH]..[maxLeadH] hours ahead
     * (the latest such forecast for each hour). Actuals are the model analysis for the spot, and your
     * own Raining/Dry reports are scored separately.
     */
    fun scores(
        issued: List<IssuedSourceHour>, obs: List<ObsHour>, reports: List<RainReport> = emptyList(),
        minLeadH: Int = 1, maxLeadH: Int = 30,
    ): List<SourceScore> {
        val actual = obs.associateBy { it.time }
        return issued.groupBy { it.source }.map { (src, rows) ->
            val best = rows.filter { (it.time - it.issuedAt) / 3600 in minLeadH..maxLeadH }
                .groupBy { it.time }.mapValues { (_, v) -> v.maxBy { it.issuedAt } }
            var n = 0; var tErr = 0.0; var tN = 0; var right = 0; var rN = 0; var brier = 0.0; var bN = 0
            for ((time, f) in best) {
                val a = actual[time] ?: continue
                n++
                if (f.temperature != null && a.temperature != null) { tErr += abs(f.temperature - a.temperature); tN++ }
                val wet = a.precipitation?.let { it >= WET_MM } ?: continue
                f.precipitation?.let { rN++; if ((it >= WET_MM) == wet) right++ }
                f.rainChance?.let { bN++; val p = it / 100; val o = if (wet) 1.0 else 0.0; brier += (p - o) * (p - o) }
            }
            var repRight = 0; var repTotal = 0
            for (r in reports) {
                val hour = ((r.time / 3600) + 1) * 3600
                val f = best[hour] ?: continue
                val chance = f.rainChance ?: continue
                repTotal++
                if ((chance >= 50) == r.raining) repRight++
            }
            SourceScore(src, n, if (tN > 0) tErr / tN else null, if (rN > 0) right.toDouble() / rN else null,
                if (bN > 0) brier / bN else null, repRight, repTotal)
        }.sortedBy { it.brier ?: 1.0 }
    }

    /**
     * Each past hour with what actually happened and what every source said for it: the latest
     * forecast made [minLeadH]..[maxLeadH] hours ahead, as in [scores]. Newest hour first.
     */
    fun pastHours(issued: List<IssuedSourceHour>, obs: List<ObsHour>, minLeadH: Int = 1, maxLeadH: Int = 30): List<PastHour> {
        val actual = obs.associateBy { it.time }
        return issued.filter { (it.time - it.issuedAt) / 3600 in minLeadH..maxLeadH && it.time in actual }
            .groupBy { it.time }
            .map { (time, rows) ->
                val a = actual.getValue(time)
                PastHour(time, a.temperature, a.precipitation,
                    rows.groupBy { it.source }.mapValues { (_, v) -> v.maxBy { it.issuedAt } })
            }
            .sortedByDescending { it.time }
    }

    /** Per day and source: how far off temperatures were and how often rain or no rain was right. Newest day first. */
    fun daily(past: List<PastHour>, zone: java.time.ZoneId): List<DayScore> =
        past.groupBy { java.time.Instant.ofEpochSecond(it.time).atZone(zone).toLocalDate() }
            .flatMap { (date, hours) ->
                hours.flatMap { h -> h.forecasts.values.map { h to it } }.groupBy { it.second.source }.map { (src, pairs) ->
                    val errs = pairs.mapNotNull { (h, f) -> if (h.temperature != null && f.temperature != null) f.temperature - h.temperature else null }
                    val rain = pairs.mapNotNull { (h, f) -> if (h.precipitation != null && f.precipitation != null) (f.precipitation >= WET_MM) == (h.precipitation >= WET_MM) else null }
                    DayScore(date, src, pairs.size, errs.sumOf { abs(it) }, errs.sum(), errs.size, rain.count { it }, rain.size)
                }
            }
            .sortedWith(compareByDescending<DayScore> { it.date }.thenBy { it.source })

    /** Days summed into weeks starting on Monday, per source. Newest week first. */
    fun weekly(days: List<DayScore>): List<DayScore> =
        days.groupBy { it.date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) to it.source }
            .map { (k, v) -> v.reduce { a, b -> a + b }.copy(date = k.first) }
            .sortedWith(compareByDescending<DayScore> { it.date }.thenBy { it.source })

    /** Hours of history at which a source's own record counts as much as an equal share. */
    const val PRIOR_HOURS = 48.0

    /**
     * Blend weights: inverse error, pulled towards equal weights while there are few scored hours.
     * Sources with no record get the equal share.
     */
    fun weights(sources: List<String>, scores: List<SourceScore>): List<SourceWeight> {
        if (sources.isEmpty()) return emptyList()
        val byId = scores.associateBy { it.source }
        val equal = 1.0 / sources.size
        fun norm(raw: Map<String, Double>): Map<String, Double> { val s = raw.values.sum(); return raw.mapValues { it.value / s } }
        val tempSkill = norm(sources.associateWith { s -> byId[s]?.tempMae?.let { 1 / (it * it + 0.25) } ?: 0.0 }.let { m ->
            val known = m.values.filter { it > 0 }; val fill = if (known.isEmpty()) 1.0 else known.average(); m.mapValues { if (it.value > 0) it.value else fill } })
        val rainSkill = norm(sources.associateWith { s -> byId[s]?.brier?.let { 1 / (it + 0.02) } ?: 0.0 }.let { m ->
            val known = m.values.filter { it > 0 }; val fill = if (known.isEmpty()) 1.0 else known.average(); m.mapValues { if (it.value > 0) it.value else fill } })
        val raw = sources.map { s ->
            val n = (byId[s]?.hours ?: 0).toDouble()
            val k = n / (n + PRIOR_HOURS)
            SourceWeight(s, k * tempSkill.getValue(s) + (1 - k) * equal, k * rainSkill.getValue(s) + (1 - k) * equal)
        }
        val tSum = raw.sumOf { it.temp }; val rSum = raw.sumOf { it.rain }
        return raw.map { it.copy(temp = it.temp / tSum, rain = it.rain / rSum) }.sortedByDescending { it.rain }
    }

    /** Weighted blend of the latest forecast from each source, hour by hour. */
    fun blend(latest: List<SourceHour>, weights: List<SourceWeight>, from: Long, hours: Int = 48): List<BlendHour> {
        val w = weights.associateBy { it.source }
        return latest.filter { it.time > from && it.time <= from + hours * 3600L }.groupBy { it.time }.toSortedMap().map { (time, rows) ->
            val t = rows.filter { it.temperature != null && w[it.source] != null }
            val r = rows.filter { it.rainChance != null && w[it.source] != null }
            val tw = t.sumOf { w.getValue(it.source).temp }
            val rw = r.sumOf { w.getValue(it.source).rain }
            BlendHour(
                time,
                if (tw > 0) t.sumOf { it.temperature!! * w.getValue(it.source).temp } / tw else null,
                if (rw > 0) r.sumOf { it.rainChance!! * w.getValue(it.source).rain } / rw else null,
                r.minOfOrNull { it.rainChance!! }, r.maxOfOrNull { it.rainChance!! }, rows.size,
            )
        }
    }
}
