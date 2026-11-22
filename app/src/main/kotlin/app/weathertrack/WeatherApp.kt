package app.weathertrack

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.glance.appwidget.updateAll
import app.weathertrack.backup.DropboxBackup
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
import app.weathertrack.data.WeatherRepository
import app.weathertrack.data.db.AppDatabase
import app.weathertrack.data.net.ArchiveClient
import app.weathertrack.data.net.HttpFetcher
import app.weathertrack.export.DataExporter
import app.weathertrack.notify.Notifier
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
    val radarSpot = RadarSpotChecker(rainMap, tiles)
    val settings = SettingsRepository(app)
}

class WeatherApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        AlertScheduler.apply(this, container.settings.current)
    }
}
