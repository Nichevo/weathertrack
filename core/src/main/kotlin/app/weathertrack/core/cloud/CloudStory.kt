package app.weathertrack.core.cloud

import app.weathertrack.core.model.HourPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Plain lines about the cloud layers ahead and what they do to the temperature. */
object CloudStory {
    /** Low cloud this thick holds the temperature down by day and up by night. */
    const val LOW_THICK = 60.0
    const val LOW_GONE = 40.0
    const val CLEAR_MAX = 25.0
    const val FROST_AT = 3.0

    private val hourFmt = DateTimeFormatter.ofPattern("h a", Locale.ENGLISH)
    private val minuteFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    fun time(epoch: Long, zone: ZoneId): String {
        val t = Instant.ofEpochSecond(epoch).atZone(zone)
        return (if (t.minute == 0) hourFmt else minuteFmt).format(t).lowercase(Locale.ENGLISH)
    }

    /** "Low 80% · mid 10% · high 0%" for one hour, or null if the layers are unknown. */
    fun layers(h: HourPoint?): String? {
        h ?: return null
        if (h.cloudCoverLow == null && h.cloudCoverMid == null && h.cloudCoverHigh == null) return null
        fun p(v: Double?) = v?.let { "${it.roundToInt()}%" } ?: "?"
        return "Low ${p(h.cloudCoverLow)} · mid ${p(h.cloudCoverMid)} · high ${p(h.cloudCoverHigh)}"
    }

    /**
     * Up to three lines for the next day: a low cloud spell and when it clears, thin high cloud, and
     * whether tonight is clear (cold, maybe frost) or cloudy (mild).
     */
    fun lines(hours: List<HourPoint>, now: Long, zone: ZoneId): List<String> {
        val ahead = hours.filter { it.time > now }.sortedBy { it.time }.take(24)
        if (ahead.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val first = ahead.first()
        val day = ahead.takeWhile { it.isDay != false }
        fun low(h: HourPoint) = h.cloudCoverLow ?: 0.0
        fun avgTemp(list: List<HourPoint>) = list.mapNotNull { it.temperature }.takeIf { it.isNotEmpty() }?.average()

        if (first.isDay != false && low(first) >= LOW_THICK && first.cloudCoverLow != null) {
            val spell = day.takeWhile { low(it) >= LOW_GONE }
            val after = day.drop(spell.size)
            val near = avgTemp(spell)?.roundToInt()
            if (after.isEmpty()) {
                out += "Low cloud all day keeps it near ${near ?: "?"}°"
            } else {
                // The hour after the spell ends is the first one with the low cloud gone.
                val clears = after.first().time - 3600
                val peak = after.mapNotNull { it.temperature }.maxOrNull()?.roundToInt()
                out += "Low cloud until ${time(clears, zone)} keeps it near ${near ?: "?"}°" +
                    if (peak != null && near != null && peak >= near + 2) ", then sun lifts it to $peak°" else ""
            }
        } else if (day.isNotEmpty()) {
            val start = day.windowed(2).firstOrNull { w -> w.all { low(it) >= LOW_THICK } }?.first()
            if (start != null) {
                val spell = day.dropWhile { it.time < start.time }.takeWhile { low(it) >= LOW_GONE }
                out += "Low cloud from ${time(start.time - 3600, zone)} holds it near ${avgTemp(spell)?.roundToInt() ?: "?"}°"
            } else if (first.isDay != false) {
                val b = Sky.blocking(first.cloudCoverLow, first.cloudCoverMid)
                if (b != null && b < Sky.SUNNY_MAX && (first.cloudCoverHigh ?: 0.0) >= 60) {
                    out += "Thin high cloud only: still sunny"
                }
            }
        }

        val night = ahead.dropWhile { it.isDay != false }.takeWhile { it.isDay == false }.take(12)
        if (night.size >= 4) {
            val lowTemp = night.mapNotNull { it.temperature }.minOrNull()
            val cover = night.mapNotNull { it.cloudCover }.takeIf { it.isNotEmpty() }?.average()
            val lowCloud = night.mapNotNull { it.cloudCoverLow }.takeIf { it.isNotEmpty() }?.average()
            val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
            val start = Instant.ofEpochSecond(night.first().time).atZone(zone)
            val tonight = start.toLocalDate() == today || (start.toLocalDate() == today.plusDays(1) && start.hour < 6)
            val label = if (tonight) "Tonight" else "Tomorrow night"
            val lt = lowTemp?.roundToInt()
            when {
                cover != null && cover <= CLEAR_MAX && lowTemp != null && lowTemp <= FROST_AT ->
                    out += "Clear ${label.lowercase()}: cold, frost possible (low $lt°)".replaceFirstChar { it.uppercase() }
                cover != null && cover <= CLEAR_MAX ->
                    out += "Clear ${label.lowercase()}: cools quickly to $lt°".replaceFirstChar { it.uppercase() }
                lowCloud != null && lowCloud >= LOW_THICK ->
                    out += "Low cloud ${label.lowercase()} keeps it mild (low $lt°)".replaceFirstChar { it.uppercase() }
            }
        }
        return out
    }
}
