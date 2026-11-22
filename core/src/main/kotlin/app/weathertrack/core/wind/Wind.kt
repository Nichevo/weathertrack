package app.weathertrack.core.wind

import kotlin.math.roundToInt

/** Wind direction helpers. Directions are where the wind comes FROM, in degrees (0 = from the north). */
object Wind {
    private val POINTS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    private val POINTS8 = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    /** Arrows pointing the way the wind blows (downwind), for a wind from N, NE, E, ... */
    private val ARROWS = listOf("↓", "↙", "←", "↖", "↑", "↗", "→", "↘")

    private fun norm(deg: Double) = ((deg % 360) + 360) % 360

    /** 16-point compass name, e.g. 225° -> "SW". */
    fun compass(deg: Double?): String? = deg?.let { POINTS[(norm(it) / 22.5).roundToInt() % 16] }

    /** 8-point compass name, for tight spaces. */
    fun compass8(deg: Double?): String? = deg?.let { POINTS8[(norm(it) / 45).roundToInt() % 8] }

    /** An arrow showing which way the wind is blowing, e.g. a southwesterly (from 225°) gives "↗". */
    fun arrow(deg: Double?): String? = deg?.let { ARROWS[(norm(it) / 45).roundToInt() % 8] }

    /** Plain words for a wind speed in km/h (Beaufort-style). */
    fun describe(kmh: Double?): String? = kmh?.let {
        when {
            it < 6 -> "calm"
            it < 20 -> "light"
            it < 30 -> "moderate"
            it < 40 -> "fresh"
            it < 62 -> "strong"
            else -> "gale"
        }
    }

    /** "SW 18 km/h, gusts 35" style summary. */
    fun summary(speed: Double?, gusts: Double?, deg: Double?): String {
        val dir = compass(deg)?.let { "$it " } ?: ""
        val s = speed?.let { "${it.roundToInt()} km/h" } ?: "–"
        val g = gusts?.let { ", gusts ${it.roundToInt()}" } ?: ""
        return "$dir$s$g"
    }
}
