package app.weathertrack

import android.app.Application
import android.util.Log
import androidx.glance.appwidget.updateAll
import app.weathertrack.data.ClimateRepository
import app.weathertrack.data.HistoryRepository
import app.weathertrack.widget.DetailWidget
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
    /** Redraws home-screen widgets from stored data. Called after each stored forecast. */
    val updateWidgets: suspend () -> Unit = {
        runCatching { DetailWidget().updateAll(app) }
            .onFailure { Log.w("WeatherApp", "Widget update failed", it) }
    }
    val weather = WeatherRepository(database, http, onForecastStored = updateWidgets)
    val places = PlaceRepository(app, database.placeDao())
    /** Shared so History and Home downloads queue up instead of tripping Open-Meteo's rate limit together. */
    val archive = ArchiveClient(http)
    val history = HistoryRepository(app, database, archive)
    val climate = ClimateRepository(app, database, archive)
    val exporter = DataExporter(app, database)
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
