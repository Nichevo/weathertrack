package app.weathertrack.core.tides

import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos

/**
 * Official New Zealand tide predictions from Land Information New Zealand (LINZ), CC BY 3.0 NZ.
 * One CSV per port per year: day, weekday, month, year, then up to four time/height pairs in local
 * clock time (NZST or NZDT) and metres above chart datum.
 */
object Linz {
    data class Port(val name: String, val latitude: Double, val longitude: Double)

    val ZONE: ZoneId = ZoneId.of("Pacific/Auckland")
    const val ATTRIBUTION = "Tide predictions from Land Information New Zealand (LINZ), CC BY 3.0 NZ"
    /** A port further than this from the place isn't used. */
    const val MAX_KM = 40.0

    /** Ports with their own yearly CSV (positions rounded; only used to pick the nearest). */
    val PORTS = listOf(
        Port("Auckland", -36.843, 174.767),
        Port("Onehunga", -36.927, 174.786),
        Port("Marsden Point", -35.833, 174.500),
        Port("Tauranga", -37.650, 176.183),
        Port("Gisborne", -38.673, 178.025),
        Port("Napier", -39.475, 176.917),
        Port("Port Taranaki", -39.058, 174.035),
        Port("Wellington", -41.285, 174.780),
        Port("Picton", -41.278, 174.003),
        Port("Nelson", -41.260, 173.270),
        Port("Westport", -41.737, 171.600),
        Port("Lyttelton", -43.607, 172.720),
        Port("Timaru", -44.390, 171.255),
        Port("Dunedin", -45.880, 170.510),
        Port("Bluff", -46.600, 168.345),
    )

    /**
     * The nearest port within [MAX_KM]. Onehunga is on the Manukau Harbour, whose tides run hours
     * apart from the Waitematā's, so it is only picked for places west of the isthmus.
     */
    fun nearest(lat: Double, lon: Double): Pair<Port, Double>? =
        PORTS.filter { it.name != "Onehunga" || lon < 174.74 }
            .map { it to km(lat, lon, it.latitude, it.longitude) }.minByOrNull { it.second }?.takeIf { it.second <= MAX_KM }

    fun byName(name: String?): Port? = PORTS.firstOrNull { it.name == name }

    fun distanceKm(lat: Double, lon: Double, port: Port) = km(lat, lon, port.latitude, port.longitude)

    fun csvUrl(port: Port, year: Int): String =
        "https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/" +
            URLEncoder.encode("${port.name} $year", "UTF-8").replace("+", "%20") + ".csv"

    /** Highs and lows from one port-year CSV. The higher of two neighbouring tides is the high. */
    fun parse(csv: String, zone: ZoneId = ZONE): List<TideEvent> {
        val raw = mutableListOf<Pair<Long, Double>>()
        for (line in csv.lineSequence()) {
            val f = line.split(",").map { it.trim() }
            if (f.size < 6) continue
            val day = f[0].toIntOrNull() ?: continue
            val month = f[2].toIntOrNull() ?: continue
            val year = f[3].toIntOrNull() ?: continue
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            var i = 4
            while (i + 1 < f.size) {
                val t = f[i].takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
                val h = f[i + 1].toDoubleOrNull()
                if (t != null && h != null) raw += date.atTime(t).atZone(zone).toEpochSecond() to h
                i += 2
            }
        }
        val sorted = raw.sortedBy { it.first }
        return sorted.mapIndexed { k, (t, h) ->
            val neighbour = listOfNotNull(sorted.getOrNull(k - 1)?.second, sorted.getOrNull(k + 1)?.second)
            TideEvent(t, h, neighbour.isEmpty() || neighbour.all { h > it })
        }
    }

    /**
     * A curve through the published highs and lows (half a cosine between each pair, the usual
     * tide-table method), sampled every [stepS] seconds from [from] to [to].
     */
    fun curve(events: List<TideEvent>, from: Long, to: Long, stepS: Long = 1800): List<TidePoint> {
        val e = events.sortedBy { it.time }
        val out = mutableListOf<TidePoint>()
        var t = from - Math.floorMod(from, stepS)
        var k = 0
        while (t <= to) {
            while (k + 1 < e.size && e[k + 1].time <= t) k++
            if (k + 1 < e.size && e[k].time <= t) {
                val a = e[k]; val b = e[k + 1]
                val f = (t - a.time).toDouble() / (b.time - a.time)
                out += TidePoint(t, a.height + (b.height - a.height) * (1 - cos(PI * f)) / 2)
            }
            t += stepS
        }
        return out
    }

    fun series(port: Port, events: List<TideEvent>, from: Long, to: Long): TideSeries? {
        val pts = curve(events, from, to)
        if (pts.size < 4) return null
        return TideSeries(pts, port.latitude, port.longitude, official = events.filter { it.time in from..to }, port = port.name)
    }

    private fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
