package app.weathertrack.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.weathertrack.WeatherApp
import app.weathertrack.core.alerts.AlertDecider
import app.weathertrack.core.alerts.TriggerCalculator
import app.weathertrack.core.alerts.TriggerKind
import app.weathertrack.core.jacket.JacketCalculator
import app.weathertrack.core.jacket.Verdict
import app.weathertrack.data.StoredForecast
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.notify.Notifier
import app.weathertrack.settings.AppSettings
import app.weathertrack.ui.Format
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Schedules background work to match the current settings. */
object AlertScheduler {
    private const val ALERTS = "trigger-alerts"
    private const val MORNING = "morning-jacket"

    fun apply(context: Context, s: AppSettings) {
        val wm = WorkManager.getInstance(context)
        RefreshWorker.schedule(context, s.refreshMinutes)
        if (s.anyAlertOn) {
            wm.enqueueUniquePeriodicWork(
                ALERTS, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).setConstraints(network()).build(),
            )
        } else {
            wm.cancelUniqueWork(ALERTS)
        }
        if (s.notifyMorning) scheduleMorning(context, s.morningTime, ExistingWorkPolicy.REPLACE)
        else wm.cancelUniqueWork(MORNING)
    }

    fun scheduleMorning(context: Context, at: LocalTime, policy: ExistingWorkPolicy) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(at)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MORNING, policy,
            OneTimeWorkRequestBuilder<MorningWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

/** Shared: the selected place with a forecast no older than [maxAgeMinutes], refreshing if needed. */
private suspend fun freshForecast(app: WeatherApp, maxAgeMinutes: Int, trigger: String): Pair<PlaceEntity, StoredForecast>? {
    val c = app.container
    c.places.ensureDefaults()
    val place = c.places.selected() ?: return null
    val cached = c.weather.cachedForecast(place)
    if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < maxAgeMinutes * 60_000L) return place to cached
    val fresh = runCatching { c.weather.refreshForecast(place, trigger) }
        .onFailure { Log.w("Alerts", "Refresh failed, using cached forecast", it) }
        .getOrNull()
    return (fresh ?: cached)?.let { place to it }
}

/** Every 15 minutes while any alert is on: checks rain soon, too hot and too cold for the selected place. */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as WeatherApp
        val s = app.container.settings.current
        if (!s.anyAlertOn) return Result.success()
        val zoneNow = LocalTime.now()
        if (AlertDecider.isQuiet(zoneNow, s.quietStart, s.quietEnd)) return Result.success()

        // Rain soon needs a fresh 15-minute nowcast; heat and cold can use an hour-old forecast.
        val (place, stored) = freshForecast(app, if (s.notifyRainSoon) 10 else 60, "alert") ?: return Result.success()
        val f = stored.forecast
        val zone = Format.zone(f.timezone)
        val now = System.currentTimeMillis() / 1000
        val enabled = buildSet {
            if (s.notifyRainSoon) add(TriggerKind.RAIN_SOON)
            if (s.notifyHot) add(TriggerKind.TOO_HOT)
            if (s.notifyCold) add(TriggerKind.TOO_COLD)
        }
        val fired = TriggerCalculator.evaluate(f.hourly, f.quarterHourly, now, s.triggerThresholds) { Format.time(it, zone) }
            .filter { it.kind in enabled }

        val prefs = applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
        val active = prefs.getStringSet("active", emptySet())!!.mapNotNull { runCatching { TriggerKind.valueOf(it) }.getOrNull() }.toSet()
        val last = TriggerKind.entries.associateWith { prefs.getLong("last_$it", 0L) }
        val notify = AlertDecider.toNotify(fired.map { it.kind }.toSet(), active, last, now)

        val edit = prefs.edit().putStringSet("active", fired.map { it.kind.name }.toSet())
        fired.filter { it.kind in notify }.forEach { t ->
            val title = when (t.kind) {
                TriggerKind.RAIN_SOON -> "Rain soon in ${place.name}"
                TriggerKind.TOO_HOT -> "Too hot in ${place.name}"
                TriggerKind.TOO_COLD -> "Too cold in ${place.name}"
            }
            Notifier.show(applicationContext, 100 + t.kind.ordinal, Notifier.CHANNEL_ALERTS, title, t.text)
            edit.putLong("last_${t.kind}", now)
        }
        edit.apply()
        return Result.success()
    }
}

/** Once a day at the chosen time: notifies only when a jacket (or heat/cold care) is needed today. */
class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as WeatherApp
        val s = app.container.settings.current
        try {
            if (!s.notifyMorning) return Result.success()
            val (place, stored) = freshForecast(app, 30, "morning") ?: return Result.success()
            val f = stored.forecast
            val zone = Format.zone(f.timezone)
            val now = System.currentTimeMillis() / 1000
            val day = JacketCalculator.evaluate(f.hourly, now, DAY_HOURS, emptyList(), s.jacketThresholds)
            val heatCold = TriggerCalculator.evaluate(
                f.hourly, emptyList(), now, s.triggerThresholds.copy(temperatureWindowHours = DAY_HOURS),
            ) { Format.time(it, zone) }.filter { it.kind != TriggerKind.RAIN_SOON }

            if (day.verdict == Verdict.NO && heatCold.isEmpty()) return Result.success()
            val title = when (day.verdict) {
                Verdict.YES -> "Take a jacket today"
                Verdict.MAYBE -> "Maybe a jacket today"
                Verdict.NO -> heatCold.first().text.substringBefore(':')
            }
            val peakRain = f.hourly
                .filter { it.time + 3600 > now && it.time < now + DAY_HOURS * 3600L }
                .maxByOrNull { it.precipitationProbability ?: 0.0 }
                ?.takeIf { (it.precipitationProbability ?: 0.0) >= 20 }
            val lines = buildList {
                addAll(day.reasons.map { it.text })
                peakRain?.let { add("Wettest around ${Format.time(it.time, zone)}") }
                addAll(heatCold.map { it.text })
            }
            Notifier.show(applicationContext, 200, Notifier.CHANNEL_MORNING, "$title · ${place.name}", lines.joinToString(". "))
            return Result.success()
        } finally {
            // Chain tomorrow's run whatever happened today.
            if (s.notifyMorning) AlertScheduler.scheduleMorning(applicationContext, s.morningTime, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    private companion object {
        const val DAY_HOURS = 12
    }
}

