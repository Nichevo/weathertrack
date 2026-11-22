package app.weathertrack.data

import app.weathertrack.core.accuracy.DaySpread
import app.weathertrack.core.accuracy.Spread
import app.weathertrack.core.air.AirHour
import app.weathertrack.core.warnings.Cap
import app.weathertrack.core.warnings.Warning
import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.openmeteo.OpenMeteoUrls
import app.weathertrack.core.tides.TideSeries
import app.weathertrack.core.tides.Linz
import app.weathertrack.core.seasonal.Enso
import app.weathertrack.core.seasonal.ModelMonth
import app.weathertrack.core.seasonal.OniMonth
import app.weathertrack.core.seasonal.Outlook
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

    /**
     * In New Zealand, LINZ's official predictions for the nearest tide port (or [portName]); elsewhere,
     * or if LINZ can't be reached, Open-Meteo's modelled sea level.
     */
    suspend fun tides(place: PlaceEntity, trigger: String = "app", portName: String = ""): TideSeries? =
        runCatching { linzTides(place, trigger, portName) }.getOrNull()
            ?: cachedOrFetch(place, ENDPOINT_TIDES, TIDES_MAX_AGE_MS, trigger) { OpenMeteoUrls.tides(place.latitude, place.longitude) }
                ?.let { runCatching { OpenMeteoParser.parseTides(it) }.getOrNull() }

    private suspend fun linzTides(place: PlaceEntity, trigger: String, portName: String): TideSeries? {
        if (!inNewZealand(place.latitude, place.longitude)) return null
        // A chosen port only applies to places within 150 km of it, so other NZ places still get their own.
        val port = Linz.byName(portName)?.takeIf { Linz.distanceKm(place.latitude, place.longitude, it) <= 150 } ?: Linz.nearest(place.latitude, place.longitude)?.first ?: return null
        val now = System.currentTimeMillis() / 1000
        val today = java.time.LocalDate.now(Linz.ZONE)
        // This year's table, plus next year's near New Year so the week ahead is covered.
        val years = listOf(today.year) + if (today.plusDays(8).year != today.year) listOf(today.year + 1) else emptyList()
        val events = years.flatMap { y ->
            val body = cachedOrFetch(place, "linz_tides_${port.name}_$y", LINZ_MAX_AGE_MS, trigger, source = SOURCE_LINZ) { Linz.csvUrl(port, y) }
                ?: return null
            Linz.parse(body)
        }
        return Linz.series(port, events, now - 6 * 3600, now + 7 * 86400)
    }

    suspend fun spread(place: PlaceEntity, trigger: String = "app"): List<DaySpread> {
        val body = cachedOrFetch(place, ENDPOINT_SPREAD, SPREAD_MAX_AGE_MS, trigger, fallbackUrl = {
            OpenMeteoUrls.modelSpread(place.latitude, place.longitude, OpenMeteoUrls.SPREAD_MODELS_SAFE)
        }) { OpenMeteoUrls.modelSpread(place.latitude, place.longitude) } ?: return emptyList()
        return runCatching { Spread.of(OpenMeteoParser.parseModelSpread(body, OpenMeteoUrls.SPREAD_MODELS)) }.getOrDefault(emptyList())
    }

    /** Air quality (CAMS via Open-Meteo); pollen too when the place is in Europe. */
    suspend fun air(place: PlaceEntity, trigger: String = "app"): List<AirHour> {
        val pollen = OpenMeteoUrls.inEurope(place.latitude, place.longitude)
        val body = cachedOrFetch(place, ENDPOINT_AIR, AIR_MAX_AGE_MS, trigger, fallbackUrl = {
            OpenMeteoUrls.airQuality(place.latitude, place.longitude, pollen = false)
        }) { OpenMeteoUrls.airQuality(place.latitude, place.longitude, pollen) } ?: return emptyList()
        return runCatching { OpenMeteoParser.parseAir(body) }.getOrDefault(emptyList())
    }

    /**
     * Current official warnings and watches covering the place. Only New Zealand (MetService's
     * public CAP feed, CC BY 4.0) for now; elsewhere this returns nothing.
     */
    suspend fun warnings(place: PlaceEntity, trigger: String = "app"): List<Warning> {
        if (!inNewZealand(place.latitude, place.longitude)) return emptyList()
        val feed = cachedOrFetch(place, ENDPOINT_WARNINGS, WARNINGS_MAX_AGE_MS, trigger, source = SOURCE_METSERVICE) { METSERVICE_CAP_FEED }
            ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        val items = runCatching { Cap.feed(feed) }.getOrDefault(emptyList())
        return items.mapNotNull { item ->
            // Each CAP document never changes once issued, so a stored copy is reused.
            val body = rawDao.latestForUrl(item.link)?.text ?: runCatching { http.get(item.link) }.getOrNull()?.also {
                rawDao.insert(raw(place, ENDPOINT_WARNING_ALERT, it, trigger, SOURCE_METSERVICE))
            }?.takeIf { it.status == 200 }?.body
            body?.let { runCatching { Cap.alert(it, item.link) }.getOrNull() }
        }.filter { it.active(now) && it.covers(place.latitude, place.longitude) }
            .distinctBy { it.id }
            .sortedByDescending { it.serious }
    }

    /** ECMWF SEAS5 monthly outlook (about 7 months) for the place, refreshed daily. */
    suspend fun seasonalModel(place: PlaceEntity, trigger: String = "app"): List<ModelMonth> =
        cachedOrFetch(place, ENDPOINT_SEASONAL, SEASONAL_MAX_AGE_MS, trigger) { Outlook.url(place.latitude, place.longitude) }
            ?.let { Outlook.parseSeasonal(it) }.orEmpty()

    /** NOAA's Oceanic Niño Index (El Niño / La Niña record since 1950), refreshed weekly. */
    suspend fun oni(place: PlaceEntity, trigger: String = "app"): List<OniMonth> =
        cachedOrFetch(place, ENDPOINT_ONI, ONI_MAX_AGE_MS, trigger, source = SOURCE_NOAA) { Enso.ONI_URL }
            ?.let { runCatching { Enso.parseOni(it) }.getOrNull() }.orEmpty()

    private fun inNewZealand(lat: Double, lon: Double) = lat in -48.0..-33.5 && lon in 165.5..179.5

    private suspend fun cachedOrFetch(
        place: PlaceEntity, endpoint: String, maxAgeMs: Long, trigger: String,
        fallbackUrl: (() -> String)? = null, source: String = WeatherRepository.SOURCE, url: () -> String,
    ): String? {
        val cached = rawDao.latest(place.id, source, endpoint)
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < maxAgeMs) return cached.text
        var r = runCatching { http.get(url()) }.getOrNull()
        r?.let { rawDao.insert(raw(place, endpoint, it, trigger, source)) }
        if (r != null && r.status == 400 && fallbackUrl != null) {
            // A variable or model name the API doesn't know here: retry with the safe set.
            r = runCatching { http.get(fallbackUrl()) }.getOrNull()?.also { rawDao.insert(raw(place, endpoint, it, trigger, source)) }
        }
        return r?.takeIf { it.status == 200 }?.body ?: cached?.text
    }

    private fun raw(place: PlaceEntity, endpoint: String, r: HttpResult, trigger: String, source: String = WeatherRepository.SOURCE) = RawPullEntity(
        source = source, endpoint = endpoint, placeId = place.id,
        latitude = place.latitude, longitude = place.longitude, url = r.url,
        fetchedAt = r.fetchedAtMs, httpStatus = r.status, durationMs = r.durationMs, trigger = trigger, body = r.body,
    )

    companion object {
        const val ENDPOINT_TIDES = "marine_tides"
        const val SOURCE_LINZ = "linz"
        /** A port's yearly table doesn't change. */
        private const val LINZ_MAX_AGE_MS = 30 * 86_400_000L
        const val ENDPOINT_SPREAD = "model_spread"
        private const val TIDES_MAX_AGE_MS = 12 * 3600_000L
        private const val SPREAD_MAX_AGE_MS = 3 * 3600_000L
        const val ENDPOINT_AIR = "air_quality"
        private const val AIR_MAX_AGE_MS = 3 * 3600_000L
        const val SOURCE_METSERVICE = "metservice"
        const val ENDPOINT_WARNINGS = "cap_feed"
        const val ENDPOINT_WARNING_ALERT = "cap_alert"
        const val METSERVICE_CAP_FEED = "https://alerts.metservice.com/cap/rss"
        private const val WARNINGS_MAX_AGE_MS = 15 * 60_000L
        const val ENDPOINT_SEASONAL = "seasonal_monthly"
        private const val SEASONAL_MAX_AGE_MS = 24 * 3600_000L
        const val SOURCE_NOAA = "noaa"
        const val ENDPOINT_ONI = "oni"
        private const val ONI_MAX_AGE_MS = 7 * 24 * 3600_000L
    }
}
