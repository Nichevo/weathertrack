package app.weathertrack.data

import android.content.Context
import app.weathertrack.core.model.GeoPlace
import app.weathertrack.data.db.PlaceDao
import app.weathertrack.data.db.PlaceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlaceRepository(context: Context, private val dao: PlaceDao) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val _selectedId = MutableStateFlow(prefs.getLong(KEY_SELECTED, -1L))
    val selectedId: StateFlow<Long> = _selectedId

    val places: Flow<List<PlaceEntity>> = dao.observeAll()

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

    /** Creates or moves the "My location" place. Coordinates are rounded to about 1 km. */
    suspend fun setDeviceLocation(latitude: Double, longitude: Double): Long {
        val lat = Math.round(latitude * 100) / 100.0
        val lon = Math.round(longitude * 100) / 100.0
        val existing = dao.deviceLocation()
        return if (existing == null) {
            dao.insert(
                PlaceEntity(
                    name = "My location", region = null, country = null, countryCode = null,
                    latitude = lat, longitude = lon, timezone = null, isDeviceLocation = true,
                )
            )
        } else {
            dao.update(existing.copy(latitude = lat, longitude = lon))
            existing.id
        }
    }

    suspend fun delete(place: PlaceEntity) {
        dao.delete(place)
        if (_selectedId.value == place.id) {
            dao.all().firstOrNull()?.let { select(it.id) }
        }
    }

    companion object {
        private const val KEY_SELECTED = "selected_place_id"
    }
}
