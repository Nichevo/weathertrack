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
/** Today's and tomorrow's pollen estimate, its record on days you reported, and whether it's personal yet. */
data class PollenView(
    val days: List<app.weathertrack.core.pollen.PollenDay>,
    val score: app.weathertrack.core.pollen.PollenScore,
    val reportedDays: Int,
    val personal: Boolean,
)

class ForecastCheckRepository(
    context: Context,
    private val db: AppDatabase,
    private val archive: ArchiveClient,
) {
    private val dao = db.forecastCheckDao()
    private val rawDao = db.rawPullDao()
    private val prefs = context.getSharedPreferences("forecast_check", Context.MODE_PRIVATE)
    private val pollenCalendar: List<app.weathertrack.core.pollen.SeasonMonth> by lazy {
        runCatching { context.assets.open("pollen_calendar.csv").bufferedReader().use { app.weathertrack.core.pollen.Pollen.parseCalendar(it.readText()) } }
            .getOrDefault(emptyList())
    }

    /**
     * The pollen estimate for today and tomorrow, and how it has done on days you reported. Uses your
     * own model once there are enough Pollen and No symptoms days here, otherwise the rules.
     */
    suspend fun pollen(place: PlaceEntity, forecast: app.weathertrack.core.model.Forecast?): PollenView {
        val P = app.weathertrack.core.pollen.Pollen
        val zone = runCatching { java.time.ZoneId.of(place.timezone) }.getOrDefault(java.time.ZoneId.systemDefault())
        val now = System.currentTimeMillis() / 1000
        val obs = observed(place.id, now - 400 * 86400L).map {
            app.weathertrack.core.pollen.PollenHour(it.time, it.temperature, it.precipitation, null, it.windSpeed, it.cloudCover)
        }
        val labels = P.labels(feelings(place.id, now - 400 * 86400L)
            .filter { it.kind == app.weathertrack.core.comfort.Feeling.Kind.POLLEN || it.kind == app.weathertrack.core.comfort.Feeling.Kind.NO_SYMPTOMS }
            .map { it.time to (it.kind == app.weathertrack.core.comfort.Feeling.Kind.POLLEN) }, zone)
        val examples = labels.mapNotNull { l ->
            P.factors(P.season(pollenCalendar, l.date, place.latitude), obs, l.date, zone)?.let { it to l.symptoms }
        }
        val model = P.fit(examples)
        // Past actuals have no humidity, so the personal model leaves it out of the forecast too.
        val fc = forecast?.hourly.orEmpty().map {
            app.weathertrack.core.pollen.PollenHour(it.time, it.temperature, it.precipitation, if (model == null) it.humidity else null, it.windSpeed, it.cloudCover)
        }
        val fcTimes = fc.map { it.time }.toSet()
        val hours = (obs.filter { it.time !in fcTimes && it.time >= now - 3 * 86400L } + fc).sortedBy { it.time }
        val today = java.time.LocalDate.now(zone)
        val days = listOf(today, today.plusDays(1)).mapNotNull { d -> P.day(P.season(pollenCalendar, d, place.latitude), hours, d, zone, model) }
        val scored = labels.mapNotNull { l ->
            P.day(P.season(pollenCalendar, l.date, place.latitude), obs, l.date, zone, model)?.let { it to l.symptoms }
        }
        return PollenView(days, P.score(scored), labels.size, model != null)
    }


    fun syncedRecently(placeId: Long): Boolean =
        System.currentTimeMillis() - prefs.getLong("synced_$placeId", 0L) < SYNC_EVERY_MS

    /** Downloads recent actuals and earlier runs. The first time goes back [FIRST_DAYS] days. */
    suspend fun sync(place: PlaceEntity, onWait: (Int) -> Unit = {}) {
        // Wind and cloud were added later: the first sync after that fetches the full 92 days again
        // so the past three months get them too.
        val backfilled = prefs.getBoolean("wind_cloud_${place.id}", false)
        val first = dao.observedCount(place.id) == 0 || dao.runCount(place.id) == 0 || !backfilled
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
                    windSpeed = it.windSpeed, windDirection = it.windDirection,
                    cloudCoverLow = it.cloudCoverLow, cloudCoverMid = it.cloudCoverMid, shortwaveRadiation = it.shortwaveRadiation,
                )
            })
        }

        val runs = runCatching { fetch(place, OpenMeteoUrls.previousRuns(place.latitude, place.longitude, days), ENDPOINT_RUNS, onWait) }
            .getOrElse { e ->
                // If wind or cloud isn't offered for this spot, keep temperature and rain going.
                if (e is ArchiveClient.RateLimited) throw e
                fetch(place, OpenMeteoUrls.previousRuns(place.latitude, place.longitude, days, windCloud = false), ENDPOINT_RUNS, onWait)
            }
        val runHours = OpenMeteoParser.parsePreviousRuns(runs.body).filter { it.time + 3600 <= nowSec }
        db.withTransaction {
            val pullId = rawDao.insert(raw(place, runs, ENDPOINT_RUNS))
            dao.insertRuns(runHours.map {
                PreviousRunHourEntity(place.id, it.time, it.lead, pullId, it.temperature, it.precipitation,
                    it.windSpeed, it.gusts, it.windDirection, it.cloudCover, it.cloudLow, it.cloudMid)
            })
        }
        prefs.edit().putLong("synced_${place.id}", System.currentTimeMillis()).putBoolean("wind_cloud_${place.id}", true).apply()
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
        ObsHour(it.validTime, it.temperature, it.apparentTemperature, it.precipitation, it.windGusts,
            it.windSpeed, it.windDirection, it.cloudCover, it.cloudCoverLow, it.cloudCoverMid, it.shortwaveRadiation)
    }

    suspend fun runs(placeId: Long, from: Long): List<RunHour> = dao.runs(placeId, from).map {
        RunHour(it.validTime, it.leadDays, it.temperature, it.precipitation,
            it.windSpeed, it.windGusts, it.windDirection, it.cloudCover, it.cloudCoverLow, it.cloudCoverMid)
    }

    suspend fun issued(placeId: Long, from: Long, until: Long): List<IssuedHour> = dao.issuedNearTerm(placeId, from, until).map {
        IssuedHour(it.issuedAt, it.validTime, it.temperature, it.apparentTemperature, it.precipitationProbability, it.precipitation, it.windGusts)
    }

    suspend fun storedPulls(placeId: Long): Int = dao.storedPulls(placeId)

    private val reportDao = db.userReportDao()

    /** Saves a Dry / Spitting / Rain / Heavy report with what the forecast and radar said at that moment. */
    suspend fun report(
        place: PlaceEntity, level: app.weathertrack.core.accuracy.RainLevel, forecastMm: Double?, forecastChance: Double?,
        radar: app.weathertrack.core.map.RadarSpot?,
    ) {
        reportDao.insert(
            app.weathertrack.data.db.UserReportEntity(
                placeId = place.id, reportedAt = System.currentTimeMillis() / 1000,
                latitude = place.latitude, longitude = place.longitude, raining = level.wet,
                forecastMm = forecastMm, forecastChance = forecastChance,
                radarHere = radar?.here, radarTime = radar?.time, rainLevel = level.key,
            ),
        )
    }

    /**
     * Saves [level] for right now, taking the forecast amount and rain chance for this moment from
     * [forecast]. Used by Today and by the widget pop-up.
     */
    suspend fun reportNow(
        place: PlaceEntity, level: app.weathertrack.core.accuracy.RainLevel,
        forecast: app.weathertrack.core.model.Forecast?, radar: app.weathertrack.core.map.RadarSpot?,
    ) {
        val now = System.currentTimeMillis() / 1000
        val mm = forecast?.quarterHourly?.firstOrNull { now >= it.time && now < it.time + 900 }?.precipitation
        val chance = forecast?.hourly?.firstOrNull { now >= it.time && now < it.time + 3600 }?.precipitationProbability
        report(place, level, mm, chance, radar)
    }

    /** Saves how you feel right now, with the weather this hour. */
    suspend fun feeling(place: PlaceEntity, kind: app.weathertrack.core.comfort.Feeling.Kind, hour: app.weathertrack.core.model.HourPoint?,
                        current: app.weathertrack.core.model.CurrentConditions?) {
        db.feelingReportDao().insert(
            app.weathertrack.data.db.FeelingReportEntity(
                placeId = place.id, reportedAt = System.currentTimeMillis() / 1000, kind = kind.key,
                temperature = current?.temperature ?: hour?.temperature,
                feelsLike = current?.apparentTemperature ?: hour?.apparentTemperature,
                windSpeed = current?.windSpeed ?: hour?.windSpeed, windGusts = current?.windGusts ?: hour?.windGusts,
                windDirection = current?.windDirection ?: hour?.windDirection,
                humidity = hour?.humidity, cloudCover = hour?.cloudCover,
            ),
        )
    }

    suspend fun feelings(placeId: Long, from: Long): List<app.weathertrack.core.comfort.Feeling> =
        db.feelingReportDao().since(placeId, from).mapNotNull { e ->
            app.weathertrack.core.comfort.Feeling.Kind.of(e.kind)?.let {
                app.weathertrack.core.comfort.Feeling(e.reportedAt, it, e.temperature, e.feelsLike, e.windSpeed, e.windDirection, e.humidity)
            }
        }

    /** Your chilly / too warm taps from every place, for the comfort model. */
    suspend fun allFeelings(from: Long): List<app.weathertrack.core.comfort.Feeling> =
        db.feelingReportDao().allSince(from).mapNotNull { e ->
            app.weathertrack.core.comfort.Feeling.Kind.of(e.kind)?.let {
                app.weathertrack.core.comfort.Feeling(e.reportedAt, it, e.temperature, e.feelsLike, e.windSpeed, e.windDirection, e.humidity)
            }
        }

    suspend fun reports(placeId: Long, from: Long): List<app.weathertrack.core.accuracy.RainReport> =
        reportDao.since(placeId, from).map {
            app.weathertrack.core.accuracy.RainReport(it.reportedAt, it.raining, it.forecastMm, it.radarHere,
                app.weathertrack.core.accuracy.RainLevel.of(it.rainLevel))
        }

    companion object {
        const val ENDPOINT_PAST = "forecast_past_hours"
        const val ENDPOINT_RUNS = "previous_runs"
        /** Open-Meteo keeps at most 92 past days on these endpoints. */
        const val FIRST_DAYS = 92
        const val LATER_DAYS = 7
        private const val SYNC_EVERY_MS = 6 * 3600_000L
    }
}

/**
 * Likely-range bands for daily highs by lead (days ahead), from the last [days] days at a place.
 * With [dayRegimes] (each coming day's forecast cloud), a day forecast to be low cloud or clear uses
 * the errors from past days of that kind once there are enough of them.
 */
suspend fun ForecastCheckRepository.highBands(
    place: PlaceEntity, days: Long = 3 * 365,
    dayRegimes: Map<java.time.LocalDate, app.weathertrack.core.accuracy.CloudRegime> = emptyMap(),
): Map<Int, app.weathertrack.core.accuracy.Band> {
    val zone = app.weathertrack.ui.Format.zone(place.timezone)
    val today = java.time.LocalDate.now(zone)
    val fromEpoch = today.minusDays(days).atStartOfDay(zone).toEpochSecond()
    val r = runs(place.id, fromEpoch)
    val o = observed(place.id, fromEpoch)
    // Same time of year across stored years when there are enough days, else everything.
    val errors = app.weathertrack.core.accuracy.ForecastCheck.seasonalHighErrors(r, o, zone, today)
    val base = app.weathertrack.core.accuracy.ForecastCheck.bands(errors)
    if (dayRegimes.isEmpty()) return base
    val byRegime = app.weathertrack.core.accuracy.WindCloud.highErrorsByRegime(r, o, zone, today.minusDays(days))
    return app.weathertrack.core.accuracy.WindCloud.regimeBands(base, byRegime) { lead -> dayRegimes[today.plusDays(lead.toLong())] }
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
