package app.weathertrack.core.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Tropical cyclones from GDACS (the UN and EU's Global Disaster Alert and Coordination System):
 * a list of storms, and for each one its past track, forecast track and forecast cone. Free, no key.
 */
object Gdacs {
    const val EVENTS_URL = "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?eventlist=TC"

    fun geometryUrl(eventId: Long, episodeId: Long) =
        "https://www.gdacs.org/gdacsapi/api/polygons/getgeometry?eventtype=TC&eventid=$eventId&episodeid=$episodeId"

    data class Event(
        val id: Long,
        val episode: Long,
        /** Like "MURASU-26". */
        val code: String,
        /** Green, Orange or Red. */
        val alert: String,
        val lat: Double,
        val lon: Double,
        val maxWindKmh: Double?,
        val current: Boolean,
    ) {
        /** "Murasu" from "MURASU-26". */
        val name: String get() = code.substringBeforeLast('-').lowercase().replaceFirstChar { it.uppercase() }
    }

    /** One leg of the track. [stage] is GDACS's storm class, such as TD, TS or HU. */
    data class Leg(val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double, val forecast: Boolean, val stage: String)

    data class Track(val legs: List<Leg>, val cone: List<Pair<Double, Double>>, val points: List<Pair<Double, Double>>)

    private val json = Json { ignoreUnknownKeys = true }

    fun parseEvents(body: String): List<Event> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        return (root["features"] as? JsonArray).orEmpty().mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val p = o["properties"] as? JsonObject ?: return@mapNotNull null
            val coords = ((o["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray) ?: return@mapNotNull null
            val lon = coords.getOrNull(0)?.num() ?: return@mapNotNull null
            val lat = coords.getOrNull(1)?.num() ?: return@mapNotNull null
            Event(
                id = p.long("eventid") ?: return@mapNotNull null,
                episode = p.long("episodeid") ?: return@mapNotNull null,
                code = p.str("eventname") ?: "Unnamed",
                alert = p.str("alertlevel") ?: "Green",
                lat = lat, lon = lon,
                maxWindKmh = (p["severitydata"] as? JsonObject)?.get("severity")?.num(),
                current = p.str("iscurrent").equals("true", ignoreCase = true),
            )
        }
    }

    fun parseTrack(body: String): Track {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return Track(emptyList(), emptyList(), emptyList())
        val legs = ArrayList<Leg>()
        var cone = emptyList<Pair<Double, Double>>()
        val points = ArrayList<Pair<Double, Double>>()
        for (f in (root["features"] as? JsonArray).orEmpty()) {
            val o = f as? JsonObject ?: continue
            val p = o["properties"] as? JsonObject ?: continue
            val cls = p.str("Class") ?: continue
            val coords = (o["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: continue
            when {
                cls.startsWith("Line_Line") -> {
                    val pts = coords.mapNotNull { it.lonLat() }
                    for (i in 1 until pts.size) {
                        legs += Leg(
                            pts[i - 1].second, pts[i - 1].first, pts[i].second, pts[i].first,
                            forecast = (p["forecast"] as? JsonPrimitive)?.booleanOrNull ?: false,
                            stage = p.str("polygonlabel") ?: "",
                        )
                    }
                }
                cls == "Poly_Cones" -> cone = ring(coords).map { it.second to it.first }
                cls.startsWith("Point_Polygon_Point") -> {
                    val r = ring(coords)
                    if (r.isNotEmpty()) points += r.map { it.second }.average() to r.map { it.first }.average()
                }
            }
        }
        return Track(legs, cone, points)
    }

    /** First ring of a polygon as (lon, lat) pairs. */
    private fun ring(coords: JsonArray): List<Pair<Double, Double>> =
        (coords.getOrNull(0) as? JsonArray).orEmpty().mapNotNull { it.lonLat() }

    private fun JsonElement.lonLat(): Pair<Double, Double>? {
        val a = this as? JsonArray ?: return null
        return (a.getOrNull(0)?.num() ?: return null) to (a.getOrNull(1)?.num() ?: return null)
    }

    private fun JsonElement.num(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
}
