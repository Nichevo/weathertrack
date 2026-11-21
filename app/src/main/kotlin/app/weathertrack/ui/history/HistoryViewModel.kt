package app.weathertrack.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.weathertrack.WeatherApp
import app.weathertrack.core.history.DecadeStats
import app.weathertrack.core.history.HistoryStats
import app.weathertrack.core.history.YearOnYear
import app.weathertrack.data.HistoryRepository
import app.weathertrack.data.PlaceRepository
import app.weathertrack.data.db.ObservedRange
import app.weathertrack.data.db.PlaceEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class HistoryState(
    val place: PlaceEntity? = null,
    val range: ObservedRange? = null,
    val syncing: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val error: String? = null,
    val month: YearMonth = YearMonth.now(),
    val yearOnYear: YearOnYear? = null,
    val decades: List<DecadeStats> = emptyList(),
)

class HistoryViewModel(
    private val history: HistoryRepository,
    private val places: PlaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state
    private var rangeJob: Job? = null
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            places.ensureDefaults()
            places.selectedId.collectLatest { onPlace() }
        }
    }

    private suspend fun onPlace() {
        val place = places.selected() ?: return
        _state.update { HistoryState(place = place) }
        rangeJob?.cancel()
        rangeJob = viewModelScope.launch {
            history.observeRange(place.id).collect { r -> _state.update { it.copy(range = r) } }
        }
        defaultMonth(place)
        if (!history.syncedRecently(place.id)) sync()
    }

    /** The latest month with at least a few days of data. */
    private suspend fun defaultMonth(place: PlaceEntity) {
        val last = history.observeRangeOnce(place.id)?.last?.let(LocalDate::parse)
        val month = when {
            last == null -> YearMonth.now()
            last.dayOfMonth < 3 -> YearMonth.from(last).minusMonths(1)
            else -> YearMonth.from(last)
        }
        _state.update { it.copy(month = month) }
        recompute()
    }

    fun sync() {
        val place = _state.value.place ?: return
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            runCatching {
                history.sync(place) { done, total -> _state.update { it.copy(progress = done to total) } }
            }.onFailure { e -> _state.update { it.copy(error = e.message ?: "History download failed") } }
            _state.update { it.copy(syncing = false, progress = null) }
            defaultMonth(place)
        }
    }

    fun shiftMonth(delta: Long) {
        val next = _state.value.month.plusMonths(delta)
        if (next.isAfter(YearMonth.now())) return
        _state.update { it.copy(month = next) }
        viewModelScope.launch { recompute() }
    }

    private suspend fun recompute() {
        val s = _state.value
        val place = s.place ?: return
        val obs = history.month(place.id, s.month.monthValue)
        if (obs.isEmpty()) {
            _state.update { it.copy(yearOnYear = null, decades = emptyList()) }
            return
        }
        _state.update {
            it.copy(
                yearOnYear = HistoryStats.yearOnYear(obs, s.month),
                decades = HistoryStats.byDecade(obs, s.month.monthValue),
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApp
                HistoryViewModel(app.container.history, app.container.places)
            }
        }
    }
}
