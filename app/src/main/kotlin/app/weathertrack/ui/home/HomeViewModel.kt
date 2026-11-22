package app.weathertrack.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.weathertrack.WeatherApp
import app.weathertrack.core.alerts.Trigger
import app.weathertrack.core.alerts.TriggerCalculator
import app.weathertrack.core.jacket.JacketCalculator
import app.weathertrack.core.walk.WalkForecast
import app.weathertrack.core.walk.WalkPlanner
import app.weathertrack.settings.SettingsRepository
import app.weathertrack.ui.Format
import app.weathertrack.core.jacket.JacketResult
import app.weathertrack.data.PlaceRepository
import app.weathertrack.data.StoredForecast
import app.weathertrack.data.WeatherRepository
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.data.highBands
import app.weathertrack.data.trustRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val place: PlaceEntity? = null,
    val places: List<PlaceEntity> = emptyList(),
    val stored: StoredForecast? = null,
    val windowHours: Int = 3,
    val jacket: JacketResult? = null,
    val triggers: List<Trigger> = emptyList(),
    val walks: List<WalkForecast> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Likely range of daily highs by lead (days ahead), learned from Forecast check. */
    val highBands: Map<Int, app.weathertrack.core.accuracy.Band> = emptyMap(),
    /** What the latest radar frame shows at the place; null if unavailable. */
    val radar: app.weathertrack.core.map.RadarSpot? = null,
    /** How often forecasts here have been right lately. */
    val trust: app.weathertrack.data.TrustRecord? = null,
    /** The user's own report if made in the last 45 minutes. */
    val myReport: app.weathertrack.core.accuracy.RainReport? = null,
    val reportMessage: String? = null,
    val tides: app.weathertrack.core.tides.TideSeries? = null,
    /** How far apart several weather models are, per day. */
    val spread: List<app.weathertrack.core.accuracy.DaySpread> = emptyList(),
)

class HomeViewModel(
    private val weather: WeatherRepository,
    private val placeRepo: PlaceRepository,
    private val updateWidgets: suspend () -> Unit,
    private val settings: SettingsRepository,
    private val forecastCheck: app.weathertrack.data.ForecastCheckRepository,
    private val radarSpot: app.weathertrack.map.RadarSpotChecker,
    private val extras: app.weathertrack.data.ExtrasRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            placeRepo.ensureDefaults()
            placeRepo.places.collect { list -> _state.update { it.copy(places = list) } }
        }
        viewModelScope.launch {
            placeRepo.selectedId.collect { load() }
        }
        viewModelScope.launch {
            settings.settings.collect { recompute() }
        }
    }

    /** Shows the cached forecast immediately, then refreshes if it is older than 20 minutes. */
    fun load() {
        viewModelScope.launch {
            placeRepo.ensureDefaults()
            val place = placeRepo.selected() ?: return@launch
            val cached = weather.cachedForecast(place)
            _state.update {
                val same = it.place?.id == place.id
                it.copy(
                    place = place, stored = cached, error = null, radar = if (same) it.radar else null,
                    tides = if (same) it.tides else null, spread = if (same) it.spread else emptyList(),
                    trust = if (same) it.trust else null, highBands = if (same) it.highBands else emptyMap(),
                )
            }
            recompute()
            updateWidgets() // the widget follows the selected place
            if (cached == null || System.currentTimeMillis() - cached.fetchedAtMs > 20 * 60_000) refresh("app")
            loadRadar(place)
            launch { loadExtras(place) }
            loadBands(place)
        }
    }

    private suspend fun loadRadar(place: PlaceEntity) {
        val spot = runCatching { radarSpot.check(place.latitude, place.longitude) }.getOrNull()
        if (_state.value.place?.id == place.id) _state.update { it.copy(radar = spot) }
    }

    /** Learns how far off past highs were, so the 7-day rows can show a likely range. */
    private suspend fun loadBands(place: PlaceEntity) {
        val bands = runCatching {
            if (!forecastCheck.syncedRecently(place.id)) forecastCheck.sync(place)
            forecastCheck.highBands(place)
        }.getOrElse { runCatching { forecastCheck.highBands(place) }.getOrDefault(emptyMap()) }
        if (_state.value.place?.id == place.id) _state.update { it.copy(highBands = bands) }
        loadTrust(place)
    }

    private suspend fun loadExtras(place: PlaceEntity) {
        val tides = runCatching { extras.tides(place) }.getOrNull()
        if (_state.value.place?.id == place.id) _state.update { it.copy(tides = tides) }
        val spread = runCatching { extras.spread(place) }.getOrDefault(emptyList())
        if (_state.value.place?.id == place.id) _state.update { it.copy(spread = spread) }
    }

    private suspend fun loadTrust(place: PlaceEntity) {
        val cfg = settings.current
        val trust = runCatching { forecastCheck.trustRecord(place, cfg.jacketThresholds, cfg.morningTime.hour) }.getOrNull()
        val now = System.currentTimeMillis() / 1000
        val mine = runCatching { app.weathertrack.core.accuracy.Feedback.current(forecastCheck.reports(place.id, now - 3600), now) }.getOrNull()
        if (_state.value.place?.id == place.id) _state.update { it.copy(trust = trust, myReport = mine) }
    }

    /** Saves the user's "raining" / "dry" report, with what the forecast and radar said right now. */
    fun report(raining: Boolean) {
        val s = _state.value
        val place = s.place ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            val f = s.stored?.forecast
            val mm = f?.quarterHourly?.firstOrNull { now >= it.time && now < it.time + 900 }?.precipitation
            val chance = f?.hourly?.firstOrNull { now >= it.time && now < it.time + 3600 }?.precipitationProbability
            val radar = s.radar ?: runCatching { radarSpot.check(place.latitude, place.longitude) }.getOrNull()
            runCatching { forecastCheck.report(place, raining, mm, chance, radar) }
                .onSuccess {
                    val zone = Format.zone(place.timezone)
                    _state.update { it.copy(reportMessage = "Thanks. Saved \"${if (raining) "raining" else "dry"}\" at ${Format.time(now, zone)}.") }
                    loadTrust(place)
                }
                .onFailure { e -> _state.update { it.copy(reportMessage = "Couldn't save that: ${e.message}") } }
        }
    }

    fun refresh(trigger: String = "manual") {
        val place = _state.value.place ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            launch { loadRadar(place) }
            runCatching { weather.refreshForecast(place, trigger) }
                .onSuccess { fresh -> _state.update { it.copy(stored = fresh, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Refresh failed") } }
            recompute()
        }
    }

    fun setWindow(hours: Int) {
        _state.update { it.copy(windowHours = hours) }
        recompute()
    }

    fun selectPlace(id: Long) = placeRepo.select(id)

    private fun recompute() {
        val s = _state.value
        val f = s.stored?.forecast ?: return _state.update { it.copy(jacket = null, triggers = emptyList(), walks = emptyList()) }
        val now = System.currentTimeMillis() / 1000
        val zone = Format.zone(f.timezone)
        val cfg = settings.current
        _state.update {
            it.copy(
                jacket = JacketCalculator.evaluate(f.hourly, now, s.windowHours, f.quarterHourly, cfg.jacketThresholds),
                triggers = TriggerCalculator.evaluate(f.hourly, f.quarterHourly, now, cfg.triggerThresholds) { t ->
                    Format.time(t, zone)
                },
                walks = WalkPlanner.upcoming(f.hourly, cfg.walkWindows, zone, now, cfg.walkThresholds, count = 3),
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApp
                HomeViewModel(
                    app.container.weather, app.container.places, app.container.updateWidgets, app.container.settings,
                    app.container.forecastCheck, app.container.radarSpot, app.container.extras,
                )
            }
        }
    }
}
