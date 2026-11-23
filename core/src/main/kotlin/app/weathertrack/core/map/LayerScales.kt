package app.weathertrack.core.map

/** A colour scale: colours (ARGB) at values, blended in between. */
class ColorScale(val stops: List<Pair<Double, Int>>) {
    val min: Double get() = stops.first().first
    val max: Double get() = stops.last().first

    fun colorAt(value: Double): Int {
        if (value <= stops.first().first) return stops.first().second
        if (value >= stops.last().first) return stops.last().second
        val i = stops.indexOfFirst { it.first > value }
        val (v0, c0) = stops[i - 1]
        val (v1, c1) = stops[i]
        val t = (value - v0) / (v1 - v0)
        fun ch(c: Int, s: Int) = (c ushr s) and 0xFF
        fun mix(s: Int) = (ch(c0, s) + (ch(c1, s) - ch(c0, s)) * t).toInt().coerceIn(0, 255)
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

/** Colour scales for the map's weather layers. Values are in the units Open-Meteo returns. */
object LayerScales {
    private fun c(alpha: Int, rgb: Int) = (alpha shl 24) or rgb

    /** Cloud cover %: clear is see-through, full cover a solid slate grey. */
    val CLOUD = ColorScale(listOf(0.0 to c(0, 0x8FA1B5), 20.0 to c(40, 0x8FA1B5), 100.0 to c(190, 0x3E4A5A)))

    /** Temperature °C. */
    val TEMP = ColorScale(listOf(
        -5.0 to c(160, 0x5B4FA8), 0.0 to c(160, 0x3F6FB6), 7.0 to c(160, 0x86B8E4), 14.0 to c(160, 0xCDE7B0),
        20.0 to c(160, 0xF5DC6A), 27.0 to c(160, 0xF0974A), 35.0 to c(160, 0xC9433B), 40.0 to c(160, 0x8E2A5A),
    ))

    /** Wind speed km/h. */
    val WIND = ColorScale(listOf(
        0.0 to c(170, 0xBFD9EE), 20.0 to c(170, 0x8CCB9E), 40.0 to c(170, 0xE8E07A), 60.0 to c(170, 0xF2A34F),
        80.0 to c(170, 0xD9534A), 100.0 to c(170, 0x8E3A8A),
    ))

    /** UV index. */
    val UV = ColorScale(listOf(
        0.0 to c(150, 0x8CCB6A), 3.0 to c(150, 0xF2DE5A), 6.0 to c(150, 0xF2A34F), 8.0 to c(150, 0xD9534A), 11.0 to c(150, 0x9B4FA8),
    ))

    /** Relative humidity %. */
    val HUMIDITY = ColorScale(listOf(30.0 to c(150, 0xF3E3C3), 65.0 to c(150, 0x9FD0C8), 100.0 to c(150, 0x2C6E8F)))

    /** Visibility in metres: fog is a thick grey veil, clear air nothing. */
    val VISIBILITY = ColorScale(listOf(
        0.0 to c(220, 0x6E7889), 1000.0 to c(170, 0x7B8794), 5000.0 to c(70, 0x8A94A6), 10000.0 to c(0, 0x8A94A6),
    ))
}
