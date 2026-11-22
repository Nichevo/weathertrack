package app.weathertrack.core.cloud

/**
 * Sun and cloud from the cloud layers. Thin high cloud barely dims the sun, so "sunny" looks only at
 * low and mid cloud, which block it.
 */
object Sky {
    /** Below this share of low + mid cloud an hour counts as sunny. */
    const val SUNNY_MAX = 40.0

    /** Share of the sky (0..100) covered by low or mid cloud, counting overlap once. Null if neither is known. */
    fun blocking(low: Double?, mid: Double?): Double? {
        if (low == null && mid == null) return null
        val l = (low ?: 0.0).coerceIn(0.0, 100.0) / 100
        val m = (mid ?: 0.0).coerceIn(0.0, 100.0) / 100
        return 100 * (1 - (1 - l) * (1 - m))
    }

    fun sunny(low: Double?, mid: Double?): Boolean? = blocking(low, mid)?.let { it < SUNNY_MAX }

    /**
     * Replaces a clear-to-overcast weather code (0..3, which Open-Meteo bases on total cloud) with one
     * based on low and mid cloud only. Rain, fog and storms keep their code.
     */
    fun skyCode(code: Int?, low: Double?, mid: Double?): Int? {
        if (code == null || code !in 0..3) return code
        val b = blocking(low, mid) ?: return code
        return codeFor(b)
    }

    fun codeFor(blocking: Double): Int = when {
        blocking < 15 -> 0
        blocking < SUNNY_MAX -> 1
        blocking < 75 -> 2
        else -> 3
    }
}
