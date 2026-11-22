package app.weathertrack.map

import app.weathertrack.core.map.RadarSpot
import app.weathertrack.core.map.RadarSpots
import app.weathertrack.core.map.RainViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the latest RainViewer radar frame at a spot, so "raining now" reflects what radar sees. */
class RadarSpotChecker(private val rainMap: RainMapRepository, private val tiles: TileStore) {
    /** Null when there is no recent radar frame (older than [MAX_AGE_S]) or the tile can't be loaded. */
    suspend fun check(lat: Double, lon: Double): RadarSpot? = withContext(Dispatchers.IO) {
        val frames = runCatching { rainMap.radarFrames("today") }.getOrNull() ?: return@withContext null
        val frame = frames.past.lastOrNull() ?: return@withContext null
        if (System.currentTimeMillis() / 1000 - frame.time > MAX_AGE_S) return@withContext null
        val (t, p) = RadarSpots.locate(lat, lon)
        val url = RainViewer.rawTileUrl(frames.host, frame, t.z, t.x, t.y)
        tiles.preload(listOf(url))
        val bmp = tiles.peek(url) ?: return@withContext null
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        // Tiles normally come back at 512 px; scale the spot if not.
        val px = p.first * w / RadarSpots.TILE_PX
        val py = p.second * h / RadarSpots.TILE_PX
        RadarSpots.read(frame.time, pixels, w, h, px, py)
    }

    companion object {
        const val MAX_AGE_S = 30 * 60L
    }
}
