package app.weathertrack.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.weathertrack.WeatherApp
import java.util.concurrent.TimeUnit

/**
 * Pulls a forecast for every saved place about once an hour, so there is a continuous record of
 * forecasts as issued for the forecast-accuracy check.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WeatherApp).container
        container.places.ensureDefaults()
        var failures = 0
        for (place in container.places.all()) {
            runCatching { container.weather.refreshForecast(place, trigger = "background") }
                .onFailure { failures++; Log.w(TAG, "Refresh failed for place ${place.id}", it) }
        }
        val places = container.places.all()
        // Every forecast source for the selected place, and for any place set to save them, so their
        // records build up for the blend and the source scores.
        for (place in places.filter { container.places.savesSources(it.id) }) {
            runCatching { container.sources.refresh(place, container.weather.cachedForecast(place), "background") }
                .onFailure { Log.w(TAG, "Sources refresh failed for place ${place.id}", it) }
        }
        // Actuals, earlier runs, home climate and archive history for every saved place, so each one's
        // record builds up without opening it. Each repository skips a place it synced in the last 6 to 12 hours.
        for (place in places) {
            if (!container.forecastCheck.syncedRecently(place.id)) {
                runCatching { container.forecastCheck.sync(place) }.onFailure { Log.w(TAG, "Check sync failed for place ${place.id}", it) }
            }
            if (!container.climate.syncedRecently(place.id)) {
                runCatching { container.climate.sync(place) }.onFailure { Log.w(TAG, "Climate sync failed for place ${place.id}", it) }
            }
        }
        // Archive history last: a new place's first download (back to 1940) is the longest. It stores each
        // chunk as it arrives, newest first, so a run cut short carries on next time.
        for (place in places) {
            if (!container.history.syncedRecently(place.id)) {
                runCatching { container.history.sync(place) }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "History sync failed for place ${place.id}", it)
                }
            }
        }
        return if (failures > 0 && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "RefreshWorker"
        private const val UNIQUE_NAME = "hourly-forecast-refresh"

        fun schedule(context: Context, minutes: Int = 60) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                // The hourly record can skip a pull or two when the battery is low.
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
