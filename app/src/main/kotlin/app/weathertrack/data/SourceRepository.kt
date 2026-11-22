package app.weathertrack.data

import android.util.Log
import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.accuracy.RainReport
import app.weathertrack.core.model.Forecast
import app.weathertrack.core.sources.BlendHour
import app.weathertrack.core.sources.DayScore
import app.weathertrack.core.sources.PastHour
import app.weathertrack.core.sources.IssuedSourceHour
import app.weathertrack.core.sources.SourceHour
import app.weathertrack.core.sources.SourceScore
import app.weathertrack.core.sources.SourceWeight
import app.weathertrack.core.sources.Sources
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.data.db.RawPullDao
import app.weathertrack.data.db.SourceDayDao
import app.weathertrack.data.db.SourceDayEntity
import app.weathertrack.data.db.RawPullEntity
import app.weathertrack.data.db.SourceHourDao
import app.weathertrack.data.db.SourceHourEntity
import app.weathertrack.data.net.HttpFetcher
import app.weathertrack.data.net.HttpResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

/** The latest forecast from every source, how each has done here, and the accuracy-weighted blend. */
data class SourceView(
    val latest: List<SourceHour>,
    val scores: List<SourceScore>,
    val weights: List<SourceWeight>,
    val blend: List<BlendHour>,
    /** The last 7 days hour by hour: what happened and what each source said. Newest first. */
    val past: List<PastHour> = emptyList(),
    /** The last 7 days, per source and day. */
    val days: List<DayScore> = emptyList(),
    /** Earlier days summed per source and week (Monday start). */
    val weeks: List<DayScore> = emptyList(),
)

/**
 * Pulls the same place from several forecast sources, stores every hour as issued (and the raw
 * responses), and scores each source against what happened.
 */
class SourceRepository(
    private val http: HttpFetcher, private val rawDao: RawPullDao, private val dao: SourceHourDao, private val dayDao: SourceDayDao,
) {
    private val lock = Mutex()

    /**
     * Fetches sources that are due (models hourly, MET Norway every 3 hours as its terms ask).
     * [main] is the app's own stored forecast, recorded as issued when it was fetched, not now.
     * [force] (the Refresh button) re-pulls the Open-Meteo sources if the last pull is over 10 minutes old.
     */
    suspend fun refresh(place: PlaceEntity, main: StoredForecast?, trigger: String, force: Boolean = false) = lock.withLock {
        val now = System.currentTimeMillis() / 1000
        suspend fun due(key: String, everyS: Long) = (dao.lastIssued(place.id, key) ?: 0L) < now - if (force) minOf(everyS, FORCE_MIN_S) else everyS
        val pulls = supervisorScope {
            val jobs = mutableListOf<Deferred<Pair<Long, List<SourceHour>>>>()
            if (main != null) {
                val issued = main.fetchedAtMs / 1000
                if (issued > (dao.lastIssued(place.id, Sources.MAIN.id) ?: 0L)) jobs += async {
                    issued to main.forecast.hourly.map {
                        SourceHour(Sources.MAIN.id, it.time, it.temperature, it.precipitation, it.precipitationProbability, it.windSpeed, it.cloudCover)
                    }
                }
            }
            if (due(Sources.MODELS.first().id, HOUR - 300)) jobs += async {
                now to fetch(place, "source_models", trigger, Sources.modelsUrl(place.latitude, place.longitude, Sources.MODELS),
                    fallback = Sources.modelsUrl(place.latitude, place.longitude, Sources.MODELS_SAFE, windCloud = false)) { body ->
                    Sources.parseModels(body, Sources.MODELS)
                }
            }
            Sources.ENSEMBLES.forEach { e ->
                if (due(Sources.key(e), 3 * HOUR - 300)) jobs += async {
                    now to fetch(place, "source_ensemble_${e.id}", trigger, Sources.ensembleUrl(place.latitude, place.longitude, e),
                        fallback = Sources.ensembleUrl(place.latitude, place.longitude, e, windCloud = false)) { Sources.parseEnsemble(it, e) }
                }
            }
            // MET Norway asks for no more than one pull per 3 hours here, so Refresh doesn't hurry it.
            if ((dao.lastIssued(place.id, Sources.METNO.id) ?: 0L) < now - 3 * HOUR) jobs += async {
                now to fetch(place, "source_metno", trigger, Sources.metnoUrl(place.latitude, place.longitude)) { Sources.parseMetno(it) }
            }
            // One source failing (network, parse or database) must not lose the others.
            jobs.mapNotNull { j -> runCatching { j.await() }.onFailure { Log.w("Sources", "A source failed", it) }.getOrNull() }
        }
        val rows = pulls.flatMap { (issuedAt, hours) ->
            hours.filter { it.time in (issuedAt - HOUR)..(issuedAt + KEEP_AHEAD_H * HOUR) }
                .map { SourceHourEntity(placeId = place.id, source = it.source, issuedAt = issuedAt, time = it.time,
                    temperature = it.temperature, precipitation = it.precipitation, rainChance = it.rainChance,
                    windSpeed = it.windSpeed, cloudCover = it.cloudCover) }
        }
        if (rows.isNotEmpty()) dao.insertAll(rows)
        runCatching { dao.prune(now, now - KEEP_DAYS * 86400, MIN_LEAD_H * HOUR) }.onFailure { Log.w("Sources", "Prune failed", it) }
    }

    private suspend fun fetch(
        place: PlaceEntity, endpoint: String, trigger: String, url: String, fallback: String? = null,
        parse: (String) -> List<SourceHour>,
    ): List<SourceHour> {
        var r = runCatching { http.get(url) }.getOrNull() ?: return emptyList()
        rawDao.insert(raw(place, endpoint, r, trigger))
        if (r.status == 400 && fallback != null) {
            // A model this API doesn't know (any more): keep the core three.
            r = runCatching { http.get(fallback) }.getOrNull() ?: return emptyList()
            rawDao.insert(raw(place, endpoint, r, trigger))
        }
        if (r.status != 200) { Log.w("Sources", "$endpoint HTTP ${r.status}"); return emptyList() }
        return runCatching { parse(r.body) }.onFailure { Log.w("Sources", "$endpoint parse failed", it) }.getOrDefault(emptyList())
    }

    private fun raw(place: PlaceEntity, endpoint: String, r: HttpResult, trigger: String) = RawPullEntity(
        source = if (endpoint == "source_metno") "metno" else WeatherRepository.SOURCE, endpoint = endpoint, placeId = place.id,
        latitude = place.latitude, longitude = place.longitude, url = r.url,
        fetchedAt = r.fetchedAtMs, httpStatus = r.status, durationMs = r.durationMs, trigger = trigger, body = r.body,
    )

    /** Latest issued hour per source and time, from [from] on. */
    suspend fun latest(placeId: Long, from: Long, hours: Int = KEEP_AHEAD_H): List<SourceHour> =
        dao.between(placeId, from, from + hours * HOUR)
            .groupBy { it.source to it.time }.values.map { v -> v.maxBy { it.issuedAt } }
            .map { SourceHour(it.source, it.time, it.temperature, it.precipitation, it.rainChance, it.windSpeed, it.cloudCover) }

    suspend fun issued(placeId: Long, from: Long, to: Long): List<IssuedSourceHour> =
        dao.forScoring(placeId, from, to, MIN_LEAD_H * HOUR, (MAX_LEAD_H + 1) * HOUR - 1).map {
            IssuedSourceHour(it.source, it.issuedAt, it.time, it.temperature, it.precipitation, it.rainChance, it.windSpeed, it.cloudCover)
        }

    /**
     * Everything the Sources screen and Today need, scored over the last [days] days. Also saves each
     * source's daily record, which is kept after the hourly rows are cleared.
     */
    suspend fun view(placeId: Long, zone: ZoneId, obs: List<ObsHour>, reports: List<RainReport>, days: Long = 60): SourceView {
        val now = System.currentTimeMillis() / 1000
        val latest = latest(placeId, now - HOUR)
        val issued = issued(placeId, now - days * 86400, now)
        val scores = Sources.scores(issued, obs, reports, MIN_LEAD_H.toInt(), MAX_LEAD_H.toInt())
        val keys = latest.map { it.source }.distinct()
        val weights = Sources.weights(keys, scores)

        val today = LocalDate.now(zone)
        val past = Sources.pastHours(issued, obs, MIN_LEAD_H.toInt(), MAX_LEAD_H.toInt())
        // Days near the edge of the hourly window may be partly cleared already, so only recent days are (re)written.
        val fresh = today.minusDays(SUMMARY_DAYS)
        runCatching {
            dayDao.upsert(Sources.daily(past, zone).filter { !it.date.isBefore(fresh) }.map {
                SourceDayEntity(placeId, it.date.toString(), it.source, it.hours, it.tempAbsSum, it.tempErrSum, it.tempN, it.rainRight, it.rainN)
            })
        }.onFailure { Log.w("Sources", "Saving daily records failed", it) }
        val stored = runCatching { dayDao.since(placeId, today.minusYears(3).toString()) }.getOrDefault(emptyList()).map {
            DayScore(LocalDate.parse(it.date), it.source, it.hours, it.tempAbsSum, it.tempErrSum, it.tempN, it.rainRight, it.rainN)
        }
        val weekStart = today.minusDays(RECENT_DAYS - 1)
        val recentFrom = weekStart.atStartOfDay(zone).toEpochSecond()
        return SourceView(
            latest, scores, weights, Sources.blend(latest, weights, now - HOUR),
            past = past.filter { it.time > recentFrom },
            days = stored.filter { !it.date.isBefore(weekStart) },
            weeks = Sources.weekly(stored.filter { it.date.isBefore(weekStart) }),
        )
    }

    companion object {
        private const val HOUR = 3600L
        /** Hours ahead stored from each pull: enough for "tomorrow" without filling the phone. */
        const val KEEP_AHEAD_H = 48
        /** Forecast leads that count towards a source's record. */
        private const val MIN_LEAD_H = 1L
        private const val MAX_LEAD_H = 30L
        /** Days of past source hours kept (scores look back 60). */
        private const val KEEP_DAYS = 62L
        private const val FORCE_MIN_S = 600L
        /** Days shown hour by hour and day by day; earlier ones are shown by week. */
        private const val RECENT_DAYS = 7L
        /** Days whose daily record is rewritten from the hourly rows (later observations can fill gaps). */
        private const val SUMMARY_DAYS = 50L
    }
}
