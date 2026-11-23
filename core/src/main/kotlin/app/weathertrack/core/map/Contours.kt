package app.weathertrack.core.map

/** Contour lines (marching squares) and pressure centres, for drawing isobars. */
object Contours {
    /** A straight piece of contour line, in grid cell units (x across, y down). */
    data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

    /**
     * Pieces of the line where [values] (row by row, [w] wide and [h] high) crosses [level].
     * Cells with a missing (NaN) corner are skipped.
     */
    fun lines(values: DoubleArray, w: Int, h: Int, level: Double): List<Segment> {
        val out = ArrayList<Segment>()
        for (y in 0 until h - 1) for (x in 0 until w - 1) {
            val tl = values[y * w + x]
            val tr = values[y * w + x + 1]
            val br = values[(y + 1) * w + x + 1]
            val bl = values[(y + 1) * w + x]
            if (tl.isNaN() || tr.isNaN() || br.isNaN() || bl.isNaN()) continue
            val case = (if (tl >= level) 8 else 0) or (if (tr >= level) 4 else 0) or (if (br >= level) 2 else 0) or (if (bl >= level) 1 else 0)
            if (case == 0 || case == 15) continue
            fun f(a: Double, b: Double) = if (b == a) 0.5 else ((level - a) / (b - a)).coerceIn(0.0, 1.0)
            // Where the line crosses each edge of the cell.
            val top = x + f(tl, tr) to y.toDouble()
            val right = (x + 1).toDouble() to y + f(tr, br)
            val bottom = x + f(bl, br) to (y + 1).toDouble()
            val left = x.toDouble() to y + f(tl, bl)
            fun seg(a: Pair<Double, Double>, b: Pair<Double, Double>) = out.add(Segment(a.first, a.second, b.first, b.second))
            val centreHigh = (tl + tr + br + bl) / 4 >= level
            when (case) {
                1, 14 -> seg(left, bottom)
                2, 13 -> seg(bottom, right)
                3, 12 -> seg(left, right)
                4, 11 -> seg(top, right)
                6, 9 -> seg(top, bottom)
                7, 8 -> seg(left, top)
                5 -> if (centreHigh) { seg(left, top); seg(bottom, right) } else { seg(left, bottom); seg(top, right) }
                10 -> if (centreHigh) { seg(left, bottom); seg(top, right) } else { seg(left, top); seg(bottom, right) }
            }
        }
        return out
    }

    /** A high or low on the pressure map. */
    data class Centre(val lat: Double, val lon: Double, val high: Boolean, val hPa: Double)

    /**
     * Highs and lows in [grid]'s [name] values for hour [hour]: inner points higher (or lower) than
     * all eight neighbours by at least [margin].
     */
    fun centres(grid: RainGrid, hour: Int, name: String = "pressure_msl", margin: Double = 0.5): List<Centre> {
        val out = ArrayList<Centre>()
        for (r in 1 until grid.rows - 1) for (c in 1 until grid.cols - 1) {
            val v = grid.fieldAtPoint(name, r, c, hour) ?: continue
            val around = (-1..1).flatMap { dr -> (-1..1).map { dc -> dr to dc } }.filter { it != 0 to 0 }
                .mapNotNull { (dr, dc) -> grid.fieldAtPoint(name, r + dr, c + dc, hour) }
            if (around.size < 8) continue
            when {
                around.all { v >= it + margin } -> out += Centre(grid.lat(r), grid.lon(c), true, v)
                around.all { v <= it - margin } -> out += Centre(grid.lat(r), grid.lon(c), false, v)
            }
        }
        return out
    }
}
