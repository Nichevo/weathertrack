package app.weathertrack.core.map

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/**
 * Time zone boundaries for the map, read from the bundled timezones.bin (built from
 * timezone-boundary-builder, © OpenStreetMap contributors, ODbL; see tools/timezones).
 * Each zone is an IANA name such as "Pacific/Auckland", so its offset and daylight saving
 * come from the phone's own time zone rules, not from the file.
 */
class TimeZoneShapes(val zones: List<Zone>) {

    /** One ring of a polygon, in degrees. The first ring of a polygon is its outline, the rest are holes. */
    class Ring(val lons: DoubleArray, val lats: DoubleArray) {
        val size get() = lons.size
    }

    class Polygon(val rings: List<Ring>) {
        val west = rings[0].lons.min()
        val east = rings[0].lons.max()
        val south = rings[0].lats.min()
        val north = rings[0].lats.max()

        /** Even-odd test over all rings, so a point in a hole is outside. */
        fun contains(lat: Double, lon: Double): Boolean {
            if (lat < south || lat > north || lon < west || lon > east) return false
            var inside = false
            for (r in rings) {
                var j = r.size - 1
                for (i in 0 until r.size) {
                    val yi = r.lats[i]
                    val yj = r.lats[j]
                    if ((yi > lat) != (yj > lat)) {
                        val x = r.lons[i] + (lat - yi) / (yj - yi) * (r.lons[j] - r.lons[i])
                        if (lon < x) inside = !inside
                    }
                    j = i
                }
            }
            return inside
        }
    }

    class Zone(val id: String, val labelLat: Double, val labelLon: Double, val polygons: List<Polygon>) {
        val west = polygons.minOf { it.west }
        val east = polygons.maxOf { it.east }
        val south = polygons.minOf { it.south }
        val north = polygons.maxOf { it.north }

        fun contains(lat: Double, lon: Double): Boolean =
            lat in south..north && lon in west..east && polygons.any { it.contains(lat, lon) }

        /** Rough size of the zone's biggest piece in square degrees, to label big zones first. */
        val size: Double = polygons.maxOf { (it.east - it.west) * (it.north - it.south) }
    }

    /** The zone at a point. Land zones win over the open-ocean "Etc/GMT" bands where both claim it. */
    fun zoneAt(lat: Double, lon: Double): Zone? {
        val l = wrapLon(lon)
        val hits = zones.filter { it.contains(lat, l) }
        return hits.firstOrNull { !it.id.startsWith("Etc/") } ?: hits.firstOrNull()
    }

    companion object {
        private const val MAGIC = 0x545A4231 // "TZB1"

        fun wrapLon(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

        /** Reads the packed format written by tools/timezones/pack_timezones.py. */
        fun read(input: InputStream): TimeZoneShapes {
            val d = DataInputStream(input.buffered())
            if (d.readInt() != MAGIC) throw IOException("Not a time zone file")
            val n = d.readInt()
            val zones = ArrayList<Zone>(n)
            repeat(n) {
                val id = d.readUTF()
                val labelLat = d.readInt() / 1e4
                val labelLon = d.readInt() / 1e4
                val polys = List(d.readInt()) {
                    Polygon(List(d.readInt()) {
                        val count = d.readInt()
                        val lons = DoubleArray(count)
                        val lats = DoubleArray(count)
                        var x = d.readInt()
                        var y = d.readInt()
                        lons[0] = x / 1e4; lats[0] = y / 1e4
                        for (i in 1 until count) {
                            x += readZigzag(d)
                            y += readZigzag(d)
                            lons[i] = x / 1e4; lats[i] = y / 1e4
                        }
                        Ring(lons, lats)
                    })
                }
                if (polys.isNotEmpty()) zones += Zone(id, labelLat, labelLon, polys)
            }
            return TimeZoneShapes(zones)
        }

        private fun readZigzag(d: DataInputStream): Int {
            var shift = 0
            var z = 0L
            while (true) {
                val b = d.readUnsignedByte()
                z = z or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return ((z ushr 1) xor -(z and 1)).toInt()
        }
    }
}

/** What a time zone's clock says, and when it next changes, from the phone's time zone rules. */
object ZoneClock {
    data class Info(
        val id: String,
        val offsetSeconds: Int,
        val daylightSaving: Boolean,
        /** Next change of offset (daylight saving starting or ending), if the zone has one ahead. */
        val nextChange: Instant?,
        val nextOffsetSeconds: Int?,
    )

    private val ids = HashMap<String, ZoneId?>()

    fun zone(id: String): ZoneId? = ids.getOrPut(id) { runCatching { ZoneId.of(id) }.getOrNull() }

    fun info(id: String, at: Instant): Info? {
        val z = zone(id) ?: return null
        val rules = z.rules
        val next = rules.nextTransition(at)
        return Info(
            id = id,
            offsetSeconds = rules.getOffset(at).totalSeconds,
            daylightSaving = rules.isDaylightSavings(at),
            nextChange = next?.instant,
            nextOffsetSeconds = next?.offsetAfter?.totalSeconds,
        )
    }

    fun offsetSeconds(id: String, at: Instant): Int? = zone(id)?.rules?.getOffset(at)?.totalSeconds

    /** "UTC+13", "UTC+5:45", "UTC−3:30" or "UTC". */
    fun offsetLabel(seconds: Int): String {
        if (seconds == 0) return "UTC"
        val sign = if (seconds > 0) "+" else "−"
        val m = abs(seconds) / 60
        return "UTC$sign${m / 60}" + if (m % 60 != 0) String.format(Locale.US, ":%02d", m % 60) else ""
    }

    /** How far another zone's clock is from yours: "same time", "3 h ahead", "4 h 30 min behind". */
    fun difference(theirs: Int, yours: Int): String {
        val diff = theirs - yours
        if (diff == 0) return "same time as you"
        val m = abs(diff) / 60
        val h = m / 60
        val text = when {
            h == 0 -> "${m % 60} min"
            m % 60 == 0 -> "$h h"
            else -> "$h h ${m % 60} min"
        }
        return text + if (diff > 0) " ahead" else " behind"
    }

    /** Short difference for map labels: "+3 h", "−4:30", "same". */
    fun shortDifference(theirs: Int, yours: Int): String {
        val diff = theirs - yours
        if (diff == 0) return "same"
        val m = abs(diff) / 60
        val sign = if (diff > 0) "+" else "−"
        return if (m % 60 == 0) "$sign${m / 60} h" else "$sign${m / 60}" + String.format(Locale.US, ":%02d", m % 60)
    }

    /**
     * Fill colour for an offset (ARGB). Whole hours step round the colour wheel by about 137°,
     * so neighbouring zones always look different; half and quarter hours sit between.
     */
    fun color(offsetSeconds: Int, alpha: Int = 70): Int {
        val hours = offsetSeconds / 3600.0
        val hue = (((hours + 12) * 137.508) % 360 + 360) % 360
        return (alpha shl 24) or hsvToRgb(hue, 0.55, 0.92)
    }

    private fun hsvToRgb(h: Double, s: Double, v: Double): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun ch(f: Double) = ((f + m) * 255).toInt().coerceIn(0, 255)
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** Offset of the place's own zone, falling back to UTC for an unknown name. */
    fun offsetOrUtc(id: String?, at: Instant): Int = id?.let { offsetSeconds(it, at) } ?: ZoneOffset.UTC.totalSeconds

    /** "in 4 months", "in 12 days", "in 5 hours" — how far off a change is. */
    fun until(from: Instant, to: Instant): String {
        val d = Duration.between(from, to)
        val days = d.toDays()
        return when {
            days >= 60 -> "in about ${(days + 15) / 30} months"
            days >= 2 -> "in $days days"
            d.toHours() >= 2 -> "in ${d.toHours()} hours"
            else -> "in ${d.toMinutes().coerceAtLeast(1)} min"
        }
    }
}
