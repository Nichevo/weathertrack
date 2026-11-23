package app.weathertrack.data

import android.content.Context
import android.location.Geocoder
import app.weathertrack.core.model.GeoPlace
import app.weathertrack.data.db.PlaceDao
import app.weathertrack.data.db.PlaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

class PlaceRepository(context: Context, private val dao: PlaceDao) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val _selectedId = MutableStateFlow(prefs.getLong(KEY_SELECTED, -1L))
    val selectedId: StateFlow<Long> = _selectedId

    val places: Flow<List<PlaceEntity>> = dao.observeAll()

    private val _sourcePlaces = MutableStateFlow(readSourcePlaces())
    /** Places whose every forecast source is saved hourly (the selected place always is). */
    val sourcePlaces: StateFlow<Set<Long>> = _sourcePlaces

    private fun readSourcePlaces(): Set<Long> =
        prefs.getStringSet(KEY_SOURCE_PLACES, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun setSaveSources(id: Long, on: Boolean) {
        val next = if (on) _sourcePlaces.value + id else _sourcePlaces.value - id
        prefs.edit().putStringSet(KEY_SOURCE_PLACES, next.map { it.toString() }.toSet()).apply()
        _sourcePlaces.value = next
    }

    fun savesSources(id: Long): Boolean = id == _selectedId.value || id in _sourcePlaces.value

    fun select(id: Long) {
        prefs.edit().putLong(KEY_SELECTED, id).apply()
        _selectedId.value = id
    }

    /** Makes sure there is at least one place, and a valid selection. */
    suspend fun ensureDefaults() {
        if (dao.count() == 0) {
            // A generic starting point (Auckland city centre). Users add their own places in the app.
            val id = dao.insert(
                PlaceEntity(
                    name = "Auckland", region = "Auckland", country = "New Zealand", countryCode = "NZ",
                    latitude = -36.8485, longitude = 174.7633, timezone = "Pacific/Auckland",
                )
            )
            select(id)
        } else if (dao.byId(_selectedId.value) == null) {
            dao.all().firstOrNull()?.let { select(it.id) }
        }
    }

    suspend fun selected(): PlaceEntity? = dao.byId(_selectedId.value)

    suspend fun all(): List<PlaceEntity> = dao.all()

    suspend fun add(geo: GeoPlace): Long {
        val nextOrder = (dao.all().maxOfOrNull { it.sortOrder } ?: 0) + 1
        return dao.insert(
            PlaceEntity(
                name = geo.name, region = geo.admin1, country = geo.country, countryCode = geo.countryCode,
                latitude = geo.latitude, longitude = geo.longitude, timezone = geo.timezone, sortOrder = nextOrder,
            )
        )
    }

    /**
     * Creates or moves the "My location" place. Coordinates are rounded to about 1 km. The place is
     * named after the suburb, looked up on the phone (Android Geocoder), so no extra API call is made.
     */
    suspend fun setDeviceLocation(latitude: Double, longitude: Double): Long {
        val lat = Math.round(latitude * 100) / 100.0
        val lon = Math.round(longitude * 100) / 100.0
        val area = suburb(lat, lon)
        val name = area?.first ?: "My location"
        val region = area?.second
        val existing = dao.deviceLocation()
        return if (existing == null) {
            dao.insert(
                PlaceEntity(
                    name = name, region = region, country = area?.third, countryCode = null,
                    latitude = lat, longitude = lon, timezone = null, isDeviceLocation = true,
                )
            )
        } else {
            dao.update(existing.copy(latitude = lat, longitude = lon, name = name, region = region ?: existing.region))
            existing.id
        }
    }

    /** (suburb, city, country) for a point, or null if the phone has no geocoder or no answer. */
    @Suppress("DEPRECATION")
    private suspend fun suburb(lat: Double, lon: Double): Triple<String, String?, String?>? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            val a = Geocoder(appContext, Locale.ENGLISH).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return@runCatching null
            val suburb = a.subLocality ?: a.locality ?: return@runCatching null
            Triple(suburb, a.locality?.takeIf { it != suburb } ?: a.adminArea, a.countryName)
        }.getOrNull()
    }

    suspend fun delete(place: PlaceEntity) {
        dao.delete(place)
        if (_selectedId.value == place.id) {
            dao.all().firstOrNull()?.let { select(it.id) }
        }
    }

    companion object {
        private const val KEY_SELECTED = "selected_place_id"
        private const val KEY_SOURCE_PLACES = "source_places"
    }
}
