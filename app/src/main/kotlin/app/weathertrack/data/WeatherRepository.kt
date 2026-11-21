package app.weathertrack.data

import androidx.room.withTransaction
import app.weathertrack.core.model.Forecast
import app.weathertrack.core.model.GeoPlace
import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.openmeteo.OpenMeteoUrls
import app.weathertrack.data.db.AppDatabase
import app.weathertrack.data.db.ForecastDayEntity
import app.weathertrack.data.db.ForecastHourEntity
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.data.db.RawPullEntity
import app.weathertrack.data.net.HttpFetcher
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** A parsed forecast together with when it was fetched (epoch ms). */
data class StoredForecast(val forecast: Forecast, val fetchedAtMs: Long, val pullId: Long)

class WeatherRepository(
    private val db: AppDatabase,
    private val http: HttpFetcher,
) {
    private val rawDao = db.rawPullDao()
    private val forecastDao = db.forecastDao()

    /** Latest successfully stored forecast for a place, parsed from its raw JSON. Works offline. */
    suspend fun cachedForecast(place: PlaceEntity): StoredForecast? {
        val pull = rawDao.latest(place.id, SOURCE, ENDPOINT_FORECAST) ?: return null
        return runCatching { StoredForecast(OpenMeteoParser.parseForecast(pull.body), pull.fetchedAt, pull.id) }.getOrNull()
    }

    /**
     * Fetches a fresh forecast, stores the raw response (even if it is an error), then stores
     * every hourly and daily value as issued.
     */
    suspend fun refreshForecast(place: PlaceEntity, trigger: String): StoredForecast {
        val url = OpenMeteoUrls.forecast(place.latitude, place.longitude)
        val result = try {
            http.get(url)
        } catch (e: IOException) {
            throw IOException("Couldn't reach Open-Meteo: ${e.message}", e)
        }
        val pull = RawPullEntity(
            source = SOURCE,
            endpoint = ENDPOINT_FORECAST,
            placeId = place.id,
            latitude = place.latitude,
            longitude = place.longitude,
            url = result.url,
            fetchedAt = result.fetchedAtMs,
            httpStatus = result.status,
            durationMs = result.durationMs,
            trigger = trigger,
            body = result.body,
        )
        if (result.status != 200) {
            rawDao.insert(pull)
            throw IOException("Open-Meteo returned HTTP ${result.status}")
        }
        val forecast = OpenMeteoParser.parseForecast(result.body)
        val issuedAt = result.fetchedAtMs / 1000
        val pullId = db.withTransaction {
            val id = rawDao.insert(pull)
            forecastDao.insertHours(forecast.hourly.map { h ->
                ForecastHourEntity(
                    pullId = id,
                    placeId = place.id,
                    issuedAt = issuedAt,
                    validTime = h.time,
                    leadHours = ((h.time - issuedAt) / 3600.0).roundToInt(),
                    temperature = h.temperature,
                    apparentTemperature = h.apparentTemperature,
                    precipitationProbability = h.precipitationProbability,
                    precipitation = h.precipitation,
                    weatherCode = h.weatherCode,
                    windSpeed = h.windSpeed,
                    windGusts = h.windGusts,
                    windDirection = h.windDirection,
                    uvIndex = h.uvIndex,
                    cloudCover = h.cloudCover,
                    humidity = h.humidity,
                )
            })
            val zone = runCatching { ZoneId.of(forecast.timezone) }.getOrDefault(ZoneId.of("UTC"))
            val issuedDate = java.time.Instant.ofEpochSecond(issuedAt).atZone(zone).toLocalDate()
            forecastDao.insertDays(forecast.daily.map { d ->
                ForecastDayEntity(
                    pullId = id,
                    placeId = place.id,
                    issuedAt = issuedAt,
                    date = d.date,
                    leadDays = (LocalDate.parse(d.date).toEpochDay() - issuedDate.toEpochDay()).toInt(),
                    weatherCode = d.weatherCode,
                    temperatureMax = d.temperatureMax,
                    temperatureMin = d.temperatureMin,
                    precipitationSum = d.precipitationSum,
                    precipitationProbabilityMax = d.precipitationProbabilityMax,
                    windGustsMax = d.windGustsMax,
                    uvIndexMax = d.uvIndexMax,
                )
            })
            id
        }
        return StoredForecast(forecast, result.fetchedAtMs, pullId)
    }

    /** Searches places by name. The raw response is stored too. */
    suspend fun searchPlaces(query: String): List<GeoPlace> {
        val result = http.get(OpenMeteoUrls.geocode(query))
        rawDao.insert(
            RawPullEntity(
                source = SOURCE, endpoint = ENDPOINT_GEOCODING, placeId = null, latitude = null, longitude = null,
                url = result.url, fetchedAt = result.fetchedAtMs, httpStatus = result.status,
                durationMs = result.durationMs, trigger = "app", body = result.body,
            )
        )
        if (result.status != 200) throw IOException("Place search returned HTTP ${result.status}")
        return OpenMeteoParser.parseGeocoding(result.body)
    }

    companion object {
        const val SOURCE = "open-meteo"
        const val ENDPOINT_FORECAST = "forecast"
        const val ENDPOINT_GEOCODING = "geocoding"
    }
}
