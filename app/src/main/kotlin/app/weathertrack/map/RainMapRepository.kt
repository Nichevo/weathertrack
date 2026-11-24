package app.weathertrack.map

import app.weathertrack.core.map.Gdacs
import app.weathertrack.core.map.Gibs
import app.weathertrack.core.map.RainGrid
import app.weathertrack.core.map.RainViewer
import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.openmeteo.OpenMeteoUrls
import app.weathertrack.core.util.runCatchingCancellable
import app.weathertrack.data.WeatherRepository
import app.weathertrack.data.db.RawPullDao
import app.weathertrack.data.db.RawPullEntity
import app.weathertrack.data.net.HttpFetcher
import java.io.IOException

/**
 * Radar frame lists from RainViewer, forecast grids from Open-Meteo (rain, and the hourly weather
 * layers), satellite image times from NASA GIBS and tropical cyclones from GDACS. All are stored
 * raw like every other pull, and kept in memory for a few minutes so panning doesn't refetch.
 */
class RainMapRepository(private val http: HttpFetcher, private val rawDao: RawPullDao) {
    private var frames: Pair<Long, RainViewer.Frames>? = null
    private val grids = LinkedHashMap<Pair<RainGrid.Bounds, Boolean>, Pair<Long, RainGrid>>()
    private val pressureGrids = LinkedHashMap<RainGrid.Bounds, Pair<Long, RainGrid>>()
    private var satellite: Pair<Long, Long>? = null
    private var storms: Pair<Long, List<Cyclone>>? = null

    /** A tropical cyclone that is active now, with its track (empty if the track didn't load). */
    data class Cyclone(val event: Gdacs.Event, val track: Gdacs.Track)

    suspend fun radarFrames(trigger: String = "map"): RainViewer.Frames {
        frames?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 }?.let { return it.second }
        val r = http.get(RainViewer.MAPS_URL)
        rawDao.insert(raw("rainviewer", "weather-maps", null, null, r.url, r.fetchedAtMs, r.status, r.durationMs, trigger, r.body))
        if (r.status != 200) throw IOException("Radar list returned HTTP ${r.status}")
        return RainViewer.parse(r.body).also { frames = System.currentTimeMillis() to it }
    }

    /**
     * Forecast rain on an [n]×[n] grid spanning [halfWidthKm] each way from the centre. With [layers],
     * the same request also brings 25 hours of cloud, wind, temperature, UV, humidity and visibility.
     */
    suspend fun grid(
        centerLat: Double, centerLon: Double, halfWidthKm: Double, n: Int = 7, trigger: String = "map", layers: Boolean = false,
    ): RainGrid {
        // Snap the centre so small pans reuse the same grid.
        val step = halfWidthKm / 111.0 / 4
        val lat = Math.round(centerLat / step) * step
        val lon = Math.round(centerLon / step) * step
        val bounds = RainGrid.points(lat, lon, halfWidthKm, n)
        val key = bounds to layers
        grids[key]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        val r = http.get(OpenMeteoUrls.rainGrid(bounds.lats, bounds.lons, layerHours = if (layers) LAYER_HOURS else 0))
        rawDao.insert(raw(WeatherRepository.SOURCE, if (layers) "layer_grid" else "rain_grid", lat, lon, r.url, r.fetchedAtMs, r.status, r.durationMs, trigger, r.body))
        if (r.status != 200) throw IOException("Forecast returned HTTP ${r.status}")
        val grid = OpenMeteoParser.parseRainGrid(r.body, bounds)
        grids[key] = System.currentTimeMillis() to grid
        while (grids.size > 6) grids.remove(grids.keys.first())
        return grid
    }

    /** Sea-level pressure (and wind) on a wide [n]×[n] grid, every hour for 3 days, for pressure lines. */
    suspend fun pressureGrid(centerLat: Double, centerLon: Double, halfWidthKm: Double, n: Int = 11): RainGrid {
        val step = halfWidthKm / 111.0 / 4
        val lat = Math.round(centerLat / step) * step
        val lon = Math.round(centerLon / step) * step
        val bounds = RainGrid.points(lat.coerceIn(-70.0, 70.0), lon, halfWidthKm, n)
        pressureGrids[bounds]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.let { return it.second }
        val r = http.get(OpenMeteoUrls.pressureGrid(bounds.lats, bounds.lons))
        rawDao.insert(raw(WeatherRepository.SOURCE, "pressure_grid", lat, lon, r.url, r.fetchedAtMs, r.status, r.durationMs, "map", r.body))
        if (r.status != 200) throw IOException("Pressure returned HTTP ${r.status}")
        val grid = OpenMeteoParser.parseRainGrid(r.body, bounds)
        pressureGrids[bounds] = System.currentTimeMillis() to grid
        while (pressureGrids.size > 4) pressureGrids.remove(pressureGrids.keys.first())
        return grid
    }

    /**
     * Time of the newest satellite image GIBS has (epoch seconds). Tries each 10-minute slot back
     * from now with one small whole-world tile; a missing image answers 404.
     */
    suspend fun satelliteLatest(): Long {
        satellite?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 }?.let { return it.second }
        val now = System.currentTimeMillis() / 1000
        for (t in Gibs.candidates(now)) {
            val r = http.get(Gibs.tileUrl(t, 0, 0, 0))
            if (r.status == 200) {
                rawDao.insert(raw("gibs", "latest_image", null, null, r.url, r.fetchedAtMs, r.status, r.durationMs, "map", "latest=${Gibs.iso(t)}"))
                satellite = System.currentTimeMillis() to t
                return t
            }
            if (r.status != 404 && r.status != 400) {
                rawDao.insert(raw("gibs", "latest_image", null, null, r.url, r.fetchedAtMs, r.status, r.durationMs, "map", r.body.take(2000)))
                throw IOException("Satellite returned HTTP ${r.status}")
            }
        }
        throw IOException("No recent satellite image")
    }

    /** Tropical cyclones active now, anywhere in the world, with their tracks. */
    suspend fun cyclones(): List<Cyclone> {
        storms?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.let { return it.second }
        val r = http.get(Gdacs.EVENTS_URL)
        rawDao.insert(raw("gdacs", "tc_events", null, null, r.url, r.fetchedAtMs, r.status, r.durationMs, "map", r.body))
        if (r.status != 200) throw IOException("Cyclones returned HTTP ${r.status}")
        val list = Gdacs.parseEvents(r.body).filter { it.current }.take(6).map { e ->
            val track = runCatchingCancellable {
                val g = http.get(Gdacs.geometryUrl(e.id, e.episode))
                rawDao.insert(raw("gdacs", "tc_track", e.lat, e.lon, g.url, g.fetchedAtMs, g.status, g.durationMs, "map", g.body))
                if (g.status == 200) Gdacs.parseTrack(g.body) else null
            }.getOrNull() ?: Gdacs.Track(emptyList(), emptyList(), emptyList())
            Cyclone(e, track)
        }
        storms = System.currentTimeMillis() to list
        return list
    }

    private fun raw(
        source: String, endpoint: String, lat: Double?, lon: Double?, url: String, at: Long, status: Int,
        ms: Long, trigger: String, body: String,
    ) = RawPullEntity(
        source = source, endpoint = endpoint, placeId = null, latitude = lat, longitude = lon, url = url,
        fetchedAt = at, httpStatus = status, durationMs = ms, trigger = trigger, body = body,
    )

    companion object {
        /** Hours of weather layers fetched (this hour and the next 24). */
        const val LAYER_HOURS = 25
    }
}
