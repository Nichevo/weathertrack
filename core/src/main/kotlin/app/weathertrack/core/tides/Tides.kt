package app.weathertrack.core.tides

/** Sea level (metres above mean sea level, tides included) at the start of an hour. */
data class TidePoint(val time: Long, val height: Double)

/** A high or low tide. [time] is interpolated between hours. */
data class TideEvent(val time: Long, val height: Double, val high: Boolean)

data class TideSeries(
    val points: List<TidePoint>,
    /** Where the sea level comes from (the nearest sea grid cell). */
    val latitude: Double,
    val longitude: Double,
) {
    val events: List<TideEvent> by lazy { Tides.extremes(points) }
}

data class TideNow(val height: Double, val rising: Boolean, val next: TideEvent?, val after: TideEvent?)

object Tides {
    /** Highs and lows: local turning points, refined with a parabola through the three hours around each. */
    fun extremes(points: List<TidePoint>): List<TideEvent> {
        val p = points.sortedBy { it.time }
        val out = mutableListOf<TideEvent>()
        for (i in 1 until p.size - 1) {
            val a = p[i - 1].height
            val b = p[i].height
            val c = p[i + 1].height
            val high = b > a && b >= c
            val low = b < a && b <= c
            if (!high && !low) continue
            val denom = a - 2 * b + c
            val offset = if (denom == 0.0) 0.0 else (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
            val step = (p[i + 1].time - p[i - 1].time) / 2.0
            val t = p[i].time + (offset * step).toLong()
            val h = b - 0.25 * (a - c) * offset
            out += TideEvent(t, h, high)
        }
        return out
    }

    /** Height now (linear between hours), whether it is rising, and the next two turning points. */
    fun now(series: TideSeries, now: Long): TideNow? {
        val p = series.points.sortedBy { it.time }
        val i = p.indexOfLast { it.time <= now }
        if (i < 0 || i >= p.size - 1) return null
        val f = (now - p[i].time).toDouble() / (p[i + 1].time - p[i].time)
        val h = p[i].height + (p[i + 1].height - p[i].height) * f
        val upcoming = series.events.filter { it.time > now }
        val next = upcoming.firstOrNull()
        return TideNow(h, next?.high ?: (p[i + 1].height > p[i].height), next, upcoming.getOrNull(1))
    }
}
