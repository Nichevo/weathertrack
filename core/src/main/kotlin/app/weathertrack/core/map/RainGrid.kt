package app.weathertrack.core.map

import kotlin.math.cos
import kotlin.math.floor

/**
 * Forecast rain on a regular latitude/longitude grid, from one Open-Meteo multi-location request.
 * Points are row by row from north-west: index = row * cols + col.
 */
data class RainGrid(
    val north: Double,
    val west: Double,
    val south: Double,
    val east: Double,
    val rows: Int,
    val cols: Int,
    /** 15-minute step start times, epoch seconds. */
    val times: List<Long>,
    /** mm in each 15-minute step: [point][time]. */
    val rainMm: List<List<Double?>>,
    /** Hourly step start times, epoch seconds. */
    val hourTimes: List<Long>,
    /** WMO weather code per hour: [point][hour]. */
    val weatherCodes: List<List<Int?>>,
    /** Other hourly values by Open-Meteo name (such as "cloud_cover" or "pressure_msl"): [point][hour]. */
    val fields: Map<String, List<List<Double?>>> = emptyMap(),
) {
    fun lat(row: Int) = north - (north - south) * row / (rows - 1).coerceAtLeast(1)
    fun lon(col: Int) = west + (east - west) * col / (cols - 1).coerceAtLeast(1)

    /** Index of the 15-minute step that contains [epoch], or null if outside the grid's times. */
    fun timeIndex(epoch: Long): Int? {
        val i = times.indexOfLast { it <= epoch }
        return if (i < 0 || epoch >= times[i] + 900) null else i
    }

    /** Rain rate in mm/h at ([lat], [lon]) for step [t], interpolated between the four nearest points. */
    fun rateAt(lat: Double, lon: Double, t: Int): Double? =
        interpolate(lat, lon) { p -> rainMm.getOrNull(p)?.getOrNull(t) }?.let { it * 4.0 }

    /** Index of the hour that contains [epoch], or null if outside the grid's hours. */
    fun hourIndex(epoch: Long): Int? {
        val i = hourTimes.indexOfLast { it <= epoch }
        return if (i < 0 || epoch >= hourTimes[i] + 3600) null else i
    }

    /** Index of the hour nearest [epoch], or null if there are no hours. */
    fun nearestHour(epoch: Long): Int? = hourTimes.indices.minByOrNull { kotlin.math.abs(hourTimes[it] - epoch) }

    /** Hourly [name] at ([lat], [lon]) for hour [h], interpolated between the four nearest points. */
    fun fieldAt(name: String, lat: Double, lon: Double, h: Int): Double? {
        val f = fields[name] ?: return null
        return interpolate(lat, lon) { p -> f.getOrNull(p)?.getOrNull(h) }
    }

    /** [name] at grid point [row], [col] for hour [h]. */
    fun fieldAtPoint(name: String, row: Int, col: Int, h: Int): Double? = fields[name]?.getOrNull(row * cols + col)?.getOrNull(h)

    /**
     * Wind at ([lat], [lon]) for hour [h]: speed interpolated as a number, direction (degrees it
     * blows from) from the interpolated east and north parts, so 350° and 10° average to 0°, not 180°.
     */
    fun windAt(lat: Double, lon: Double, h: Int, speedName: String = "wind_speed_10m"): Wind? {
        val speed = fieldAt(speedName, lat, lon, h) ?: return null
        val s = fields["wind_speed_10m"] ?: return null
        val d = fields["wind_direction_10m"] ?: return null
        fun part(p: Int, east: Boolean): Double? {
            val sp = s.getOrNull(p)?.getOrNull(h) ?: return null
            val dir = Math.toRadians(d.getOrNull(p)?.getOrNull(h) ?: return null)
            return if (east) -sp * kotlin.math.sin(dir) else -sp * kotlin.math.cos(dir)
        }
        val u = interpolate(lat, lon) { part(it, true) } ?: return null
        val v = interpolate(lat, lon) { part(it, false) } ?: return null
        val from = (Math.toDegrees(kotlin.math.atan2(-u, -v)) + 360.0) % 360.0
        return Wind(speed, from)
    }

    /** Bilinear interpolation of a per-point value; a missing corner takes the first corner's value. */
    private inline fun interpolate(lat: Double, lon: Double, value: (Int) -> Double?): Double? {
        val fr = (north - lat) / (north - south) * (rows - 1)
        val fc = (lon - west) / (east - west) * (cols - 1)
        if (fr < 0 || fc < 0 || fr > rows - 1 || fc > cols - 1) return null
        val r0 = floor(fr).toInt().coerceAtMost(rows - 2).coerceAtLeast(0)
        val c0 = floor(fc).toInt().coerceAtMost(cols - 2).coerceAtLeast(0)
        val dr = fr - r0
        val dc = fc - c0
        val a = value(r0 * cols + c0) ?: return null
        val b = value(r0 * cols + c0 + 1) ?: a
        val c = value((r0 + 1) * cols + c0) ?: a
        val d = value((r0 + 1) * cols + c0 + 1) ?: a
        val top = a + (b - a) * dc
        val bottom = c + (d - c) * dc
        return top + (bottom - top) * dr
    }

    /** Points where the forecast has a thunderstorm (codes 95 to 99) in the hour containing [epoch]. */
    fun thunderPoints(epoch: Long): List<Pair<Double, Double>> {
        val h = hourTimes.indexOfLast { it <= epoch }.takeIf { it >= 0 } ?: return emptyList()
        return (0 until rows * cols).filter { (weatherCodes.getOrNull(it)?.getOrNull(h) ?: 0) >= 95 }
            .map { lat(it / cols) to lon(it % cols) }
    }

    companion object {
        /**
         * Grid points around a centre: [n]×[n] points spanning [halfWidthKm] each way (east-west),
         * with the same distance north-south.
         */
        fun points(centerLat: Double, centerLon: Double, halfWidthKm: Double, n: Int): Bounds {
            val dLat = halfWidthKm / 111.0
            val dLon = halfWidthKm / (111.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.1))
            return Bounds(centerLat + dLat, centerLon - dLon, centerLat - dLat, centerLon + dLon, n, n)
        }
    }

    data class Bounds(val north: Double, val west: Double, val south: Double, val east: Double, val rows: Int, val cols: Int) {
        val lats: List<Double> get() = (0 until rows).flatMap { r -> List(cols) { north - (north - south) * r / (rows - 1) } }
        val lons: List<Double> get() = (0 until rows).flatMap { _ -> (0 until cols).map { c -> west + (east - west) * c / (cols - 1) } }
    }
}

/** Wind speed (km/h) and the direction it blows from (degrees, 0 = from the north). */
data class Wind(val speed: Double, val fromDeg: Double) {
    /** Compass name of where it blows from, such as "SW". */
    val compass: String get() = COMPASS[((fromDeg + 22.5) / 45.0).toInt().mod(8)]

    companion object {
        private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    }
}

/** Rain intensity bands used for colours and labels, by rate in mm/h. */
enum class RainBand(val minRate: Double, val label: String) {
    LIGHT(0.1, "Light"), MODERATE(1.0, "Moderate"), HEAVY(4.0, "Heavy"), DOWNPOUR(10.0, "Downpour");

    companion object {
        fun of(rate: Double?): RainBand? = rate?.let { r -> entries.lastOrNull { r >= it.minRate } }
    }
}
