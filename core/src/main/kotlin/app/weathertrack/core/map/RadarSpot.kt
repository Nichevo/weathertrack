package app.weathertrack.core.map

import kotlin.math.floor

/** What the latest radar frame shows at one spot. */
data class RadarSpot(
    /** Radar frame time, epoch seconds. */
    val time: Long,
    /** Share of radar pixels within about 1.5 km that show rain (0..1). */
    val here: Double,
    /** Share of radar pixels within about 10 km that show rain (0..1). */
    val nearby: Double,
) {
    val wetHere: Boolean get() = here >= WET_SHARE
    val wetNearby: Boolean get() = nearby >= NEARBY_SHARE

    companion object {
        const val WET_SHARE = 0.3
        const val NEARBY_SHARE = 0.05
    }
}

object RadarSpots {
    /** RainViewer tile size used for spot checks. */
    const val TILE_PX = 512
    const val HERE_PX = 3
    const val NEARBY_PX = 20

    /** Radar tile (at [RainViewer.MAX_ZOOM]) holding [lat]/[lon], and the pixel inside it. */
    fun locate(lat: Double, lon: Double): Pair<TileMath.Tile, Pair<Int, Int>> {
        val z = RainViewer.MAX_ZOOM
        val wx = TileMath.x(lon, z)
        val wy = TileMath.y(lat, z)
        val tx = floor(wx / TileMath.TILE).toInt()
        val ty = floor(wy / TileMath.TILE).toInt()
        val scale = TILE_PX / TileMath.TILE
        val px = ((wx - tx * TileMath.TILE) * scale).toInt().coerceIn(0, TILE_PX - 1)
        val py = ((wy - ty * TileMath.TILE) * scale).toInt().coerceIn(0, TILE_PX - 1)
        return TileMath.Tile(z, TileMath.wrapX(tx, z), ty) to (px to py)
    }

    /**
     * Reads ARGB [pixels] ([width] wide) round ([px], [py]). Any pixel that isn't transparent is a
     * radar echo (RainViewer draws nothing where it is dry).
     */
    fun read(time: Long, pixels: IntArray, width: Int, height: Int, px: Int, py: Int): RadarSpot =
        RadarSpot(time, share(pixels, width, height, px, py, HERE_PX), share(pixels, width, height, px, py, NEARBY_PX))

    private fun share(pixels: IntArray, width: Int, height: Int, px: Int, py: Int, r: Int): Double {
        var wet = 0
        var all = 0
        for (y in (py - r)..(py + r)) {
            if (y !in 0 until height) continue
            for (x in (px - r)..(px + r)) {
                if (x !in 0 until width) continue
                val dx = x - px
                val dy = y - py
                if (dx * dx + dy * dy > r * r) continue
                all++
                if ((pixels[y * width + x] ushr 24) > 32) wet++
            }
        }
        return if (all == 0) 0.0 else wet.toDouble() / all
    }
}
