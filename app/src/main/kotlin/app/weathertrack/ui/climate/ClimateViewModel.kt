package app.weathertrack.ui.climate

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.weathertrack.WeatherApp
import app.weathertrack.core.climate.ClimateComparison
import app.weathertrack.core.climate.ClimateDay
import app.weathertrack.core.climate.ClimateSummary
import app.weathertrack.core.climate.DegreeDayBases
import app.weathertrack.core.climate.EnergyAnalysis
import app.weathertrack.core.climate.EnergyFit
import app.weathertrack.core.climate.EnergyMonth
import app.weathertrack.core.climate.HomeClimate
import app.weathertrack.core.climate.PeriodRow
import app.weathertrack.data.ClimateRepository
import app.weathertrack.data.PlaceRepository
import app.weathertrack.data.db.ObservedRange
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

data class ClimateState(
    val place: PlaceEntity? = null,
    val range: ObservedRange? = null,
    val energyRange: ObservedRange? = null,
    val syncing: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    /** Seconds left while waiting out Open-Meteo's per-minute limit. */
    val waitSeconds: Int? = null,
    val error: String? = null,
    val bases: DegreeDayBases = DegreeDayBases(),
    val month: YearMonth = YearMonth.now(),
    val monthToDate: ClimateComparison? = null,
    val months: List<PeriodRow> = emptyList(),
    val weeks: List<PeriodRow> = emptyList(),
    val years: List<ClimateSummary> = emptyList(),
    val energyFit: EnergyFit? = null,
    val energyMonths: List<EnergyMonth> = emptyList(),
    val message: String? = null,
)

class ClimateViewModel(
    private val climate: ClimateRepository,
    private val places: PlaceRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ClimateState(bases = settings.current.degreeDayBases))
    val state: StateFlow<ClimateState> = _state
    private var days: List<ClimateDay> = emptyList()
    private var energy: Map<LocalDate, Double> = emptyMap()
    private var rangeJob: Job? = null
    private var syncJob: Job? = null
    /** Set once the month arrows are used, so downloads don't jump the view away. */
    private var userPickedMonth = false

    init {
        viewModelScope.launch {
            places.ensureDefaults()
            places.selectedId.collectLatest { onPlace() }
        }
        viewModelScope.launch {
            climate.observeEnergyRange().collect { r -> _state.update { it.copy(energyRange = r) } }
        }
        viewModelScope.launch {
            settings.settings.map { it.degreeDayBases }.distinctUntilChanged().collect { b ->
                if (b != _state.value.bases) {
                    _state.update { it.copy(bases = b) }
                    recompute()
                }
            }
        }
    }

    private suspend fun onPlace() {
        val place = places.selected() ?: return
        userPickedMonth = false
        _state.update { ClimateState(place = place, bases = it.bases, energyRange = it.energyRange) }
        rangeJob?.cancel()
        rangeJob = viewModelScope.launch {
            climate.observeRange(place.id).collect { r -> _state.update { it.copy(range = r) } }
        }
        reload(place, resetMonth = true)
        if (!climate.syncedRecently(place.id)) sync()
    }

    private suspend fun reload(place: PlaceEntity, resetMonth: Boolean) {
        days = climate.days(place.id)
        energy = climate.energy()
        if (resetMonth) {
            val last = days.lastOrNull()?.date
            val month = when {
                last == null -> YearMonth.now()
                last.dayOfMonth < 3 -> YearMonth.from(last).minusMonths(1)
                else -> YearMonth.from(last)
            }
            _state.update { it.copy(month = month) }
        }
        recompute()
    }

    fun sync() {
        val place = _state.value.place ?: return
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            runCatching {
                climate.sync(
                    place,
                    onProgress = { done, total ->
                        _state.update { it.copy(progress = done to total, waitSeconds = null) }
                        // Show each newly stored chunk straight away (newest years arrive first).
                        if (done > 0) viewModelScope.launch { reload(place, resetMonth = !userPickedMonth) }
                    },
                    onWait = { left -> _state.update { it.copy(waitSeconds = left.takeIf { s -> s > 0 }) } },
                )
            }.onFailure { e -> _state.update { it.copy(error = e.message ?: "Climate download failed") } }
            _state.update { it.copy(syncing = false, progress = null, waitSeconds = null) }
            reload(place, resetMonth = !userPickedMonth)
        }
    }

    fun shiftMonth(delta: Long) {
        val next = _state.value.month.plusMonths(delta)
        if (next.isAfter(YearMonth.now())) return
        userPickedMonth = true
        _state.update { it.copy(month = next) }
        viewModelScope.launch { recompute() }
    }

    fun importEnergy(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching { climate.importEnergy(uri) }
            val msg = result.fold(
                onSuccess = { r ->
                    if (r == null) "Couldn't find a date column and a kWh column in that file."
                    else "Imported ${r.days.size} days (${r.days.keys.first()} to ${r.days.keys.last()}) " +
                        "from \"${r.dateColumn}\" and \"${r.valueColumn}\"" +
                        (if (r.rowsSkipped > 0) ", skipped ${r.rowsSkipped} rows." else ".")
                },
                onFailure = { it.message ?: "Import failed" },
            )
            _state.update { it.copy(message = msg) }
            _state.value.place?.let { reload(it, resetMonth = false) }
        }
    }

    fun clearEnergy() {
        viewModelScope.launch {
            climate.clearEnergy()
            _state.update { it.copy(message = "Removed imported power use.") }
            _state.value.place?.let { reload(it, resetMonth = false) }
        }
    }

    private suspend fun recompute() {
        val s = _state.value
        val all = days
        if (all.isEmpty()) {
            _state.update { it.copy(monthToDate = null, months = emptyList(), weeks = emptyList(), years = emptyList(), energyFit = null, energyMonths = emptyList()) }
            return
        }
        val bases = s.bases
        val last = all.last().date
        val lastComplete = if (last == YearMonth.from(last).atEndOfMonth()) YearMonth.from(last) else YearMonth.from(last).minusMonths(1)
        val kwh = energy
        val result = withContext(Dispatchers.Default) {
            ClimateState(
                monthToDate = HomeClimate.monthToDate(all, s.month, bases),
                months = HomeClimate.months(all, lastComplete, bases),
                weeks = HomeClimate.weeks(all, last, bases),
                years = HomeClimate.years(all, bases).reversed(),
                energyFit = EnergyAnalysis.fit(all, kwh, bases),
                energyMonths = EnergyAnalysis.byMonth(all, kwh, bases),
            )
        }
        _state.update {
            it.copy(
                monthToDate = result.monthToDate, months = result.months, weeks = result.weeks, years = result.years,
                energyFit = result.energyFit, energyMonths = result.energyMonths,
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApp
                ClimateViewModel(app.container.climate, app.container.places, app.container.settings)
            }
        }
    }
}
