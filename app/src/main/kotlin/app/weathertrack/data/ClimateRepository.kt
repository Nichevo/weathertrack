package app.weathertrack.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import app.weathertrack.core.climate.ClimateDay
import app.weathertrack.core.climate.EnergyCsv
import app.weathertrack.core.climate.EnergyImport
import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.openmeteo.OpenMeteoUrls
import app.weathertrack.data.db.AppDatabase
import app.weathertrack.data.db.ClimateDayEntity
import app.weathertrack.data.db.EnergyDayEntity
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.data.db.RawPullEntity
import app.weathertrack.data.net.HttpFetcher
import app.weathertrack.data.net.HttpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Daily climate for the Home view (from 1991, so there is a 1991–2020 normal) and imported
 * household power use. Every archive response is stored raw first, like the rest of the app.
 */
class ClimateRepository(
    context: Context,
    private val db: AppDatabase,
    private val http: HttpFetcher,
) {
    private val appContext = context.applicationContext
    private val dao = db.climateDayDao()
    private val energyDao = db.energyDayDao()
    private val rawDao = db.rawPullDao()
    private val prefs = context.getSharedPreferences("climate", Context.MODE_PRIVATE)

    fun observeRange(placeId: Long) = dao.observeRange(placeId)
    fun observeEnergyRange() = energyDao.observeRange()

    fun syncedRecently(placeId: Long): Boolean =
        System.currentTimeMillis() - prefs.getLong("synced_$placeId", 0L) < 12 * 3600_000L

    /** Fetches every missing day up to two days ago. Returns the number of days stored. */
    suspend fun sync(place: PlaceEntity, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val end = LocalDate.now(ZoneOffset.UTC).minusDays(2)
        val start = dao.lastDate(place.id)?.let { LocalDate.parse(it).plusDays(1) } ?: FIRST_DAY
        var stored = 0
        if (!start.isAfter(end)) {
            val chunks = generateSequence(start) { it.plusYears(CHUNK_YEARS) }
                .takeWhile { !it.isAfter(end) }
                .map { from -> from to minOf(from.plusYears(CHUNK_YEARS).minusDays(1), end) }
                .toList()
            chunks.forEachIndexed { i, (from, to) ->
                onProgress(i, chunks.size)
                stored += fetchChunk(place, from, to)
            }
            onProgress(chunks.size, chunks.size)
        }
        prefs.edit().putLong("synced_${place.id}", System.currentTimeMillis()).apply()
        return stored
    }

    private suspend fun fetchChunk(place: PlaceEntity, from: LocalDate, to: LocalDate): Int {
        var result = fetch(place, from, to, OpenMeteoUrls.CLIMATE_DAILY_VARS)
        if (result.status == 400) {
            // The archive rejected a variable: fall back to the core set (no humidity or cloud).
            result = fetch(place, from, to, OpenMeteoUrls.CLIMATE_SAFE_DAILY_VARS)
        }
        if (result.status != 200) throw IOException("Climate download returned HTTP ${result.status}")
        // Days the archive has not filled in yet come back empty; they are fetched again next time.
        val days = OpenMeteoParser.parseClimateDaily(result.body).filter { it.tempMean != null }
        return db.withTransaction {
            val pullId = rawDao.insert(raw(place, result))
            dao.insert(days.map { d ->
                ClimateDayEntity(
                    placeId = place.id, date = d.date.toString(), pullId = pullId, fetchedAt = result.fetchedAtMs / 1000,
                    temperatureMean = d.tempMean, temperatureMax = d.tempMax, temperatureMin = d.tempMin,
                    relativeHumidityMean = d.humidityMean, dewPointMean = d.dewPointMean,
                    shortwaveRadiationSum = d.solarMj, sunshineDuration = d.sunshineSeconds, cloudCoverMean = d.cloudMean,
                )
            })
            days.size
        }
    }

    private suspend fun fetch(place: PlaceEntity, from: LocalDate, to: LocalDate, vars: List<String>) =
        http.get(OpenMeteoUrls.archiveClimate(place.latitude, place.longitude, from.toString(), to.toString(), vars))
            .also { if (it.status != 200) rawDao.insert(raw(place, it)) }

    private fun raw(place: PlaceEntity, result: HttpResult) = RawPullEntity(
        source = WeatherRepository.SOURCE, endpoint = ENDPOINT_CLIMATE, placeId = place.id,
        latitude = place.latitude, longitude = place.longitude, url = result.url,
        fetchedAt = result.fetchedAtMs, httpStatus = result.status, durationMs = result.durationMs,
        trigger = "climate", body = result.body,
    )

    suspend fun days(placeId: Long): List<ClimateDay> = dao.all(placeId).map {
        ClimateDay(
            LocalDate.parse(it.date), it.temperatureMean, it.temperatureMax, it.temperatureMin,
            it.relativeHumidityMean, it.dewPointMean, it.shortwaveRadiationSum, it.sunshineDuration, it.cloudCoverMean,
        )
    }

    suspend fun energy(): Map<LocalDate, Double> = energyDao.all().associate { LocalDate.parse(it.date) to it.kwh }

    /** Reads a power-usage CSV and stores kWh per day, replacing days already stored. Null if unreadable. */
    suspend fun importEnergy(uri: Uri): EnergyImport? = withContext(Dispatchers.IO) {
        val name = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val text = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return@withContext null
        val parsed = EnergyCsv.parse(text) ?: return@withContext null
        val now = System.currentTimeMillis()
        energyDao.insert(parsed.days.map { (d, kwh) -> EnergyDayEntity(d.toString(), kwh, name, now) })
        parsed
    }

    suspend fun clearEnergy() = energyDao.clear()

    companion object {
        const val ENDPOINT_CLIMATE = "archive_climate"
        val FIRST_DAY: LocalDate = LocalDate.of(1991, 1, 1)
        private const val CHUNK_YEARS = 10L
    }
}
