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
import app.weathertrack.core.accuracy.Confidence
import app.weathertrack.core.accuracy.Feedback
import app.weathertrack.core.accuracy.RainAdjust
import app.weathertrack.core.alerts.AlertDecider
import app.weathertrack.core.alerts.Trigger
import app.weathertrack.core.alerts.TriggerCalculator
import app.weathertrack.core.alerts.TriggerKind
import app.weathertrack.core.jacket.JacketCalculator
import app.weathertrack.core.jacket.Verdict
import app.weathertrack.core.walk.WalkPlanner
import app.weathertrack.core.walk.WalkRating
import app.weathertrack.data.StoredForecast
import app.weathertrack.data.highBands
import app.weathertrack.data.trustRecord
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
        app.weathertrack.backup.BackupWorker.schedule(context, s.backupWeekly)
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
        val quiet = AlertDecider.isQuiet(LocalTime.now(), s.quietStart, s.quietEnd)
        val walkDue = s.notifyWalks && walkHeadsUpDue(s)
        // Red and orange warnings get through quiet hours; others wait.
        if (s.notifyWarnings) runCatching { warningAlerts(app, quiet) }.onFailure { Log.w("Alerts", "Warnings check failed", it) }
        // Walk heads-ups follow your own walk times, so they are allowed in quiet hours.
        if (quiet && !walkDue) return Result.success()

        // Rain soon needs a fresh 15-minute nowcast; heat and cold can use an hour-old forecast.
        val (place, stored) = freshForecast(app, if (s.notifyRainSoon || walkDue) 10 else 60, "alert") ?: return Result.success()
        val f = stored.forecast
        val zone = Format.zone(f.timezone)
        val now = System.currentTimeMillis() / 1000
        if (walkDue) walkHeadsUp(place.name, f, zone, now, s)
        if (quiet) return Result.success()
        if (s.notifyColdNight) coldNight(place.name, f, zone, now, s.coldNightTemp.toDouble())
        val enabled = buildSet {
            if (s.notifyRainSoon) add(TriggerKind.RAIN_SOON)
            if (s.notifyHot) add(TriggerKind.TOO_HOT)
            if (s.notifyCold) add(TriggerKind.TOO_COLD)
        }
        val model = TriggerCalculator.evaluate(f.hourly, f.quarterHourly, now, s.triggerThresholds) { Format.time(it, zone) }
            .filter { it.kind in enabled }
        val fired = if (TriggerKind.RAIN_SOON in enabled) checkRain(app, place, model, now) else model

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
            val text = if (t.kind == TriggerKind.RAIN_SOON) t.text + adjustedNote(app, place, now) else t.text
            Notifier.show(applicationContext, 100 + t.kind.ordinal, Notifier.CHANNEL_ALERTS, title, text)
            edit.putLong("last_${t.kind}", now)
        }
        edit.apply()
        return Result.success()
    }
}

/** Each new official warning for the selected place, once. */
private suspend fun CoroutineWorker.warningAlerts(app: WeatherApp, quiet: Boolean) {
    val place = app.container.places.selected() ?: return
    val warnings = app.container.extras.warnings(place, "alert")
    val prefs = applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
    val seen = prefs.getStringSet("warnings_seen", emptySet())!!.toMutableSet()
    val zone = Format.zone(place.timezone)
    warnings.filter { it.id !in seen && (!quiet || it.serious) }.forEach { w ->
        val until = w.expires?.let { " until ${Format.time(it, zone)}" } ?: ""
        Notifier.show(
            applicationContext, 400 + (w.id.hashCode() and 0xff), Notifier.CHANNEL_ALERTS,
            "${w.headline.ifBlank { w.event }} · ${place.name}", (w.description.ifBlank { w.event } + until).take(400),
        )
        seen += w.id
    }
    // Forget warnings no longer current so the set stays small (kept as is if nothing came back).
    val keep = if (warnings.isEmpty()) seen.toSet() else seen.filter { id -> warnings.any { it.id == id } }.toSet()
    prefs.edit().putStringSet("warnings_seen", keep).apply()
}

/** Evening heads-up (5 to 10 pm) when tonight is cold or frosty, once a night. */
private fun CoroutineWorker.coldNight(placeName: String, f: app.weathertrack.core.model.Forecast, zone: java.time.ZoneId, now: Long, threshold: Double) {
    val local = java.time.Instant.ofEpochSecond(now).atZone(zone)
    if (local.hour !in 17..21) return
    val prefs = applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
    val key = "cold_night_${local.toLocalDate()}"
    if (prefs.getBoolean(key, false)) return
    val night = app.weathertrack.core.outdoor.Frost.tonight(f.hourly, now, zone, threshold) ?: return
    prefs.edit().putBoolean(key, true).apply()
    val title = (if (night.frost) "Frost likely tonight" else "Cold night") + " · $placeName"
    val tips = if (night.frost) ". Cover tender plants and check the car windscreen" else ""
    Notifier.show(applicationContext, 500, Notifier.CHANNEL_ALERTS, title, night.text(Format.time(night.at, zone)) + tips)
}

/**
 * Checks the model's "rain soon" against the radar and your own report:
 * your "Raining"/"Dry" report from the last 45 minutes wins, then rain on the radar at your spot,
 * then showers on the radar nearby. A model alert with clear radar all around says it may not happen.
 */
private suspend fun checkRain(app: WeatherApp, place: PlaceEntity, model: List<Trigger>, now: Long): List<Trigger> {
    val c = app.container
    val mine = runCatching { Feedback.current(c.forecastCheck.reports(place.id, now - 3600), now) }.getOrNull()
    val others = model.filter { it.kind != TriggerKind.RAIN_SOON }
    val rain = model.firstOrNull { it.kind == TriggerKind.RAIN_SOON }
    // You said it's already raining: no point telling you rain is coming.
    if (mine?.raining == true) return others
    val radar = runCatching { c.radarSpot.check(place.latitude, place.longitude) }.getOrNull()
    val checked = when {
        radar?.wetHere == true && mine == null ->
            Trigger(TriggerKind.RAIN_SOON, radar.time, rain?.value ?: 0.0, "Raining here now on the radar")
        rain != null && mine?.raining == false && radar?.wetNearby != true ->
            rain.copy(text = rain.text + ". You said it's dry and the radar is clear nearby, so it may hold off")
        rain != null && radar != null && !radar.wetNearby ->
            rain.copy(text = rain.text + ". The radar is clear nearby, so it may not happen")
        rain != null && radar?.wetNearby == true ->
            rain.copy(text = rain.text + ". Showers on the radar within about 10 km")
        rain == null && radar?.wetNearby == true ->
            Trigger(TriggerKind.RAIN_SOON, radar.time, 0.0, "Showers on the radar within about 10 km, though the forecast is dry")
        else -> rain
    }
    return others + listOfNotNull(checked)
}

/** ". Adjusted for here: about 35%" when the stored record is long enough to say so, else "". */
private suspend fun adjustedNote(app: WeatherApp, place: PlaceEntity, now: Long): String = runCatching {
    val s = app.container.settings.current
    val f = app.container.weather.cachedForecast(place)?.forecast ?: return ""
    val j = JacketCalculator.evaluate(f.hourly, now, 3, f.quarterHourly, s.jacketThresholds)
    val chance = j.maxRainProbability ?: return ""
    val trust = app.container.forecastCheck.trustRecord(place, s.jacketThresholds, s.morningTime.hour)
    if (Feedback.calibrated(trust.reliability, chance) == null) return ""
    val adj = RainAdjust.adjusted(chance, trust.reliability) ?: return ""
    if (kotlin.math.abs(adj - chance) < 5) "" else ". Forecast ${Format.pct(chance)}, adjusted for here about ${Format.pct(adj)}"
}.getOrDefault("")

/** Prefs key for "this walk window on this date was already announced". */
private fun walkKey(date: java.time.LocalDate, index: Int) = "walk_${date}_$index"

/** True when a walk window starts within the lead time (device local time) and hasn't been announced. */
private fun CoroutineWorker.walkHeadsUpDue(s: AppSettings): Boolean {
    val prefs = applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
    val now = LocalDateTime.now()
    return s.walkWindows.withIndex().any { (i, w) ->
        val start = now.toLocalDate().atTime(w.start)
        !now.isBefore(start.minusMinutes(s.walkLeadMinutes.toLong())) && now.isBefore(start.plusMinutes(15)) &&
            !prefs.getBoolean(walkKey(now.toLocalDate(), i), false)
    }
}

private fun CoroutineWorker.walkHeadsUp(placeName: String, f: app.weathertrack.core.model.Forecast, zone: java.time.ZoneId, now: Long, s: AppSettings) {
    val prefs = applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
    val today = LocalDateTime.now().toLocalDate()
    val nowLocal = LocalDateTime.now()
    s.walkWindows.withIndex().forEach { (i, w) ->
        val start = today.atTime(w.start)
        val due = !nowLocal.isBefore(start.minusMinutes(s.walkLeadMinutes.toLong())) && nowLocal.isBefore(start.plusMinutes(15))
        if (!due || prefs.getBoolean(walkKey(today, i), false)) return@forEach
        prefs.edit().putBoolean(walkKey(today, i), true).apply()
        val startEpoch = today.atTime(w.start).atZone(zone).toEpochSecond()
        var endEpoch = today.atTime(w.end).atZone(zone).toEpochSecond()
        if (endEpoch <= startEpoch) endEpoch += 24 * 3600
        val walk = WalkPlanner.forecast(f.hourly, w, today, maxOf(startEpoch, now), endEpoch, s.walkThresholds) ?: return@forEach
        if (s.walkNotifyOnlyIfNotGood && walk.rating == WalkRating.GOOD) return@forEach
        val verdict = when (walk.rating) {
            WalkRating.GOOD -> "looks good"
            WalkRating.FAIR -> "OK, with care"
            WalkRating.POOR -> "not great"
        }
        val details = buildList {
            addAll(walk.issues.map { it.text })
            walk.bestHour?.let { add("Best from ${Format.time(it, zone)}") }
            add("Feels ${Format.temp(walk.feelsMin)}–${Format.temp(walk.feelsMax)}, humidity ${Format.pct(walk.humidityMax)}")
            add("Cloud low ${Format.pct(walk.cloudLow)}, mid ${Format.pct(walk.cloudMid)}, high ${Format.pct(walk.cloudHigh)}")
        }
        Notifier.show(applicationContext, 300 + i, Notifier.CHANNEL_ALERTS, "${w.name} $verdict · $placeName", details.joinToString(". "))
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
                addAll(morningSureness(app, place, f, now, day.maxRainProbability))
            }
            Notifier.show(applicationContext, 200, Notifier.CHANNEL_MORNING, "$title · ${place.name}", lines.joinToString(". "))
            return Result.success()
        } finally {
            // Chain tomorrow's run whatever happened today.
            if (s.notifyMorning) AlertScheduler.scheduleMorning(applicationContext, s.morningTime, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    /** How sure the morning call is: rain adjusted for here, today's likely high range, and whether models disagree. */
    private suspend fun morningSureness(
        app: WeatherApp, place: PlaceEntity, f: app.weathertrack.core.model.Forecast, now: Long, chance: Double?,
    ): List<String> = buildList {
        val c = app.container
        val s = c.settings.current
        val zone = Format.zone(f.timezone)
        val today = java.time.LocalDate.now(zone).toString()
        runCatching {
            val trust = c.forecastCheck.trustRecord(place, s.jacketThresholds, s.morningTime.hour)
            if (chance != null && Feedback.calibrated(trust.reliability, chance) != null) {
                RainAdjust.adjusted(chance, trust.reliability)?.let { adj ->
                    if (kotlin.math.abs(adj - chance) >= 5) add("Rain chance adjusted for here: about ${Format.pct(adj)}")
                }
            }
        }
        runCatching {
            val high = f.daily.firstOrNull { it.date == today }?.temperatureMax
            val band = c.forecastCheck.highBands(place)[0]
            if (high != null && band != null && band.confidence != Confidence.UNKNOWN) {
                add("High ${Format.temp(high)}, likely ${Format.temp(high + band.low)} to ${Format.temp(high + band.high)}")
            }
        }
        runCatching { c.extras.warnings(place, "morning").forEach { add(it.headline.ifBlank { it.event }) } }
        runCatching {
            c.extras.spread(place, "morning").firstOrNull { it.date == today }?.let { d ->
                if (d.highsDisagree) add("Models disagree on the high (${Format.temp(d.highMin)} to ${Format.temp(d.highMax)})")
                if (d.rainDisagrees) add("Models split on rain: ${d.wetModels} of ${d.models} wet")
            }
        }
    }

    private companion object {
        const val DAY_HOURS = 12
    }
}

