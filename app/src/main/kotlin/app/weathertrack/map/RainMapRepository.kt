package app.weathertrack.map

import app.weathertrack.core.map.RainGrid
import app.weathertrack.core.map.RainViewer
import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.openmeteo.OpenMeteoUrls
import app.weathertrack.data.WeatherRepository
import app.weathertrack.data.db.RawPullDao
import app.weathertrack.data.db.RawPullEntity
import app.weathertrack.data.net.HttpFetcher
import java.io.IOException

/**
 * Radar frame lists from RainViewer and forecast rain grids from Open-Meteo. Both are stored raw
 * like every other pull, and kept in memory for a few minutes so panning doesn't refetch.
 */
class RainMapRepository(private val http: HttpFetcher, private val rawDao: RawPullDao) {
    private var frames: Pair<Long, RainViewer.Frames>? = null
    private val grids = LinkedHashMap<RainGrid.Bounds, Pair<Long, RainGrid>>()

    suspend fun radarFrames(trigger: String = "map"): RainViewer.Frames {
        frames?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 }?.let { return it.second }
        val r = http.get(RainViewer.MAPS_URL)
        rawDao.insert(raw("rainviewer", "weather-maps", null, null, r.url, r.fetchedAtMs, r.status, r.durationMs, trigger, r.body))
        if (r.status != 200) throw IOException("Radar list returned HTTP ${r.status}")
        return RainViewer.parse(r.body).also { frames = System.currentTimeMillis() to it }
    }

    /** Forecast rain on an [n]×[n] grid spanning [halfWidthKm] each way from the centre. */
    suspend fun grid(centerLat: Double, centerLon: Double, halfWidthKm: Double, n: Int = 7, trigger: String = "map"): RainGrid {
        // Snap the centre so small pans reuse the same grid.
        val step = halfWidthKm / 111.0 / 4
        val lat = Math.round(centerLat / step) * step
        val lon = Math.round(centerLon / step) * step
        val bounds = RainGrid.points(lat, lon, halfWidthKm, n)
        grids[bounds]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        val r = http.get(OpenMeteoUrls.rainGrid(bounds.lats, bounds.lons))
        rawDao.insert(raw(WeatherRepository.SOURCE, "rain_grid", lat, lon, r.url, r.fetchedAtMs, r.status, r.durationMs, trigger, r.body))
        if (r.status != 200) throw IOException("Forecast rain returned HTTP ${r.status}")
        val grid = OpenMeteoParser.parseRainGrid(r.body, bounds)
        grids[bounds] = System.currentTimeMillis() to grid
        while (grids.size > 6) grids.remove(grids.keys.first())
        return grid
    }

    private fun raw(
        source: String, endpoint: String, lat: Double?, lon: Double?, url: String, at: Long, status: Int,
        ms: Long, trigger: String, body: String,
    ) = RawPullEntity(
        source = source, endpoint = endpoint, placeId = null, latitude = lat, longitude = lon, url = url,
        fetchedAt = at, httpStatus = status, durationMs = ms, trigger = trigger, body = body,
    )
}
