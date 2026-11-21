package app.weathertrack.data

import android.content.Context
import androidx.room.withTransaction
import app.weathertrack.core.history.DailyObs
import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.openmeteo.OpenMeteoUrls
import app.weathertrack.data.db.AppDatabase
import app.weathertrack.data.db.ObservedDayEntity
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.data.db.RawPullEntity
import app.weathertrack.data.net.HttpFetcher
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Downloads observed daily history (back to 1940) from the Open-Meteo archive in 10-year chunks.
 * Every response is stored raw, then parsed into observed_day. Later syncs only fetch new days.
 */
class HistoryRepository(
    context: Context,
    private val db: AppDatabase,
    private val http: HttpFetcher,
) {
    private val dao = db.observedDayDao()
    private val rawDao = db.rawPullDao()
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun observeRange(placeId: Long) = dao.observeRange(placeId)

    suspend fun observeRangeOnce(placeId: Long) = dao.range(placeId)

    /** Whether a sync for this place ran recently enough to skip. */
    fun syncedRecently(placeId: Long): Boolean =
        System.currentTimeMillis() - prefs.getLong("synced_$placeId", 0L) < 12 * 3600_000L

    /**
     * Fetches every missing day up to two days ago. [onProgress] gets (chunks done, chunks total).
     * Returns the number of days stored.
     */
    suspend fun sync(place: PlaceEntity, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val end = LocalDate.now(ZoneOffset.UTC).minusDays(2)
        val start = dao.lastDate(place.id)?.let { LocalDate.parse(it).plusDays(1) } ?: FIRST_DAY
        if (start.isAfter(end)) {
            prefs.edit().putLong("synced_${place.id}", System.currentTimeMillis()).apply()
            return 0
        }
        val chunks = generateSequence(start) { it.plusYears(CHUNK_YEARS) }
            .takeWhile { !it.isAfter(end) }
            .map { from -> from to minOf(from.plusYears(CHUNK_YEARS).minusDays(1), end) }
            .toList()
        var stored = 0
        chunks.forEachIndexed { i, (from, to) ->
            onProgress(i, chunks.size)
            stored += fetchChunk(place, from, to)
        }
        onProgress(chunks.size, chunks.size)
        prefs.edit().putLong("synced_${place.id}", System.currentTimeMillis()).apply()
        return stored
    }

    private suspend fun fetchChunk(place: PlaceEntity, from: LocalDate, to: LocalDate): Int {
        val result = http.get(OpenMeteoUrls.archiveDaily(place.latitude, place.longitude, from.toString(), to.toString()))
        val pull = RawPullEntity(
            source = WeatherRepository.SOURCE, endpoint = ENDPOINT_ARCHIVE, placeId = place.id,
            latitude = place.latitude, longitude = place.longitude, url = result.url,
            fetchedAt = result.fetchedAtMs, httpStatus = result.status, durationMs = result.durationMs,
            trigger = "history", body = result.body,
        )
        if (result.status != 200) {
            rawDao.insert(pull)
            throw IOException("History download returned HTTP ${result.status}")
        }
        // Days the archive has not filled in yet come back empty; they are fetched again next time.
        val days = OpenMeteoParser.parseArchiveDaily(result.body).filter { it.hasData }
        return db.withTransaction {
            val pullId = rawDao.insert(pull)
            dao.insert(days.map { d ->
                ObservedDayEntity(
                    placeId = place.id, date = d.date, pullId = pullId, fetchedAt = result.fetchedAtMs / 1000,
                    temperatureMax = d.tempMax, temperatureMin = d.tempMin, temperatureMean = d.tempMean,
                    apparentTemperatureMax = d.feelsMax, apparentTemperatureMin = d.feelsMin,
                    precipitationSum = d.precipitation, rainSum = d.rain, precipitationHours = d.precipitationHours,
                    windGustsMax = d.gustMax, sunshineDuration = d.sunshineSeconds, weatherCode = d.weatherCode,
                )
            })
            days.size
        }
    }

    /** Every year of one calendar month for a place, ready for the history calculations. */
    suspend fun month(placeId: Long, monthValue: Int): List<DailyObs> =
        dao.forMonth(placeId, "%02d".format(monthValue)).map {
            DailyObs(LocalDate.parse(it.date), it.temperatureMax, it.temperatureMin, it.precipitationSum)
        }

    companion object {
        const val ENDPOINT_ARCHIVE = "archive"
        val FIRST_DAY: LocalDate = LocalDate.of(1940, 1, 1)
        private const val CHUNK_YEARS = 10L
    }
}
