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
import app.weathertrack.ui.Format
import app.weathertrack.core.jacket.JacketResult
import app.weathertrack.data.PlaceRepository
import app.weathertrack.data.StoredForecast
import app.weathertrack.data.WeatherRepository
import app.weathertrack.data.db.PlaceEntity
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
    val loading: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(
    private val weather: WeatherRepository,
    private val placeRepo: PlaceRepository,
    private val updateWidgets: suspend () -> Unit,
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
    }

    /** Shows the cached forecast immediately, then refreshes if it is older than 20 minutes. */
    fun load() {
        viewModelScope.launch {
            placeRepo.ensureDefaults()
            val place = placeRepo.selected() ?: return@launch
            val cached = weather.cachedForecast(place)
            _state.update { it.copy(place = place, stored = cached, error = null) }
            recompute()
            updateWidgets() // the widget follows the selected place
            if (cached == null || System.currentTimeMillis() - cached.fetchedAtMs > 20 * 60_000) refresh("app")
        }
    }

    fun refresh(trigger: String = "manual") {
        val place = _state.value.place ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
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
        val f = s.stored?.forecast ?: return _state.update { it.copy(jacket = null, triggers = emptyList()) }
        val now = System.currentTimeMillis() / 1000
        val zone = Format.zone(f.timezone)
        _state.update {
            it.copy(
                jacket = JacketCalculator.evaluate(f.hourly, now, s.windowHours, f.quarterHourly),
                triggers = TriggerCalculator.evaluate(f.hourly, f.quarterHourly, now) { t -> Format.time(t, zone) },
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApp
                HomeViewModel(app.container.weather, app.container.places, app.container.updateWidgets)
            }
        }
    }
}
