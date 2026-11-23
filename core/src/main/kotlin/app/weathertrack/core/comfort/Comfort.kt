package app.weathertrack.core.comfort

import app.weathertrack.core.wind.Wind
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** How you felt at a moment, with the weather then. */
data class Feeling(
    val time: Long,
    val kind: Kind,
    val temperature: Double?,
    val feelsLike: Double?,
    val windSpeed: Double?,
    val windDirection: Double?,
    val humidity: Double?,
) {
    enum class Kind(val key: String) {
        CHILLY("chilly"), WARM("warm"), POLLEN("pollen"),
        /** "No symptoms today": a day pollen didn't bother you, so the pollen model has both kinds of day. */
        NO_SYMPTOMS("no_symptoms");
        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
    }
}

/** What your own feedback says about you, once there is enough of it. */
object Comfort {
    const val MIN_TAPS = 3

    private fun median(v: List<Double>): Double? = v.sorted().takeIf { it.isNotEmpty() }?.let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** The feels-like temperature at which you usually say you're chilly (median), once there are a few taps. */
    fun chillyPoint(f: List<Feeling>): Double? =
        f.filter { it.kind == Feeling.Kind.CHILLY }.mapNotNull { it.feelsLike }.takeIf { it.size >= MIN_TAPS }?.let(::median)

    /** The feels-like temperature at which you usually say it's too warm. */
    fun warmPoint(f: List<Feeling>): Double? =
        f.filter { it.kind == Feeling.Kind.WARM }.mapNotNull { it.feelsLike }.takeIf { it.size >= MIN_TAPS }?.let(::median)

    /** Your learned comfort points, from taps in the last year. */
    data class Model(val chilly: Double?, val chillyTaps: Int, val warm: Double?, val warmTaps: Int) {
        val any get() = chilly != null || warm != null
    }

    /**
     * What your taps from the last [days] days say: where you usually feel chilly and where too warm.
     * If they overlap (chilly at or above too warm), neither is trusted yet.
     */
    fun model(f: List<Feeling>, now: Long, days: Int = 365): Model {
        val recent = f.filter { it.time >= now - days * 86400L }
        var chilly = chillyPoint(recent)
        var warm = warmPoint(recent)
        if (chilly != null && warm != null && chilly >= warm - 2) { chilly = null; warm = null }
        return Model(chilly, recent.count { it.kind == Feeling.Kind.CHILLY && it.feelsLike != null },
            warm, recent.count { it.kind == Feeling.Kind.WARM && it.feelsLike != null })
    }

    data class PollenPattern(val taps: Int, val months: List<String>, val windFrom: String?, val windShare: Double?, val humidity: Double?)

    /** When pollen has bothered you: the usual months and wind direction. */
    fun pollen(f: List<Feeling>, zone: ZoneId): PollenPattern? {
        val p = f.filter { it.kind == Feeling.Kind.POLLEN }
        if (p.isEmpty()) return null
        val months = p.groupingBy { Instant.ofEpochSecond(it.time).atZone(zone).month }.eachCount()
            .entries.sortedByDescending { it.value }.take(2).map { it.key.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
        val dirs = p.mapNotNull { Wind.compass8(it.windDirection) }
        val top = dirs.groupingBy { it }.eachCount().maxByOrNull { it.value }
        return PollenPattern(p.size, months, top?.key, top?.let { it.value.toDouble() / dirs.size }, median(p.mapNotNull { it.humidity }))
    }

    /** One or two plain lines for Today. */
    fun summary(f: List<Feeling>, zone: ZoneId): List<String> {
        val out = mutableListOf<String>()
        val chilly = chillyPoint(f); val warm = warmPoint(f)
        if (chilly != null || warm != null) {
            out += listOfNotNull(
                chilly?.let { "you get chilly around ${it.roundToInt()}° feels-like" },
                warm?.let { "too warm from about ${it.roundToInt()}°" },
            ).joinToString(", and ").replaceFirstChar { it.uppercase() } + "."
        }
        pollen(f, zone)?.takeIf { it.taps >= MIN_TAPS }?.let { p ->
            out += "Pollen has bothered you ${p.taps} times, mostly in ${p.months.joinToString(" and ")}" +
                (if (p.windFrom != null && (p.windShare ?: 0.0) >= 0.5) " with ${p.windFrom} winds" else "") + "."
        }
        return out
    }
}
