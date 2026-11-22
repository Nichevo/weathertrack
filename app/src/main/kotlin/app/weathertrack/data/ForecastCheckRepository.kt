package app.weathertrack.data

import android.content.Context
import androidx.room.withTransaction
import app.weathertrack.core.accuracy.IssuedHour
import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.accuracy.RunHour
import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.openmeteo.OpenMeteoUrls
import app.weathertrack.data.db.AppDatabase
import app.weathertrack.data.db.ObservedHourEntity
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.data.db.PreviousRunHourEntity
import app.weathertrack.data.db.RawPullEntity
import app.weathertrack.data.net.ArchiveClient
import app.weathertrack.data.net.HttpResult
import java.io.IOException

/**
 * Data for Forecast check: what actually happened each hour (Open-Meteo's best estimate for past
 * hours) and what earlier model runs said about those hours (Previous Runs). Both are stored raw
 * first. The forecasts this app stored itself come from forecast_hour.
 */
class ForecastCheckRepository(
    context: Context,
    private val db: AppDatabase,
    private val archive: ArchiveClient,
) {
    private val dao = db.forecastCheckDao()
    private val rawDao = db.rawPullDao()
    private val prefs = context.getSharedPreferences("forecast_check", Context.MODE_PRIVATE)

    fun syncedRecently(placeId: Long): Boolean =
        System.currentTimeMillis() - prefs.getLong("synced_$placeId", 0L) < SYNC_EVERY_MS

    /** Downloads recent actuals and earlier runs. The first time goes back [FIRST_DAYS] days. */
    suspend fun sync(place: PlaceEntity, onWait: (Int) -> Unit = {}) {
        val first = dao.observedCount(place.id) == 0 || dao.runCount(place.id) == 0
        val days = if (first) FIRST_DAYS else LATER_DAYS
        val nowSec = System.currentTimeMillis() / 1000

        val obs = fetch(place, OpenMeteoUrls.pastHours(place.latitude, place.longitude, days), ENDPOINT_PAST, onWait)
        // Only hours that have finished count as actuals.
        val hours = OpenMeteoParser.parseForecast(obs.body).hourly.filter { it.time + 3600 <= nowSec }
        db.withTransaction {
            val pullId = rawDao.insert(raw(place, obs, ENDPOINT_PAST))
            dao.insertObserved(hours.map {
                ObservedHourEntity(
                    placeId = place.id, validTime = it.time, pullId = pullId, fetchedAt = obs.fetchedAtMs / 1000,
                    temperature = it.temperature, apparentTemperature = it.apparentTemperature,
                    precipitation = it.precipitation, weatherCode = it.weatherCode, windGusts = it.windGusts,
                    humidity = it.humidity, cloudCover = it.cloudCover,
                )
            })
        }

        val runs = fetch(place, OpenMeteoUrls.previousRuns(place.latitude, place.longitude, days), ENDPOINT_RUNS, onWait)
        val runHours = OpenMeteoParser.parsePreviousRuns(runs.body).filter { it.time + 3600 <= nowSec }
        db.withTransaction {
            val pullId = rawDao.insert(raw(place, runs, ENDPOINT_RUNS))
            dao.insertRuns(runHours.map {
                PreviousRunHourEntity(place.id, it.time, it.lead, pullId, it.temperature, it.precipitation)
            })
        }
        prefs.edit().putLong("synced_${place.id}", System.currentTimeMillis()).apply()
    }

    private suspend fun fetch(place: PlaceEntity, url: String, endpoint: String, onWait: (Int) -> Unit): HttpResult {
        val result = archive.get(url, onWait)
        if (result.status != 200) {
            rawDao.insert(raw(place, result, endpoint))
            throw IOException("Forecast check download returned HTTP ${result.status}")
        }
        return result
    }

    private fun raw(place: PlaceEntity, result: HttpResult, endpoint: String) = RawPullEntity(
        source = WeatherRepository.SOURCE, endpoint = endpoint, placeId = place.id,
        latitude = place.latitude, longitude = place.longitude, url = result.url,
        fetchedAt = result.fetchedAtMs, httpStatus = result.status, durationMs = result.durationMs,
        trigger = "forecast_check", body = result.body,
    )

    suspend fun observed(placeId: Long, from: Long): List<ObsHour> = dao.observed(placeId, from).map {
        ObsHour(it.validTime, it.temperature, it.apparentTemperature, it.precipitation, it.windGusts)
    }

    suspend fun runs(placeId: Long, from: Long): List<RunHour> = dao.runs(placeId, from).map {
        RunHour(it.validTime, it.leadDays, it.temperature, it.precipitation)
    }

    suspend fun issued(placeId: Long, from: Long, until: Long): List<IssuedHour> = dao.issuedNearTerm(placeId, from, until).map {
        IssuedHour(it.issuedAt, it.validTime, it.temperature, it.apparentTemperature, it.precipitationProbability, it.precipitation, it.windGusts)
    }

    suspend fun storedPulls(placeId: Long): Int = dao.storedPulls(placeId)

    private val reportDao = db.userReportDao()

    /** Saves a "raining" / "dry" report with what the forecast and radar said at that moment. */
    suspend fun report(
        place: PlaceEntity, raining: Boolean, forecastMm: Double?, forecastChance: Double?,
        radar: app.weathertrack.core.map.RadarSpot?,
    ) {
        reportDao.insert(
            app.weathertrack.data.db.UserReportEntity(
                placeId = place.id, reportedAt = System.currentTimeMillis() / 1000,
                latitude = place.latitude, longitude = place.longitude, raining = raining,
                forecastMm = forecastMm, forecastChance = forecastChance,
                radarHere = radar?.here, radarTime = radar?.time,
            ),
        )
    }

    suspend fun reports(placeId: Long, from: Long): List<app.weathertrack.core.accuracy.RainReport> =
        reportDao.since(placeId, from).map { app.weathertrack.core.accuracy.RainReport(it.reportedAt, it.raining, it.forecastMm, it.radarHere) }

    companion object {
        const val ENDPOINT_PAST = "forecast_past_hours"
        const val ENDPOINT_RUNS = "previous_runs"
        /** Open-Meteo keeps at most 92 past days on these endpoints. */
        const val FIRST_DAYS = 92
        const val LATER_DAYS = 7
        private const val SYNC_EVERY_MS = 6 * 3600_000L
    }
}

/** Likely-range bands for daily highs by lead (days ahead), from the last [days] days at a place. */
suspend fun ForecastCheckRepository.highBands(place: PlaceEntity, days: Long = 90): Map<Int, app.weathertrack.core.accuracy.Band> {
    val zone = app.weathertrack.ui.Format.zone(place.timezone)
    val from = java.time.LocalDate.now(zone).minusDays(days)
    val fromEpoch = from.atStartOfDay(zone).toEpochSecond()
    val errors = app.weathertrack.core.accuracy.ForecastCheck.highErrors(runs(place.id, fromEpoch), observed(place.id, fromEpoch), zone, from)
    return app.weathertrack.core.accuracy.ForecastCheck.bands(errors)
}

/** What this place's record says about how far to trust the forecast, over the last [days] days. */
data class TrustRecord(
    val reliability: List<app.weathertrack.core.accuracy.Reliability>,
    val jacket: app.weathertrack.core.accuracy.JacketScore,
    val reports: app.weathertrack.core.accuracy.ReportScore,
)

suspend fun ForecastCheckRepository.trustRecord(
    place: PlaceEntity, thresholds: app.weathertrack.core.jacket.JacketThresholds, morningHour: Int, days: Long = 90,
): TrustRecord {
    val zone = app.weathertrack.ui.Format.zone(place.timezone)
    val from = java.time.LocalDate.now(zone).minusDays(days)
    val fromEpoch = from.atStartOfDay(zone).toEpochSecond()
    val now = System.currentTimeMillis() / 1000
    val obs = observed(place.id, fromEpoch)
    val iss = issued(place.id, fromEpoch, now)
    val FC = app.weathertrack.core.accuracy.ForecastCheck
    return TrustRecord(
        FC.reliability(iss, obs, fromEpoch),
        FC.jacket(iss, obs, zone, from, thresholds, morningHour = morningHour),
        app.weathertrack.core.accuracy.Feedback.score(reports(place.id, fromEpoch)),
    )
}
