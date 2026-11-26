package app.weathertrack.map

import android.content.Context
import app.weathertrack.core.map.TimeZoneShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The bundled time zone boundaries (assets/timezones.bin), read once on first use. Nothing is downloaded. */
class TimeZoneMap(private val context: Context) {
    private val lock = Mutex()
    @Volatile private var shapes: TimeZoneShapes? = null

    suspend fun shapes(): TimeZoneShapes = shapes ?: lock.withLock {
        shapes ?: withContext(Dispatchers.IO) {
            context.assets.open(ASSET).use { TimeZoneShapes.read(it) }
        }.also { shapes = it }
    }

    companion object {
        const val ASSET = "timezones.bin"
    }
}
