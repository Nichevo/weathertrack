package app.weathertrack.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Map and radar tile images: memory cache, plus OkHttp's disk cache so the base map works offline
 * for areas already seen. [version] ticks whenever a tile arrives, so the map can redraw.
 */
class TileStore(context: Context) {
    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "tiles"), 80L * 1024 * 1024))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()
    private val loading = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(6)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** The tile if it is in memory; otherwise starts loading it and returns null. */
    fun peek(url: String): Bitmap? {
        memory.get(url)?.let { return it }
        synchronized(this) {
            if (url in missing || !loading.add(url)) return null
        }
        scope.launch {
            load(url)
            synchronized(this@TileStore) { loading.remove(url) }
            _version.value++
        }
        return null
    }

    /** Loads every tile in [urls] (for drawing off screen, such as the widget). */
    suspend fun preload(urls: Collection<String>) = withContext(Dispatchers.IO) {
        urls.filter { memory.get(it) == null }.map { async { load(it) } }.awaitAll()
    }

    private suspend fun load(url: String): Bitmap? = gate.withPermit {
        memory.get(url)?.let { return@withPermit it }
        val bmp = runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "WeatherTrack-Android").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    // 404 means no tile there (for example radar outside coverage): remember it.
                    if (r.code == 404) synchronized(this) { missing += url }
                    null
                } else r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }.getOrNull()
        bmp?.also { memory.put(url, it) }
    }

    companion object {
        private val subdomains = listOf("a", "b", "c", "d")

        /** CARTO Voyager base map (OpenStreetMap data), 512-pixel tiles. */
        fun baseUrl(z: Int, x: Int, y: Int): String =
            "https://${subdomains[(x + y).mod(4)]}.basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y@2x.png"

        const val ATTRIBUTION = "© OpenStreetMap contributors © CARTO · Radar: RainViewer · Forecast: Open-Meteo"
    }
}
