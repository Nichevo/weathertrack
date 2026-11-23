package app.weathertrack.core.pollen

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** One hour of weather, from a forecast or from what happened. */
data class PollenHour(
    val time: Long,
    val temperature: Double?,
    val precipitation: Double?,
    val humidity: Double? = null,
    val windSpeed: Double? = null,
    val cloudCover: Double? = null,
)

/** How much of each pollen type is about in a month, 0 (none) to 1 (peak). */
data class SeasonMonth(val month: Int, val trees: Double, val grass: Double, val weeds: Double) {
    val baseline: Double get() {
        val top = maxOf(trees, grass, weeds)
        return (top + 0.25 * (trees + grass + weeds - top)).coerceIn(0.0, 1.0)
    }
    val main: String? get() = listOf("Tree" to trees, "Grass" to grass, "Weed" to weeds).maxBy { it.second }
        .takeIf { it.second >= 0.3 }?.first
}

enum class PollenLevel(val label: String) { LOW("Low"), MODERATE("Moderate"), HIGH("High") }

/** The weather multipliers for one day (1 = no effect). */
data class PollenFactors(val season: Double, val warmth: Double, val sun: Double, val dryness: Double, val rain: Double, val wind: Double) {
    val product get() = season * warmth * sun * dryness * rain * wind
    /** For the personal model: the log of each part. */
    fun features(): DoubleArray = doubleArrayOf(ln(season + 0.05), ln(warmth), ln(sun), ln(dryness), ln(rain), ln(wind))
}

data class PollenDay(
    val date: LocalDate,
    val level: PollenLevel,
    /** 0..10 from the rules, or the personal model's chance (0..1) times 10. */
    val index: Double,
    val reasons: List<String>,
    /** True when the level comes from the model fitted to your own reports. */
    val personal: Boolean,
    val factors: PollenFactors,
)

/** A day you said pollen bothered you (true) or that you had no symptoms (false). */
data class PollenLabel(val date: LocalDate, val symptoms: Boolean)

/** Scores of estimated levels against your reports. */
data class PollenScore(val right: Int, val total: Int, val symptomDays: Int, val calledOnSymptomDays: Int) {
    val share: Double? get() = if (total == 0) null else right.toDouble() / total
}

/**
 * Our own pollen estimate where no pollen forecast exists (Open-Meteo's pollen is Europe only): a season
 * calendar times what the weather does to pollen in the air, optionally fitted to your own reports.
 * It is an estimate, not a measured pollen count.
 */
object Pollen {
    /** Reported days needed (with at least [MIN_EACH] of each kind) before the personal model is used. */
    const val MIN_LABELS = 30
    const val MIN_EACH = 5

    /** Lines of "month,trees,grass,weeds"; lines starting with # and the header are skipped. */
    fun parseCalendar(csv: String): List<SeasonMonth> = csv.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it[0].isDigit() }
        .mapNotNull { line ->
            val p = line.split(',').map { it.trim() }
            val m = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            SeasonMonth(m, p.getOrNull(1)?.toDoubleOrNull() ?: 0.0, p.getOrNull(2)?.toDoubleOrNull() ?: 0.0, p.getOrNull(3)?.toDoubleOrNull() ?: 0.0)
        }.toList()

    /** The calendar month for [date]; it is written for the southern hemisphere, so the north is shifted 6 months. */
    fun season(calendar: List<SeasonMonth>, date: LocalDate, latitude: Double): SeasonMonth? {
        val m = if (latitude > 0) (date.monthValue + 5) % 12 + 1 else date.monthValue
        return calendar.firstOrNull { it.month == m }
    }

    private fun lerp(x: Double, x0: Double, x1: Double, y0: Double, y1: Double) =
        if (x <= x0) y0 else if (x >= x1) y1 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)

    /**
     * The day's factors from [hours] around it: the daytime (10 am to 5 pm) for warmth, sun, dryness and
     * wind, and the 24 hours before 10 am plus the day itself for rain.
     */
    fun factors(season: SeasonMonth?, hours: List<PollenHour>, date: LocalDate, zone: ZoneId): PollenFactors? {
        val start = date.atTime(10, 0).atZone(zone).toEpochSecond()
        val end = date.atTime(17, 0).atZone(zone).toEpochSecond()
        val day = hours.filter { it.time in start..end }
        if (day.size < 4) return null
        val before = hours.filter { it.time in (start - 24 * 3600) until start }
        val tMax = day.mapNotNull { it.temperature }.maxOrNull()
        val cloud = day.mapNotNull { it.cloudCover }.takeIf { it.isNotEmpty() }?.average()
        val hum = day.mapNotNull { it.humidity }.minOrNull()
        val wind = day.mapNotNull { it.windSpeed }.takeIf { it.isNotEmpty() }?.average()
        val rainBefore = before.sumOf { it.precipitation ?: 0.0 }
        val rainDay = day.sumOf { it.precipitation ?: 0.0 }

        val warmth = tMax?.let { if (it < 18) lerp(it, 10.0, 18.0, 0.5, 1.0) else lerp(it, 18.0, 26.0, 1.0, 1.3) } ?: 1.0
        val sun = cloud?.let { lerp(it, 40.0, 85.0, 1.15, 0.7) } ?: 1.0
        val dryness = hum?.let { lerp(it, 45.0, 85.0, 1.15, 0.75) } ?: 1.0
        val sunnyWarm = (cloud ?: 100.0) < 50 && (tMax ?: 0.0) >= 18 && rainDay < 0.5
        val rain = when {
            rainDay >= 2.0 -> 0.5
            rainDay >= 0.5 -> 0.75
            // Rain the day before washes pollen out, but a warm sunny day after it often brings a burst.
            rainBefore >= 1.0 && sunnyWarm -> 1.15
            rainBefore >= 5.0 -> 0.6
            rainBefore >= 1.0 -> 0.8
            else -> 1.0
        }
        val windF = wind?.let { w -> when {
            w < 5 -> 0.9
            w <= 30 -> lerp(w, 5.0, 15.0, 1.0, 1.15)
            else -> lerp(w, 30.0, 45.0, 1.15, 0.9)
        } } ?: 1.0
        return PollenFactors(season?.baseline ?: 0.5, warmth, sun, dryness, rain, windF)
    }

    fun levelOf(index: Double): PollenLevel = when {
        index < 3 -> PollenLevel.LOW
        index < 6 -> PollenLevel.MODERATE
        else -> PollenLevel.HIGH
    }

    /** The day's estimate, from the rules or (when [model] is given) from your personal model. */
    fun day(
        season: SeasonMonth?, hours: List<PollenHour>, date: LocalDate, zone: ZoneId, model: PollenModel? = null,
    ): PollenDay? {
        val f = factors(season, hours, date, zone) ?: return null
        val rules = (10 * f.product).coerceIn(0.0, 10.0)
        val p = model?.chance(f)
        val index = p?.let { it * 10 } ?: rules
        val level = if (p != null) when { p < 0.3 -> PollenLevel.LOW; p < 0.6 -> PollenLevel.MODERATE; else -> PollenLevel.HIGH } else levelOf(rules)
        return PollenDay(date, level, index, reasons(season, f), p != null, f)
    }

    /** The two or three things moving the estimate most. */
    fun reasons(season: SeasonMonth?, f: PollenFactors): List<String> {
        val out = mutableListOf<String>()
        season?.main?.let { out += if (f.season >= 0.8) "$it pollen season at its peak" else "$it pollen season" }
            ?: out.add("Little pollen season")
        val parts = listOf(
            f.warmth to (if (f.warmth > 1) "warm" else "cool"),
            f.sun to (if (f.sun > 1) "sunny" else "cloudy"),
            f.dryness to (if (f.dryness > 1) "dry air" else "humid"),
            f.rain to (if (f.rain > 1) "sun after rain" else "rain clears the air"),
            f.wind to (if (f.wind > 1) "breezy" else if (f.wind < 1) "still or very windy" else ""),
        ).filter { abs(ln(it.first)) >= 0.09 && it.second.isNotEmpty() }
            .sortedByDescending { abs(ln(it.first)) }.take(2).map { it.second }
        if (parts.isNotEmpty()) out += parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        return out
    }

    /** Your reports as one label per day: a pollen tap wins over "no symptoms" on the same day. */
    fun labels(times: List<Pair<Long, Boolean>>, zone: ZoneId): List<PollenLabel> =
        times.groupBy { Instant.ofEpochSecond(it.first).atZone(zone).toLocalDate() }
            .map { (d, v) -> PollenLabel(d, v.any { it.second }) }.sortedBy { it.date }

    /** Fits the personal model once there are enough reported days of both kinds; null until then. */
    fun fit(examples: List<Pair<PollenFactors, Boolean>>): PollenModel? {
        if (examples.size < MIN_LABELS) return null
        if (examples.count { it.second } < MIN_EACH || examples.count { !it.second } < MIN_EACH) return null
        return PollenModel.fit(examples.map { it.first.features() to it.second })
    }

    /** How the estimate did on days you reported: Moderate or High counts as calling symptoms. */
    fun score(days: List<Pair<PollenDay, Boolean>>): PollenScore {
        val right = days.count { (d, s) -> (d.level != PollenLevel.LOW) == s }
        val sym = days.filter { it.second }
        return PollenScore(right, days.size, sym.size, sym.count { it.first.level != PollenLevel.LOW })
    }

    /** When in the day it's usually worst, for planning walks. */
    const val TIME_OF_DAY = "Usually worst from late morning to mid-afternoon, lower early and in the evening."

    fun text(d: PollenDay): String = "${d.level.label} (estimate${if (d.personal) ", fitted to your reports" else ""}). " +
        d.reasons.joinToString(". ") + "." + if (d.level != PollenLevel.LOW) " $TIME_OF_DAY" else ""
}

/** Logistic regression on the log factors, with a little L2 so a few dozen days can't overfit wildly. */
class PollenModel(private val w: DoubleArray, private val b: Double) {
    fun chance(f: PollenFactors): Double {
        val x = f.features()
        var z = b
        for (i in x.indices) z += w[i] * x[i]
        return 1 / (1 + exp(-z))
    }

    companion object {
        fun fit(data: List<Pair<DoubleArray, Boolean>>, iterations: Int = 2000, rate: Double = 0.1, l2: Double = 0.05): PollenModel {
            val n = data.first().first.size
            val w = DoubleArray(n)
            var b = 0.0
            repeat(iterations) {
                val gw = DoubleArray(n)
                var gb = 0.0
                for ((x, y) in data) {
                    var z = b
                    for (i in 0 until n) z += w[i] * x[i]
                    val e = 1 / (1 + exp(-z)) - (if (y) 1.0 else 0.0)
                    for (i in 0 until n) gw[i] += e * x[i]
                    gb += e
                }
                for (i in 0 until n) w[i] -= rate * (gw[i] / data.size + l2 * w[i])
                b -= rate * gb / data.size
            }
            return PollenModel(w, b)
        }
    }
}
