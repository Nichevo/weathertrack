package app.weathertrack

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.glance.appwidget.updateAll
import app.weathertrack.backup.DropboxBackup
import app.weathertrack.data.UpdateChecker
import app.weathertrack.data.Diagnostics
import app.weathertrack.data.StorageMaintenance
import app.weathertrack.data.ClimateRepository
import app.weathertrack.data.ExtrasRepository
import app.weathertrack.data.ForecastCheckRepository
import app.weathertrack.data.HistoryRepository
import app.weathertrack.widget.DetailWidget
import app.weathertrack.widget.RainWidgetFrames
import app.weathertrack.widget.RainWidgetWorker
import app.weathertrack.map.RainMapRepository
import app.weathertrack.map.RadarSpotChecker
import app.weathertrack.map.TileStore
import app.weathertrack.data.PlaceRepository
import app.weathertrack.data.SourceRepository
import app.weathertrack.data.WeatherRepository
import app.weathertrack.data.db.AppDatabase
import app.weathertrack.data.net.ArchiveClient
import app.weathertrack.data.net.HttpFetcher
import app.weathertrack.export.DataExporter
import app.weathertrack.notify.LockGlance
import app.weathertrack.notify.Notifier
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.weathertrack.settings.SettingsRepository
import app.weathertrack.work.AlertScheduler

/** Simple manual dependency container. */
class AppContainer(app: Application) {
    val database = AppDatabase.create(app)
    val http = HttpFetcher()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Redraws home-screen widgets from stored data. Called after each stored forecast. */
    val updateWidgets: suspend () -> Unit = {
        runCatching { DetailWidget().updateAll(app) }
            .onFailure { Log.w("WeatherApp", "Widget update failed", it) }
        // The rain map widget needs network to draw, so WorkManager does it (it throttles itself to every 10 minutes).
        runCatching { RainWidgetWorker.enqueue(app, force = false) }
            .onFailure { Log.w("WeatherApp", "Rain map widget update failed", it) }
        runCatching { LockGlance.update(app) }
            .onFailure { Log.w("WeatherApp", "Lock screen weather update failed", it) }
    }
    val weather = WeatherRepository(database, http, onForecastStored = updateWidgets)
    val places = PlaceRepository(app, database.placeDao())
    /** Shared so History and Home downloads queue up instead of tripping Open-Meteo's rate limit together. */
    val archive = ArchiveClient(http)
    val history = HistoryRepository(app, database, archive)
    val climate = ClimateRepository(app, database, archive)
    val forecastCheck = ForecastCheckRepository(app, database, archive)
    val exporter = DataExporter(app, database)
    val backup = DropboxBackup(app, exporter)
    val tiles = TileStore(app)
    val rainMap = RainMapRepository(http, database.rawPullDao())
    val extras = ExtrasRepository(http, database.rawPullDao())
    val sources = SourceRepository(http, database.rawPullDao(), database.sourceHourDao(), database.sourceDayDao())
    val radarSpot = RadarSpotChecker(rainMap, tiles)
    val settings = SettingsRepository(app)
    val updates = UpdateChecker(app, http)
    val storage = StorageMaintenance(app, database)
    val diagnostics = Diagnostics(app, database, storage)
}

class WeatherApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Diagnostics.installCrashLog(this)
        container = AppContainer(this)
        Notifier.createChannels(this)
        LockGlance.createChannel(this)
        // Show or clear the lock screen weather whenever its switch changes (Settings, tile or its own button).
        container.appScope.launch {
            container.settings.settings.map { it.lockGlance }.distinctUntilChanged().collect {
                runCatching { LockGlance.update(this@WeatherApp) }
                LockGlance.refreshTile(this@WeatherApp)
            }
        }
        AlertScheduler.apply(this, container.settings.current)
    }
}
