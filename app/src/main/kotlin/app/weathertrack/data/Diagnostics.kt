package app.weathertrack.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkManager
import app.weathertrack.BuildConfig
import app.weathertrack.data.db.AppDatabase
import app.weathertrack.ui.Format
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The details needed to fix a problem, without anything personal: no place names, coordinates,
 * accounts or keys. Also keeps the last crash on the phone so it can be included.
 */
class Diagnostics(private val context: Context, private val db: AppDatabase, private val storage: StorageMaintenance) {

    suspend fun report(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { build() }

    private suspend fun build(): String {
        val raw = db.rawPullDao()
        val now = System.currentTimeMillis()
        val s = (context.applicationContext as app.weathertrack.WeatherApp).container.settings.current
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sb = StringBuilder()
        sb.appendLine("App ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}), database v${AppDatabase.VERSION}")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Battery saver ${onOff(pm.isPowerSaveMode)}, ignoring battery optimisation ${onOff(pm.isIgnoringBatteryOptimizations(context.packageName))}" +
            (if (Build.VERSION.SDK_INT >= 28) ", background restricted ${onOff(am.isBackgroundRestricted)}" else ""))
        sb.appendLine("Notifications allowed: ${onOff(app.weathertrack.notify.Notifier.canNotify(context))}")
        sb.appendLine("Places saved: ${runCatching { db.placeDao().count() }.getOrDefault(-1)}")
        sb.appendLine("Settings: refresh ${s.refreshMinutes} min, alerts ${onOff(s.anyAlertOn)}, lock screen ${onOff(s.lockGlance)}, " +
            "weekly backup ${onOff(s.backupWeekly)}, keep older forecasts: ${s.keepOlder}")
        sb.appendLine()
        sb.appendLine("Storage: database ${Format.bytes(storage.fileBytes())}" +
            (storage.last()?.let { ", last tidy-up ${Format.ago(it.at)}: ${it.describe()}" } ?: ", not tidied yet"))
        sb.appendLine()
        sb.appendLine("Downloads in the last 24 hours:")
        raw.downloadsSince(now - 86_400_000L).forEach { sb.appendLine("  ${it.endpoint}: ${it.pulls} (${Format.bytes(it.bytes)})") }
        sb.appendLine()
        sb.appendLine("Last good download:")
        listOf(WeatherRepository.ENDPOINT_FORECAST, ForecastCheckRepository.ENDPOINT_PAST, ForecastCheckRepository.ENDPOINT_RUNS,
            "source_models", "source_metno", ExtrasRepository.ENDPOINT_TIDES, HistoryRepository.ENDPOINT_ARCHIVE).forEach { e ->
            sb.appendLine("  $e: ${raw.lastSuccess(e)?.let { Format.ago(it) } ?: "never"}")
        }
        val failures = raw.recentFailures(10)
        if (failures.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Recent failed downloads:")
            failures.forEach { sb.appendLine("  ${it.endpoint}: HTTP ${it.status}, ${Format.ago(it.fetchedAt)}") }
        }
        sb.appendLine()
        sb.appendLine("Background work:")
        WORK.forEach { name ->
            val infos = runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get() }.getOrDefault(emptyList())
            sb.appendLine("  $name: " + (infos.lastOrNull()?.let { "${it.state.name.lowercase()}, tried ${it.runAttemptCount}x" } ?: "not scheduled"))
        }
        lastCrash(context)?.let {
            sb.appendLine()
            sb.appendLine("Last crash:")
            sb.appendLine(it.take(4000))
        }
        return sb.toString()
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    companion object {
        private val WORK = listOf("hourly-forecast-refresh", "trigger-alerts", "morning-jacket", "dropbox-backup", "storage-tidy", "rain-widget-render")
        private const val CRASH_FILE = "last_crash.txt"

        /** Saves an uncaught crash's stack trace, then lets Android handle it as usual. */
        fun installCrashLog(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, e ->
                runCatching {
                    val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                    File(context.filesDir, CRASH_FILE).writeText(
                        "${java.time.Instant.now()} · ${BuildConfig.VERSION_NAME} · thread ${thread.name}\n$trace",
                    )
                }
                previous?.uncaughtException(thread, e)
            }
        }

        fun lastCrash(context: Context): String? = File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText()

        fun clearCrash(context: Context) { File(context.filesDir, CRASH_FILE).delete() }

        /** A GitHub "new issue" link with the report filled in (the person reviews it before posting). */
        fun issueUrl(title: String, body: String): String {
            fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
            // Browsers and GitHub cut very long links, so the body is kept to a safe length.
            return "https://github.com/Nichevo/weathertrack/issues/new?title=${enc(title)}&body=${enc(body.take(5500))}"
        }
    }
}
