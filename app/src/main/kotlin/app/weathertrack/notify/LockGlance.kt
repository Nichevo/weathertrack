package app.weathertrack.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.weathertrack.MainActivity
import app.weathertrack.R
import app.weathertrack.WeatherApp
import app.weathertrack.core.alerts.TriggerCalculator
import app.weathertrack.core.alerts.TriggerKind
import app.weathertrack.core.jacket.JacketCalculator
import app.weathertrack.core.jacket.Verdict
import app.weathertrack.core.walk.WalkPlanner
import app.weathertrack.core.walk.WalkRating
import app.weathertrack.core.model.WeatherCodes
import app.weathertrack.ui.Format
import kotlinx.coroutines.launch

/**
 * A quiet, always-there notification that shows the weather on the lock screen, since apps can't put
 * widgets there. Refreshed with every stored forecast; turned on and off from Settings, the quick-settings
 * tile, or its own "Turn off" button.
 */
object LockGlance {
    const val CHANNEL = "lock_glance"
    private const val ID = 7001
    const val ACTION_OFF = "app.weathertrack.action.LOCK_GLANCE_OFF"

    fun createChannel(context: Context) {
        // Default importance (not "silent"), so phones that hide silent notifications on the lock screen still show it,
        // but with no sound or vibration.
        val ch = NotificationChannel(CHANNEL, "Weather on lock screen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A quiet weather summary on the lock screen, updated with each forecast"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Turns it on or off from anywhere (tile, notification button). Settings shows the same switch. */
    fun setOn(context: Context, on: Boolean) {
        val app = context.applicationContext as WeatherApp
        app.container.settings.update { it.copy(lockGlance = on) }
        // The container watches the setting and shows or clears the notification.
        refreshTile(context)
    }

    fun refreshTile(context: Context) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, LockGlanceTile::class.java)) }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    /** Shows (or refreshes) the notification if it is on, and clears it if not. */
    @Suppress("MissingPermission")
    suspend fun update(context: Context) {
        val c = (context.applicationContext as WeatherApp).container
        val cfg = c.settings.current
        if (!cfg.lockGlance) { cancel(context); return }
        if (!Notifier.canNotify(context)) return
        val place = c.places.selected() ?: return
        val stored = c.weather.cachedForecast(place) ?: return
        val f = stored.forecast
        val zone = Format.zone(f.timezone)
        val now = System.currentTimeMillis() / 1000
        val cur = f.current
        val today = f.daily.firstOrNull { it.date == java.time.LocalDate.now(zone).toString() }

        val jacket = when (JacketCalculator.evaluate(f.hourly, now, 3, f.quarterHourly, cfg.jacketThresholds).verdict) {
            Verdict.YES -> "Take a jacket"
            Verdict.MAYBE -> "Maybe jacket"
            Verdict.NO -> "No jacket"
        }
        val title = "${Format.temp(cur?.temperature)} ${WeatherCodes.describe(cur?.weatherCode)} · $jacket"

        val parts = mutableListOf<String>()
        val triggers = TriggerCalculator.evaluate(f.hourly, f.quarterHourly, now, cfg.triggerThresholds) { Format.hour(it, zone) }
        val rainSoon = triggers.firstOrNull { it.kind == TriggerKind.RAIN_SOON }
        if (rainSoon != null) parts += rainSoon.text
        else {
            val next6 = f.hourly.filter { it.time + 3600 > now && it.time <= now + 6 * 3600 }
            val peak = next6.maxByOrNull { it.precipitationProbability ?: 0.0 }
            parts += if (peak == null || (peak.precipitationProbability ?: 0.0) < 20) "Dry next 6 h"
            else "Rain ${Format.pct(peak.precipitationProbability)} at ${Format.hour(peak.time, zone).replace(" ", "").lowercase()}"
        }
        today?.let { parts += "${Format.temp(it.temperatureMax)}/${Format.temp(it.temperatureMin)}" }
        WalkPlanner.upcoming(f.hourly, cfg.walkWindows, zone, now, cfg.walkThresholds, count = 1).firstOrNull()?.let { w ->
            val word = when (w.rating) { WalkRating.GOOD -> "good"; WalkRating.FAIR -> "take care"; WalkRating.POOR -> "poor" }
            parts += (if (w.window.name.startsWith("Morning")) "AM walk " else "PM walk ") + word
        }
        triggers.firstOrNull { it.kind == TriggerKind.TOO_HOT }?.let { parts += "Hot ${Format.temp(it.value)}" }
        triggers.firstOrNull { it.kind == TriggerKind.TOO_COLD }?.let { parts += "Cold ${Format.temp(it.value)}" }
        val text = parts.joinToString(" · ")

        val open = PendingIntent.getActivity(
            context, 70, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val off = PendingIntent.getBroadcast(
            context, 71, Intent(context, LockGlanceOffReceiver::class.java).setAction(ACTION_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("${place.name} · ${Format.time(stored.fetchedAtMs / 1000, zone)}")
            .setContentIntent(open)
            .addAction(0, "Turn off", off)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, n) }.onFailure { Log.w("LockGlance", "Notify failed", it) }
    }
}

/** The notification's "Turn off" button. */
class LockGlanceOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LockGlance.ACTION_OFF) {
            LockGlance.cancel(context)
            LockGlance.setOn(context, false)
        }
    }
}

/** Quick-settings tile: one tap in the pull-down shade turns the lock screen weather on or off. */
class LockGlanceTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        show()
    }

    override fun onClick() {
        super.onClick()
        val c = (applicationContext as WeatherApp).container
        val turningOn = !c.settings.current.lockGlance
        if (turningOn && !Notifier.canNotify(this)) {
            // Notifications are off for the app: open it so they can be allowed (Settings asks).
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 72, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
            return
        }
        LockGlance.setOn(this, turningOn)
        show(turningOn)
        c.appScope.launch { LockGlance.update(applicationContext) }
    }

    private fun show(on: Boolean = (applicationContext as WeatherApp).container.settings.current.lockGlance) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.lock_glance_tile)
        tile.updateTile()
    }
}
