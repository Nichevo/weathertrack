package app.weathertrack.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.weathertrack.WeatherApp
import app.weathertrack.core.storage.KeepOlder
import app.weathertrack.core.storage.PullRef
import app.weathertrack.core.storage.Storage
import app.weathertrack.data.db.AppDatabase
import java.util.concurrent.TimeUnit

/** What a tidy-up did. */
data class TidyResult(val compressed: Int, val savedBytes: Long, val thinnedPulls: Int, val failedDeleted: Int, val at: Long) {
    fun describe(): String = buildList {
        if (compressed > 0) add("compressed $compressed older responses (saved ${app.weathertrack.ui.Format.bytes(savedBytes)})")
        if (thinnedPulls > 0) add("thinned $thinnedPulls extra forecasts older than ${Storage.FULL_DETAIL_DAYS} days")
        if (failedDeleted > 0) add("removed $failedDeleted failed downloads")
    }.joinToString(", ").ifEmpty { "nothing needed tidying" }.replaceFirstChar { it.uppercase() }
}

/**
 * Keeps the database from growing without limit, without losing weather data:
 * - raw responses older than 30 days are gzipped in place (about 10x smaller, still exportable);
 * - forecasts older than 30 days are thinned to one per hour per place (or as set; "Every forecast" keeps all);
 * - failed downloads older than 30 days are removed (they hold no weather).
 */
class StorageMaintenance(private val context: Context, private val db: AppDatabase) {
    private val prefs = context.getSharedPreferences("storage", Context.MODE_PRIVATE)

    fun last(): TidyResult? {
        val at = prefs.getLong("at", 0L).takeIf { it > 0 } ?: return null
        return TidyResult(prefs.getInt("compressed", 0), prefs.getLong("saved", 0), prefs.getInt("thinned", 0), prefs.getInt("failed", 0), at)
    }

    suspend fun run(keep: KeepOlder, vacuum: Boolean): TidyResult {
        val raw = db.rawPullDao()
        val now = System.currentTimeMillis()
        val day = 86_400_000L

        // 1. Thin old forecasts (and their raw responses) so a 15-minute alert cadence doesn't pile up.
        var thinned = 0
        keep.bucketMs?.let { bucket ->
            val cutoff = now - Storage.FULL_DETAIL_DAYS * day
            val refs = raw.pullsBefore(WeatherRepository.SOURCE, WeatherRepository.ENDPOINT_FORECAST, cutoff)
                .map { PullRef(it.id, it.placeId, it.fetchedAt) }
            Storage.thin(refs, cutoff, bucket).chunked(500).forEach { ids ->
                db.withTransaction {
                    db.forecastDao().deleteHoursOf(ids)
                    db.forecastDao().deleteDaysOf(ids)
                    raw.deleteIds(ids)
                }
                thinned += ids.size
            }
        }

        // 2. Failed downloads.
        val failed = raw.deleteFailedBefore(now - Storage.FAILED_KEEP_DAYS * day)

        // 3. Gzip older responses in batches.
        var compressed = 0
        var saved = 0L
        val before = now - Storage.COMPRESS_AFTER_DAYS * day
        while (true) {
            val batch = raw.uncompressed(before, 100)
            if (batch.isEmpty()) break
            db.withTransaction {
                batch.forEach { row ->
                    val gz = Storage.gzip(row.body)
                    raw.setCompressed(row.id, gz)
                    saved += row.body.toByteArray(Charsets.UTF_8).size - gz.size
                }
            }
            compressed += batch.size
        }

        // 4. Give the space back to the phone after a big change.
        if (vacuum && (saved > 5_000_000 || thinned > 200)) {
            runCatching { db.openHelper.writableDatabase.execSQL("VACUUM") }.onFailure { Log.w("Storage", "VACUUM failed", it) }
        }
        val result = TidyResult(compressed, saved, thinned, failed, now)
        prefs.edit().putLong("at", now).putInt("compressed", compressed).putLong("saved", saved)
            .putInt("thinned", thinned).putInt("failed", failed).apply()
        return result
    }

    /** Size of the database file and its write-ahead log. */
    fun fileBytes(): Long {
        val f = context.getDatabasePath(AppDatabase.NAME)
        return f.length() + java.io.File(f.path + "-wal").length()
    }
}

/** Weekly tidy-up, only while charging and idle so it never costs battery. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as WeatherApp).container
        return runCatching { c.storage.run(KeepOlder.of(c.settings.current.keepOlder), vacuum = true) }
            .fold({ Result.success() }, { Log.w("Storage", "Tidy-up failed", it); Result.retry() })
    }

    companion object {
        private const val NAME = "storage-tidy"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintenanceWorker>(7, TimeUnit.DAYS).setConstraints(constraints).build(),
            )
        }
    }
}
