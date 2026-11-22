package app.weathertrack.core.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A plain-words line about rain around a spot at one forecast step. */
data class RainOutlook(val headline: String, val detail: String, val rateHere: Double?)

object RainOutlooks {
    private val compass = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

    fun at(grid: RainGrid, lat: Double, lon: Double, epoch: Long): RainOutlook? {
        val t = grid.timeIndex(epoch) ?: return null
        val here = grid.rateAt(lat, lon, t)
        val band = RainBand.of(here)
        if (band != null) {
            return RainOutlook("${band.label} rain over you", String.format(java.util.Locale.US, "About %.1f mm an hour", here), here)
        }
        // Nearest grid point with rain.
        var best: Triple<Double, Double, Double>? = null
        for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
            val rate = (grid.rainMm.getOrNull(r * grid.cols + c)?.getOrNull(t) ?: 0.0) * 4
            if (RainBand.of(rate) == null) continue
            val km = distanceKm(lat, lon, grid.lat(r), grid.lon(c))
            if (best == null || km < best.first) best = Triple(km, grid.lat(r), grid.lon(c))
        }
        val b = best ?: return RainOutlook("Dry around you", "No rain within about ${halfWidthKm(grid)} km", here)
        val dir = bearing(lat, lon, b.second, b.third)
        return RainOutlook("Dry over you", "Rain about ${b.first.roundToInt()} km to the $dir", here)
    }

    /** First 15-minute step from [fromEpoch] on with rain at the spot, as epoch seconds. */
    fun firstRain(grid: RainGrid, lat: Double, lon: Double, fromEpoch: Long): Long? {
        val start = grid.timeIndex(fromEpoch) ?: return null
        return (start until grid.times.size).firstOrNull { RainBand.of(grid.rateAt(lat, lon, it)) != null }?.let { grid.times[it] }
    }

    private fun halfWidthKm(g: RainGrid) = ((g.north - g.south) / 2 * 111).roundToInt()

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111.0
        val dx = (lon2 - lon1) * 111.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
        val dy = lat2 - lat1
        val dx = (lon2 - lon1) * cos(Math.toRadians(lat1))
        val deg = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
        return compass[((deg + 22.5) / 45).toInt() % 8]
    }
}
