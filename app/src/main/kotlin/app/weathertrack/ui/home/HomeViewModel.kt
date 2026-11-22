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
    val air: List<app.weathertrack.core.air.AirHour> = emptyList(),
    val warnings: List<app.weathertrack.core.warnings.Warning> = emptyList(),
    /** Next days with likely ranges for the high and the low, from past errors here. */
    val highFan: List<app.weathertrack.core.accuracy.FanPoint> = emptyList(),
    val lowFan: List<app.weathertrack.core.accuracy.FanPoint> = emptyList(),
    /** Every forecast source, their records here and the accuracy-weighted blend. */
    val sources: app.weathertrack.data.SourceView? = null,
    /** What your chilly / too warm / pollen taps say so far. */
    val comfort: List<String> = emptyList(),
)

class HomeViewModel(
    private val weather: WeatherRepository,
    private val placeRepo: PlaceRepository,
    private val updateWidgets: suspend () -> Unit,
    private val settings: SettingsRepository,
    private val forecastCheck: app.weathertrack.data.ForecastCheckRepository,
    private val radarSpot: app.weathertrack.map.RadarSpotChecker,
    private val extras: app.weathertrack.data.ExtrasRepository,
    private val sources: app.weathertrack.data.SourceRepository,
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
            settings.settings.collect {
                recompute()
                // The 4x2 widget shows settings too (second clock, thresholds).
                runCatching { updateWidgets() }
            }
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
                    air = if (same) it.air else emptyList(), warnings = if (same) it.warnings else emptyList(),
                    highFan = if (same) it.highFan else emptyList(), lowFan = if (same) it.lowFan else emptyList(),
                    sources = if (same) it.sources else null,
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
            forecastCheck.highBands(place, dayRegimes = dayRegimes(place))
        }.getOrElse { runCatching { forecastCheck.highBands(place, dayRegimes = dayRegimes(place)) }.getOrDefault(emptyMap()) }
        if (_state.value.place?.id == place.id) _state.update { it.copy(highBands = bands) }
        loadTrust(place)
        loadFans(place)
        loadSources(place)
    }

    /** Each coming day's cloud (low cloud, clear or mixed by day), from the stored forecast. */
    private suspend fun dayRegimes(place: PlaceEntity): Map<java.time.LocalDate, app.weathertrack.core.accuracy.CloudRegime> {
        val f = weather.cachedForecast(place)?.forecast ?: return emptyMap()
        return app.weathertrack.core.accuracy.WindCloud.dayRegimes(
            f.hourly.map { Triple(it.time, it.cloudCoverLow, it.cloudCover) }, Format.zone(place.timezone),
        )
    }

    /** Likely ranges for the coming highs and lows, from how far off past forecasts were here. */
    private suspend fun loadFans(place: PlaceEntity) {
        val FC = app.weathertrack.core.accuracy.ForecastCheck
        val zone = Format.zone(place.timezone)
        val today = java.time.LocalDate.now(zone)
        val result = runCatching {
            val fromEpoch = today.minusDays(3 * 365L).atStartOfDay(zone).toEpochSecond()
            val r = forecastCheck.runs(place.id, fromEpoch)
            val o = forecastCheck.observed(place.id, fromEpoch)
            val daily = (weather.cachedForecast(place)?.forecast?.daily).orEmpty()
                .mapNotNull { d -> runCatching { java.time.LocalDate.parse(d.date) }.getOrNull()?.let { it to d } }
            // A range needs enough checked days behind it to mean "8 in 10".
            val enough = app.weathertrack.core.accuracy.Band.MIN_BAND_DAYS
            val hi = FC.seasonalHighErrors(r, o, zone, today).filter { it.days >= enough }
            val lo = FC.lowErrors(r, o, zone, today.minusDays(90)).filter { it.days >= enough }
            // A day forecast as low cloud or clear uses past days of the same kind, once there are enough.
            val regimes = dayRegimes(place)
            val byRegime = app.weathertrack.core.accuracy.WindCloud.highErrorsByRegime(r, o, zone, today.minusDays(3 * 365L))
                .mapValues { (_, v) -> v.filter { it.days >= enough } }
            daily.flatMap { (d, dp) ->
                val errs = regimes[d]?.let { byRegime[it] }?.takeIf { it.isNotEmpty() } ?: hi
                FC.fan(listOf(d to dp.temperatureMax), today, errs)
            } to
                FC.fan(daily.map { it.first to it.second.temperatureMin }, today, lo)
        }.getOrNull() ?: return
        if (_state.value.place?.id == place.id) _state.update { it.copy(highFan = result.first, lowFan = result.second) }
    }

    /** Pulls every forecast source (when due), then scores them and blends. */
    private suspend fun loadSources(place: PlaceEntity) {
        val now = System.currentTimeMillis() / 1000
        runCatching { sources.refresh(place, weather.cachedForecast(place), "app") }
        val view = runCatching {
            val from = now - 60 * 86400L
            sources.view(place.id, Format.zone(place.timezone), forecastCheck.observed(place.id, from), forecastCheck.reports(place.id, from))
        }.getOrNull()
        if (_state.value.place?.id == place.id) _state.update { it.copy(sources = view) }
    }

    private suspend fun loadExtras(place: PlaceEntity) {
        val tides = runCatching { extras.tides(place, portName = settings.current.tidePort) }.getOrNull()
        if (_state.value.place?.id == place.id) _state.update { it.copy(tides = tides) }
        val spread = runCatching { extras.spread(place) }.getOrDefault(emptyList())
        if (_state.value.place?.id == place.id) _state.update { it.copy(spread = spread) }
        val warnings = runCatching { extras.warnings(place) }.getOrDefault(emptyList())
        if (_state.value.place?.id == place.id) _state.update { it.copy(warnings = warnings) }
        val air = runCatching { extras.air(place) }.getOrDefault(emptyList())
        if (_state.value.place?.id == place.id) _state.update { it.copy(air = air) }
    }

    private suspend fun loadTrust(place: PlaceEntity) {
        val cfg = settings.current
        val trust = runCatching { forecastCheck.trustRecord(place, cfg.jacketThresholds, cfg.morningTime.hour) }.getOrNull()
        val now = System.currentTimeMillis() / 1000
        val mine = runCatching { app.weathertrack.core.accuracy.Feedback.current(forecastCheck.reports(place.id, now - 3600), now) }.getOrNull()
        if (_state.value.place?.id == place.id) _state.update { it.copy(trust = trust, myReport = mine) }
        loadComfort(place)
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

    /** Saves "Feeling chilly", "Too warm" or "Pollen bothering me" with the weather right now. */
    fun feel(kind: app.weathertrack.core.comfort.Feeling.Kind) {
        val s = _state.value
        val place = s.place ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            val f = s.stored?.forecast
            val hour = f?.hourly?.firstOrNull { now >= it.time - 3600 && now < it.time }
            runCatching { forecastCheck.feeling(place, kind, hour, f?.current) }
                .onSuccess {
                    val zone = Format.zone(place.timezone)
                    val word = when (kind) {
                        app.weathertrack.core.comfort.Feeling.Kind.CHILLY -> "chilly"
                        app.weathertrack.core.comfort.Feeling.Kind.WARM -> "too warm"
                        app.weathertrack.core.comfort.Feeling.Kind.POLLEN -> "pollen"
                    }
                    _state.update { it.copy(reportMessage = "Thanks. Saved \"$word\" at ${Format.time(now, zone)}, with the weather then.") }
                    loadComfort(place)
                }
                .onFailure { e -> _state.update { it.copy(reportMessage = "Couldn't save that: ${e.message}") } }
        }
    }

    private suspend fun loadComfort(place: PlaceEntity) {
        val zone = Format.zone(place.timezone)
        val lines = runCatching {
            app.weathertrack.core.comfort.Comfort.summary(forecastCheck.feelings(place.id, System.currentTimeMillis() / 1000 - 3 * 365 * 86400L), zone)
        }.getOrDefault(emptyList())
        if (_state.value.place?.id == place.id) _state.update { it.copy(comfort = lines) }
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
                    app.container.forecastCheck, app.container.radarSpot, app.container.extras, app.container.sources,
                )
            }
        }
    }
}
