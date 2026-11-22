package app.weathertrack.core.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** RainViewer's public radar API: a list of recent radar frames and how to build their tile URLs. */
object RainViewer {
    const val MAPS_URL = "https://api.rainviewer.com/public/weather-maps.json"
    /** RainViewer's free tiles stop at this zoom; the app enlarges them beyond it. */
    const val MAX_ZOOM = 7
    /** Colour scheme 2 ("universal blue"), smoothed, no snow colours. */
    private const val OPTIONS = "2/1_0"

    data class Frame(val time: Long, val path: String)
    data class Frames(val host: String, val past: List<Frame>)

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): Frames {
        val root = json.parseToJsonElement(body).jsonObject
        val host = (root["host"] as? JsonPrimitive)?.contentOrNull ?: "https://tilecache.rainviewer.com"
        val past = ((root["radar"] as? JsonObject)?.get("past") as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val time = (o["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Frame(time, path)
        }.sortedBy { it.time }
        return Frames(host, past)
    }

    /** Unsmoothed 512-pixel tile for [frame], for reading the radar at a spot. */
    fun rawTileUrl(host: String, frame: Frame, z: Int, x: Int, y: Int): String =
        "$host${frame.path}/512/$z/$x/$y/2/0_0.png"

    /** 512-pixel tile for [frame]. [z] must be at most [MAX_ZOOM]. */
    fun tileUrl(host: String, frame: Frame, z: Int, x: Int, y: Int): String =
        "$host${frame.path}/512/$z/$x/$y/$OPTIONS.png"
}
