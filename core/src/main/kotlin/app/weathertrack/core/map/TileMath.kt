package app.weathertrack.core.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** Web Mercator maths for 256-unit tiles ("world pixels" at a zoom level). */
object TileMath {
    const val TILE = 256.0

    fun worldSize(zoom: Int): Double = TILE * 2.0.pow(zoom)

    fun x(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * worldSize(zoom)

    fun y(lat: Double, zoom: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldSize(zoom)
    }

    fun lon(x: Double, zoom: Int): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun lat(y: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / worldSize(zoom)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    data class Tile(val z: Int, val x: Int, val y: Int)

    /** Tiles covering a view [width]×[height] world pixels centred on ([cx], [cy]). x wraps round the globe. */
    fun tilesFor(cx: Double, cy: Double, zoom: Int, width: Double, height: Double): List<Tile> {
        val n = 1 shl zoom
        val x0 = floor((cx - width / 2) / TILE).toInt()
        val x1 = floor((cx + width / 2) / TILE).toInt()
        val y0 = floor((cy - height / 2) / TILE).toInt().coerceAtLeast(0)
        val y1 = floor((cy + height / 2) / TILE).toInt().coerceAtMost(n - 1)
        return (y0..y1).flatMap { ty -> (x0..x1).map { tx -> Tile(zoom, tx, ty) } }
    }

    /** Wraps a tile x index into 0 until 2^zoom. */
    fun wrapX(x: Int, zoom: Int): Int {
        val n = 1 shl zoom
        return ((x % n) + n) % n
    }

    /** Approximate km across [widthPx] world pixels at [lat]. */
    fun kmAcross(widthPx: Double, lat: Double, zoom: Int): Double =
        widthPx * 40075.0 * cos(Math.toRadians(lat)) / worldSize(zoom)
}
