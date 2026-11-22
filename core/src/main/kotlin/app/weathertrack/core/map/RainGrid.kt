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
) {
    fun lat(row: Int) = north - (north - south) * row / (rows - 1).coerceAtLeast(1)
    fun lon(col: Int) = west + (east - west) * col / (cols - 1).coerceAtLeast(1)

    /** Index of the 15-minute step that contains [epoch], or null if outside the grid's times. */
    fun timeIndex(epoch: Long): Int? {
        val i = times.indexOfLast { it <= epoch }
        return if (i < 0 || epoch >= times[i] + 900) null else i
    }

    /** Rain rate in mm/h at ([lat], [lon]) for step [t], interpolated between the four nearest points. */
    fun rateAt(lat: Double, lon: Double, t: Int): Double? {
        val fr = (north - lat) / (north - south) * (rows - 1)
        val fc = (lon - west) / (east - west) * (cols - 1)
        if (fr < 0 || fc < 0 || fr > rows - 1 || fc > cols - 1) return null
        val r0 = floor(fr).toInt().coerceAtMost(rows - 2).coerceAtLeast(0)
        val c0 = floor(fc).toInt().coerceAtMost(cols - 2).coerceAtLeast(0)
        val dr = fr - r0
        val dc = fc - c0
        fun v(r: Int, c: Int) = rainMm.getOrNull(r * cols + c)?.getOrNull(t)
        val a = v(r0, c0) ?: return null
        val b = v(r0, c0 + 1) ?: a
        val c = v(r0 + 1, c0) ?: a
        val d = v(r0 + 1, c0 + 1) ?: a
        val top = a + (b - a) * dc
        val bottom = c + (d - c) * dc
        return (top + (bottom - top) * dr) * 4.0
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

/** Rain intensity bands used for colours and labels, by rate in mm/h. */
enum class RainBand(val minRate: Double, val label: String) {
    LIGHT(0.1, "Light"), MODERATE(1.0, "Moderate"), HEAVY(4.0, "Heavy"), DOWNPOUR(10.0, "Downpour");

    companion object {
        fun of(rate: Double?): RainBand? = rate?.let { r -> entries.lastOrNull { r >= it.minRate } }
    }
}
