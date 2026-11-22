package app.weathertrack.data

import android.util.Log
import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.accuracy.RainReport
import app.weathertrack.core.model.Forecast
import app.weathertrack.core.sources.BlendHour
import app.weathertrack.core.sources.IssuedSourceHour
import app.weathertrack.core.sources.SourceHour
import app.weathertrack.core.sources.SourceScore
import app.weathertrack.core.sources.SourceWeight
import app.weathertrack.core.sources.Sources
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.data.db.RawPullDao
import app.weathertrack.data.db.RawPullEntity
import app.weathertrack.data.db.SourceHourDao
import app.weathertrack.data.db.SourceHourEntity
import app.weathertrack.data.net.HttpFetcher
import app.weathertrack.data.net.HttpResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The latest forecast from every source, how each has done here, and the accuracy-weighted blend. */
data class SourceView(
    val latest: List<SourceHour>,
    val scores: List<SourceScore>,
    val weights: List<SourceWeight>,
    val blend: List<BlendHour>,
)

/**
 * Pulls the same place from several forecast sources, stores every hour as issued (and the raw
 * responses), and scores each source against what happened.
 */
class SourceRepository(private val http: HttpFetcher, private val rawDao: RawPullDao, private val dao: SourceHourDao) {
    private val lock = Mutex()

    /** Fetches sources that are due (models hourly, MET Norway every 3 hours as its terms ask). */
    suspend fun refresh(place: PlaceEntity, main: Forecast?, trigger: String) = lock.withLock {
        val now = System.currentTimeMillis() / 1000
        suspend fun due(key: String, everyS: Long) = (dao.lastIssued(place.id, key) ?: 0L) < now - everyS
        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<SourceHour>>>()
            if (main != null && due(Sources.MAIN.id, HOUR - 300)) {
                jobs += async { main.hourly.map { SourceHour(Sources.MAIN.id, it.time, it.temperature, it.precipitation, it.precipitationProbability) } }
            }
            if (due(Sources.MODELS.first().id, HOUR - 300)) jobs += async {
                fetch(place, "source_models", trigger, Sources.modelsUrl(place.latitude, place.longitude, Sources.MODELS),
                    fallback = Sources.modelsUrl(place.latitude, place.longitude, Sources.MODELS_SAFE)) { body ->
                    Sources.parseModels(body, Sources.MODELS)
                }
            }
            Sources.ENSEMBLES.forEach { e ->
                if (due(Sources.key(e), 3 * HOUR - 300)) jobs += async {
                    fetch(place, "source_ensemble_${e.id}", trigger, Sources.ensembleUrl(place.latitude, place.longitude, e)) { Sources.parseEnsemble(it, e) }
                }
            }
            if (due(Sources.METNO.id, 3 * HOUR)) jobs += async {
                fetch(place, "source_metno", trigger, Sources.metnoUrl(place.latitude, place.longitude)) { Sources.parseMetno(it) }
            }
            val rows = jobs.flatMap { runCatching { it.await() }.getOrDefault(emptyList()) }
                .filter { it.time in (now - HOUR)..(now + KEEP_AHEAD_H * HOUR) }
                .map { SourceHourEntity(placeId = place.id, source = it.source, issuedAt = now, time = it.time,
                    temperature = it.temperature, precipitation = it.precipitation, rainChance = it.rainChance) }
            if (rows.isNotEmpty()) dao.insertAll(rows)
        }
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
            .map { SourceHour(it.source, it.time, it.temperature, it.precipitation, it.rainChance) }

    suspend fun issued(placeId: Long, from: Long, to: Long): List<IssuedSourceHour> =
        dao.between(placeId, from, to).map { IssuedSourceHour(it.source, it.issuedAt, it.time, it.temperature, it.precipitation, it.rainChance) }

    /** Everything the Sources screen and Today need, scored over the last [days] days. */
    suspend fun view(placeId: Long, obs: List<ObsHour>, reports: List<RainReport>, days: Long = 60): SourceView {
        val now = System.currentTimeMillis() / 1000
        val latest = latest(placeId, now - HOUR)
        val scores = Sources.scores(issued(placeId, now - days * 86400, now), obs, reports)
        val keys = latest.map { it.source }.distinct()
        val weights = Sources.weights(keys, scores)
        return SourceView(latest, scores, weights, Sources.blend(latest, weights, now - HOUR))
    }

    companion object {
        private const val HOUR = 3600L
        /** Hours ahead stored from each pull: enough for "tomorrow" without filling the phone. */
        const val KEEP_AHEAD_H = 48
    }
}
