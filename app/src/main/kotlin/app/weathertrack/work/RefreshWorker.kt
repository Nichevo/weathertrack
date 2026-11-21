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
        return if (failures > 0 && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "RefreshWorker"
        private const val UNIQUE_NAME = "hourly-forecast-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
