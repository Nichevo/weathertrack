package app.weathertrack.settings

import android.content.Context
import app.weathertrack.core.alerts.TriggerThresholds
import app.weathertrack.core.jacket.JacketThresholds
import app.weathertrack.core.walk.WalkThresholds
import app.weathertrack.core.walk.WalkWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime

/** User settings. "Maybe" jacket levels are derived from the "yes" levels. */
data class AppSettings(
    // Jacket call: YES at these levels.
    val jacketRainChance: Int = 50,
    val jacketRainMm: Double = 0.5,
    val jacketGustKmh: Int = 45,
    val jacketFeelsLike: Int = 11,
    // Triggers
    val hotFeelsLike: Int = 27,
    val coldFeelsLike: Int = 5,
    val rainSoonMinutes: Int = 30,
    // Notifications
    val notifyRainSoon: Boolean = false,
    val notifyHot: Boolean = false,
    val notifyCold: Boolean = false,
    val notifyMorning: Boolean = false,
    val morningTime: LocalTime = LocalTime.of(7, 0),
    val quietStart: LocalTime = LocalTime.of(22, 0),
    val quietEnd: LocalTime = LocalTime.of(7, 0),
    // Background refresh of all saved places, in minutes.
    val refreshMinutes: Int = 60,
    // Walks: two regular windows, local time.
    val walk1Start: LocalTime = LocalTime.of(7, 0),
    val walk1End: LocalTime = LocalTime.of(9, 0),
    val walk2Start: LocalTime = LocalTime.of(17, 0),
    val walk2End: LocalTime = LocalTime.of(19, 0),
    val petHotFeelsLike: Int = 24,
    val notifyWalks: Boolean = false,
    val walkLeadMinutes: Int = 30,
    val walkNotifyOnlyIfNotGood: Boolean = true,
) {
    val walkWindows: List<WalkWindow>
        get() = listOf(WalkWindow("Morning walk", walk1Start, walk1End), WalkWindow("Evening walk", walk2Start, walk2End))

    val walkThresholds: WalkThresholds
        get() = WalkThresholds(petHotFeelsLike = petHotFeelsLike.toDouble(), coldFeelsLike = coldFeelsLike.toDouble())

    val jacketThresholds: JacketThresholds
        get() = JacketThresholds(
            rainProbabilityYes = jacketRainChance.toDouble(),
            rainProbabilityMaybe = (jacketRainChance - 25).coerceAtLeast(5).toDouble(),
            rainTotalMmYes = jacketRainMm,
            rainTotalMmMaybe = (jacketRainMm / 5).coerceAtLeast(0.1),
            gustKmhYes = jacketGustKmh.toDouble(),
            gustKmhMaybe = (jacketGustKmh - 10).toDouble(),
            feelsLikeYes = jacketFeelsLike.toDouble(),
            feelsLikeMaybe = (jacketFeelsLike + 3).toDouble(),
        )

    val triggerThresholds: TriggerThresholds
        get() = TriggerThresholds(
            hotFeelsLike = hotFeelsLike.toDouble(),
            coldFeelsLike = coldFeelsLike.toDouble(),
            rainSoonMinutes = rainSoonMinutes,
        )

    val anyAlertOn: Boolean get() = notifyRainSoon || notifyHot || notifyCold || notifyWalks
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings

    val current: AppSettings get() = _settings.value

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_settings.value)
        write(next)
        _settings.value = next
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        fun time(key: String, def: LocalTime) = prefs.getString(key, null)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: def
        return AppSettings(
            jacketRainChance = prefs.getInt("jacket_rain_chance", d.jacketRainChance),
            jacketRainMm = prefs.getFloat("jacket_rain_mm", d.jacketRainMm.toFloat()).toDouble(),
            jacketGustKmh = prefs.getInt("jacket_gust", d.jacketGustKmh),
            jacketFeelsLike = prefs.getInt("jacket_feels", d.jacketFeelsLike),
            hotFeelsLike = prefs.getInt("hot_feels", d.hotFeelsLike),
            coldFeelsLike = prefs.getInt("cold_feels", d.coldFeelsLike),
            rainSoonMinutes = prefs.getInt("rain_soon_minutes", d.rainSoonMinutes),
            notifyRainSoon = prefs.getBoolean("notify_rain_soon", d.notifyRainSoon),
            notifyHot = prefs.getBoolean("notify_hot", d.notifyHot),
            notifyCold = prefs.getBoolean("notify_cold", d.notifyCold),
            notifyMorning = prefs.getBoolean("notify_morning", d.notifyMorning),
            morningTime = time("morning_time", d.morningTime),
            quietStart = time("quiet_start", d.quietStart),
            quietEnd = time("quiet_end", d.quietEnd),
            refreshMinutes = prefs.getInt("refresh_minutes", d.refreshMinutes),
            walk1Start = time("walk1_start", d.walk1Start),
            walk1End = time("walk1_end", d.walk1End),
            walk2Start = time("walk2_start", d.walk2Start),
            walk2End = time("walk2_end", d.walk2End),
            petHotFeelsLike = prefs.getInt("pet_hot_feels", d.petHotFeelsLike),
            notifyWalks = prefs.getBoolean("notify_walks", d.notifyWalks),
            walkLeadMinutes = prefs.getInt("walk_lead_minutes", d.walkLeadMinutes),
            walkNotifyOnlyIfNotGood = prefs.getBoolean("walk_only_not_good", d.walkNotifyOnlyIfNotGood),
        )
    }

    private fun write(s: AppSettings) {
        prefs.edit()
            .putInt("jacket_rain_chance", s.jacketRainChance)
            .putFloat("jacket_rain_mm", s.jacketRainMm.toFloat())
            .putInt("jacket_gust", s.jacketGustKmh)
            .putInt("jacket_feels", s.jacketFeelsLike)
            .putInt("hot_feels", s.hotFeelsLike)
            .putInt("cold_feels", s.coldFeelsLike)
            .putInt("rain_soon_minutes", s.rainSoonMinutes)
            .putBoolean("notify_rain_soon", s.notifyRainSoon)
            .putBoolean("notify_hot", s.notifyHot)
            .putBoolean("notify_cold", s.notifyCold)
            .putBoolean("notify_morning", s.notifyMorning)
            .putString("morning_time", s.morningTime.toString())
            .putString("quiet_start", s.quietStart.toString())
            .putString("quiet_end", s.quietEnd.toString())
            .putInt("refresh_minutes", s.refreshMinutes)
            .putString("walk1_start", s.walk1Start.toString())
            .putString("walk1_end", s.walk1End.toString())
            .putString("walk2_start", s.walk2Start.toString())
            .putString("walk2_end", s.walk2End.toString())
            .putInt("pet_hot_feels", s.petHotFeelsLike)
            .putBoolean("notify_walks", s.notifyWalks)
            .putInt("walk_lead_minutes", s.walkLeadMinutes)
            .putBoolean("walk_only_not_good", s.walkNotifyOnlyIfNotGood)
            .apply()
    }
}
