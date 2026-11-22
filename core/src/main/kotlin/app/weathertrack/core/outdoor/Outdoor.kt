package app.weathertrack.core.outdoor

import app.weathertrack.core.model.DayPoint
import app.weathertrack.core.model.HourPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Rough pavement (asphalt) surface temperature, for dog paws. */
object Pavement {
    /** Sun on dark asphalt adds about 3° per 100 W/m² (25° air in full sun gives about 52°); wind takes some away. */
    fun estimate(airTemp: Double?, shortwave: Double?, windKmh: Double?): Double? {
        airTemp ?: return null
        shortwave ?: return null
        val cooling = 1 - (windKmh ?: 0.0).coerceIn(0.0, 40.0) / 80
        return airTemp + 0.031 * shortwave.coerceAtLeast(0.0) * cooling
    }

    /** The "seven second" hand test fails at about 52°; above about 45° short walks only. */
    const val TOO_HOT = 52.0
    const val WARM = 45.0
}

/** Minutes until fair skin starts to burn. */
object UvBurn {
    /** Minimal erythemal dose by Fitzpatrick skin type I..VI, J/m². */
    private val MED = listOf(200.0, 250.0, 350.0, 450.0, 600.0, 1000.0)

    fun minutes(uv: Double?, skinType: Int = 2): Int? {
        if (uv == null || uv < 1) return null
        val med = MED[(skinType - 1).coerceIn(0, 5)]
        // One UV index unit is 0.025 W/m² of erythemal irradiance.
        return (med / (uv * 0.025 * 60)).roundToInt()
    }

    /** First and last hour today with UV 3 or more (when sun protection is advised). */
    fun protectHours(hours: List<HourPoint>, date: LocalDate, zone: ZoneId): Pair<Long, Long>? {
        val today = hours.filter { Instant.ofEpochSecond(it.time).atZone(zone).toLocalDate() == date && (it.uvIndex ?: 0.0) >= 3 }
        if (today.isEmpty()) return null
        return today.first().time to today.last().time + 3600
    }
}

data class Drying(val score: Int, val label: String, val wetHours: Int, val bestFrom: Long?, val bestTo: Long?)

/** How well washing dries outside between 9 am and 5 pm on [date]. */
object DryingDay {
    fun of(hours: List<HourPoint>, date: LocalDate, zone: ZoneId): Drying? {
        val day = hours.filter {
            val t = Instant.ofEpochSecond(it.time).atZone(zone)
            t.toLocalDate() == date && t.hour in 9..16
        }
        if (day.size < 4) return null
        val scored = day.map { h ->
            val wet = (h.precipitationProbability ?: 0.0) >= 40 || (h.precipitation ?: 0.0) >= 0.2
            val s = if (wet) 0.0 else {
                val dry = ((100 - (h.humidity ?: 70.0)) / 60).coerceIn(0.0, 1.0)
                val warm = (((h.temperature ?: 12.0) - 5) / 20).coerceIn(0.0, 1.0)
                val wind = ((h.windSpeed ?: 0.0) / 20).coerceIn(0.0, 1.0)
                val sun = ((h.shortwaveRadiation ?: ((100 - (h.cloudCover ?: 50.0)) * 6)) / 600).coerceIn(0.0, 1.0)
                0.35 * dry + 0.25 * warm + 0.2 * wind + 0.2 * sun
            }
            h to (wet to s)
        }
        val wetHours = scored.count { it.second.first }
        var score = (scored.map { it.second.second }.average() * 10).roundToInt()
        if (wetHours >= 2) score = score.coerceAtMost(3)
        // Longest stretch of dry hours.
        var best: Pair<Int, Int>? = null
        var start = -1
        scored.forEachIndexed { i, (_, v) ->
            if (!v.first) { if (start < 0) start = i; if (best == null || i - start > best!!.second - best!!.first) best = start to i }
            else start = -1
        }
        val label = when {
            score >= 7 -> "Great drying day"
            score >= 5 -> "Good drying day"
            score >= 3 -> "Slow drying"
            else -> "Not a drying day"
        }
        val b = best?.takeIf { wetHours > 0 }
        return Drying(score, label, wetHours, b?.let { scored[it.first].first.time }, b?.let { scored[it.second].first.time + 3600 })
    }
}

data class Watering(val rainMm: Double, val dryingMm: Double, val days: Int) {
    val deficit get() = dryingMm - rainMm
    val text: String get() = when {
        deficit >= 8 -> "Water the garden: about ${dryingMm.roundToInt()} mm will dry out and only ${rainMm.roundToInt()} mm of rain is due in $days days"
        deficit >= 3 -> "Garden may need a little water: ${dryingMm.roundToInt()} mm drying, ${rainMm.roundToInt()} mm rain in $days days"
        else -> "No need to water: rain (${rainMm.roundToInt()} mm) keeps up with drying in the next $days days"
    }
}

/** Rain against evaporation (ET₀) over the next [days] days, starting today. */
object Garden {
    fun watering(daily: List<DayPoint>, today: LocalDate, days: Int = 3): Watering? {
        val next = daily.filter { !LocalDate.parse(it.date).isBefore(today) }.take(days)
        if (next.size < days || next.any { it.et0 == null }) return null
        return Watering(next.sumOf { it.precipitationSum ?: 0.0 }, next.sumOf { it.et0 ?: 0.0 }, days)
    }
}

data class SunDay(
    val dawn: Long?, val sunrise: Long?, val sunset: Long?, val dusk: Long?,
    /** Evening golden hour: sun below 6° until sunset. */
    val goldenFrom: Long?,
)

/** Sun height and twilight times (accurate to a minute or two, plenty for walks). */
object Sun {
    /** Sun elevation in degrees at [epoch] seconds. */
    fun elevation(lat: Double, lon: Double, epoch: Long): Double {
        val d = epoch / 86400.0 + 2440587.5 - 2451545.0
        val g = Math.toRadians(357.529 + 0.98560028 * d)
        val q = 280.459 + 0.98564736 * d
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = Math.toDegrees(atan2(cos(e) * sin(l), cos(l)))
        val dec = asin(sin(e) * sin(l))
        val gmst = (18.697374558 + 24.06570982441908 * d) * 15
        val ha = Math.toRadians(gmst + lon - ra)
        val la = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)))
    }

    fun day(lat: Double, lon: Double, date: LocalDate, zone: ZoneId): SunDay {
        val start = date.atStartOfDay(zone).toEpochSecond()
        val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
        fun cross(level: Double, rising: Boolean): Long? {
            var t = start
            var prev = elevation(lat, lon, t) - level
            while (t < end) {
                val next = t + 300
                val v = elevation(lat, lon, next) - level
                if ((rising && prev < 0 && v >= 0) || (!rising && prev >= 0 && v < 0)) {
                    var a = t; var b = next
                    repeat(9) {
                        val m = (a + b) / 2
                        val vm = elevation(lat, lon, m) - level
                        if ((vm >= 0) == rising) b = m else a = m
                    }
                    return b
                }
                prev = v
                t = next
            }
            return null
        }
        return SunDay(
            dawn = cross(-6.0, true), sunrise = cross(-0.833, true),
            sunset = cross(-0.833, false), dusk = cross(-6.0, false), goldenFrom = cross(6.0, false),
        )
    }
}

data class ColdNight(val low: Double, val at: Long, val frost: Boolean) {
    fun text(time: String): String =
        (if (frost) "Frost likely tonight" else "Cold night") + ", low ${low.roundToInt()}° around $time"
}

/** Tonight's low and frost risk, from [now] to 10 am tomorrow. */
object Frost {
    fun tonight(hours: List<HourPoint>, now: Long, zone: ZoneId, threshold: Double): ColdNight? {
        val local = Instant.ofEpochSecond(now).atZone(zone)
        val untilDay = if (local.hour < 10) local.toLocalDate() else local.toLocalDate().plusDays(1)
        val until = untilDay.atTime(10, 0).atZone(zone).toEpochSecond()
        val night = hours.filter { it.time in now..until && it.temperature != null }
        val coldest = night.minByOrNull { it.temperature!! } ?: return null
        val low = coldest.temperature!!
        if (low > threshold) return null
        // Ground frost forms a few degrees above zero air temperature when the sky is clear and the air is still.
        val near = night.filter { kotlin.math.abs(it.time - coldest.time) <= 3 * 3600 }
        val clear = near.mapNotNull { it.cloudCover }.average().let { it.isNaN() || it < 30 }
        val calm = near.mapNotNull { it.windSpeed }.average().let { it.isNaN() || it < 10 }
        val frost = low <= 0.5 || (low <= 3.0 && clear && calm)
        return ColdNight(low, coldest.time, frost)
    }
}
