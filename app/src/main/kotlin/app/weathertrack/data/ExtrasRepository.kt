package app.weathertrack.data

import app.weathertrack.core.accuracy.DaySpread
import app.weathertrack.core.accuracy.Spread
import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.openmeteo.OpenMeteoUrls
import app.weathertrack.core.tides.TideSeries
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.data.db.RawPullDao
import app.weathertrack.data.db.RawPullEntity
import app.weathertrack.data.net.HttpFetcher
import app.weathertrack.data.net.HttpResult

/**
 * Tides (Open-Meteo Marine, nearest sea grid cell) and model spread (several weather models side by
 * side). Both are stored raw like every other pull and reused while fresh; an older stored pull is
 * used when the network fails.
 */
class ExtrasRepository(private val http: HttpFetcher, private val rawDao: RawPullDao) {

    suspend fun tides(place: PlaceEntity, trigger: String = "app"): TideSeries? =
        cachedOrFetch(place, ENDPOINT_TIDES, TIDES_MAX_AGE_MS, trigger) { OpenMeteoUrls.tides(place.latitude, place.longitude) }
            ?.let { runCatching { OpenMeteoParser.parseTides(it) }.getOrNull() }

    suspend fun spread(place: PlaceEntity, trigger: String = "app"): List<DaySpread> {
        val body = cachedOrFetch(place, ENDPOINT_SPREAD, SPREAD_MAX_AGE_MS, trigger, fallbackUrl = {
            OpenMeteoUrls.modelSpread(place.latitude, place.longitude, OpenMeteoUrls.SPREAD_MODELS_SAFE)
        }) { OpenMeteoUrls.modelSpread(place.latitude, place.longitude) } ?: return emptyList()
        return runCatching { Spread.of(OpenMeteoParser.parseModelSpread(body, OpenMeteoUrls.SPREAD_MODELS)) }.getOrDefault(emptyList())
    }

    private suspend fun cachedOrFetch(
        place: PlaceEntity, endpoint: String, maxAgeMs: Long, trigger: String,
        fallbackUrl: (() -> String)? = null, url: () -> String,
    ): String? {
        val cached = rawDao.latest(place.id, WeatherRepository.SOURCE, endpoint)
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < maxAgeMs) return cached.body
        var r = runCatching { http.get(url()) }.getOrNull()
        r?.let { rawDao.insert(raw(place, endpoint, it, trigger)) }
        if (r != null && r.status == 400 && fallbackUrl != null) {
            // A model name the API doesn't know: retry with the core models.
            r = runCatching { http.get(fallbackUrl()) }.getOrNull()?.also { rawDao.insert(raw(place, endpoint, it, trigger)) }
        }
        return r?.takeIf { it.status == 200 }?.body ?: cached?.body
    }

    private fun raw(place: PlaceEntity, endpoint: String, r: HttpResult, trigger: String) = RawPullEntity(
        source = WeatherRepository.SOURCE, endpoint = endpoint, placeId = place.id,
        latitude = place.latitude, longitude = place.longitude, url = r.url,
        fetchedAt = r.fetchedAtMs, httpStatus = r.status, durationMs = r.durationMs, trigger = trigger, body = r.body,
    )

    companion object {
        const val ENDPOINT_TIDES = "marine_tides"
        const val ENDPOINT_SPREAD = "model_spread"
        private const val TIDES_MAX_AGE_MS = 12 * 3600_000L
        private const val SPREAD_MAX_AGE_MS = 3 * 3600_000L
    }
}
