package app.weathertrack.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.weathertrack.WeatherApp
import app.weathertrack.core.history.DecadeStats
import app.weathertrack.core.history.DailyObs
import app.weathertrack.core.history.ExploreMeasure
import app.weathertrack.core.history.ExploreSpan
import app.weathertrack.core.history.ExploreView
import app.weathertrack.core.history.Explorer
import app.weathertrack.core.history.HistoryStats
import app.weathertrack.core.history.YearOnYear
import app.weathertrack.core.util.runCatchingCancellable
import app.weathertrack.data.ExtrasRepository
import app.weathertrack.data.HistoryRepository
import app.weathertrack.core.seasonal.ModelMonth
import app.weathertrack.core.seasonal.OniMonth
import app.weathertrack.core.seasonal.Outlook
import app.weathertrack.core.seasonal.OutlookView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** Seconds left while waiting out Open-Meteo's per-minute limit. */
    val waitSeconds: Int? = null,
    val error: String? = null,
    val month: YearMonth = YearMonth.now(),
    val yearOnYear: YearOnYear? = null,
    val decades: List<DecadeStats> = emptyList(),
    /** The last 12 complete months against their averages, by season. */
    val seasons: app.weathertrack.core.history.SeasonView? = null,
    /** The next 12 months: seasonal model, normals and El Niño / La Niña effects. */
    val outlook: OutlookView? = null,
    /** Explorer choices: what to chart, over what period, against what. */
    val measure: ExploreMeasure = ExploreMeasure.RAIN,
    val span: ExploreSpan = ExploreSpan.MONTH,
    val showYears: Boolean = true,
    val showNormal: Boolean = true,
    val showDecades: Boolean = true,
    val explore: ExploreView? = null,
)

class HistoryViewModel(
    private val history: HistoryRepository,
    private val places: PlaceRepository,
    private val extras: ExtrasRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state
    private var rangeJob: Job? = null
    private var syncJob: Job? = null
    /** Set once the month arrows are used, so downloads don't jump the view away. */
    private var userPickedMonth = false

    init {
        viewModelScope.launch {
            places.ensureDefaults()
            places.selectedId.collectLatest { onPlace() }
        }
    }

    private suspend fun onPlace() {
        val place = places.selected() ?: return
        userPickedMonth = false
        allObs = null
        _state.update { HistoryState(place = place, measure = it.measure, span = it.span, showYears = it.showYears, showNormal = it.showNormal, showDecades = it.showDecades) }
        rangeJob?.cancel()
        rangeJob = viewModelScope.launch {
            history.observeRange(place.id).collect { r -> _state.update { it.copy(range = r) } }
        }
        defaultMonth(place)
        refreshSeasons(place)
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
            runCatchingCancellable {
                history.sync(
                    place,
                    onProgress = { done, total ->
                        _state.update { it.copy(progress = done to total, waitSeconds = null) }
                        // Show each newly stored chunk straight away (newest years arrive first).
                        if (done > 0) viewModelScope.launch {
                            allObs = null
                            if (userPickedMonth) recompute() else defaultMonth(place)
                            refreshSeasons(place)
                        }
                    },
                    onWait = { left -> _state.update { it.copy(waitSeconds = left.takeIf { s -> s > 0 }) } },
                )
            }.onFailure { e -> _state.update { it.copy(error = e.message ?: "History download failed") } }
            _state.update { it.copy(syncing = false, progress = null, waitSeconds = null) }
            allObs = null
                            if (userPickedMonth) recompute() else defaultMonth(place)
            refreshSeasons(place)
        }
    }

    private suspend fun refreshSeasons(place: PlaceEntity) {
        val view = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val obs = history.all(place.id)
            val last = obs.lastOrNull()?.date ?: return@withContext null
            val lastFull = if (last == YearMonth.from(last).atEndOfMonth()) YearMonth.from(last) else YearMonth.from(last).minusMonths(1)
            app.weathertrack.core.history.Seasons.view(obs, lastFull, southern = place.latitude < 0)
        }
        if (_state.value.place?.id == place.id) _state.update { it.copy(seasons = view) }
        refreshOutlook(place)
    }

    private var outlookJob: Job? = null
    private var outlookPlace: Long? = null
    private var model: Pair<Long, List<ModelMonth>>? = null
    private var oni: List<OniMonth>? = null

    /** Downloads (at most daily) the seasonal model and El Niño record, then builds the next 12 months. */
    private fun refreshOutlook(place: PlaceEntity) {
        if (outlookJob?.isActive == true && outlookPlace == place.id) return
        outlookJob?.cancel()
        outlookPlace = place.id
        outlookJob = viewModelScope.launch {
            val m = model?.takeIf { it.first == place.id }?.second
                ?: extras.seasonalModel(place, trigger = "history").also { if (it.isNotEmpty()) model = place.id to it }
            val o = oni ?: extras.oni(place, trigger = "history").also { if (it.isNotEmpty()) oni = it }
            val view = withContext(Dispatchers.Default) {
                val obs = history.all(place.id)
                if (obs.isEmpty()) return@withContext null
                val last = obs.last().date
                val lastFull = if (last == YearMonth.from(last).atEndOfMonth()) YearMonth.from(last) else YearMonth.from(last).minusMonths(1)
                Outlook.build(obs, lastFull.plusMonths(1), place.latitude < 0, m, o)
            }
            if (_state.value.place?.id == place.id) _state.update { it.copy(outlook = view) }
        }
    }

    fun shiftMonth(delta: Long) {
        val s = _state.value
        val next = s.span.step(s.month, delta.toInt())
        if (s.span.start(next).isAfter(LocalDate.now())) return
        userPickedMonth = true
        _state.update { it.copy(month = next) }
        viewModelScope.launch { recompute() }
    }

    private suspend fun recompute() {
        val s = _state.value
        val place = s.place ?: return
        val obs = history.month(place.id, s.month.monthValue)
        if (obs.isEmpty()) {
            _state.update { it.copy(yearOnYear = null, decades = emptyList(), explore = null) }
            return
        }
        _state.update {
            it.copy(
                yearOnYear = HistoryStats.yearOnYear(obs, s.month),
                decades = HistoryStats.byDecade(obs, s.month.monthValue),
            )
        }
        explore()
    }

    /** Every stored day for the place, kept while the place and data don't change. */
    private var allObs: List<DailyObs>? = null

    private suspend fun obs(place: PlaceEntity): List<DailyObs> =
        allObs ?: history.all(place.id).also { allObs = it }

    private suspend fun explore() {
        val s = _state.value
        val place = s.place ?: return
        val all = obs(place)
        val view = withContext(Dispatchers.Default) {
            if (all.isEmpty()) null else Explorer.view(all, s.measure, s.span, s.month)
        }
        _state.update { cur ->
            if (cur.place?.id == place.id && cur.measure == s.measure && cur.span == s.span && cur.month == s.month) cur.copy(explore = view) else cur
        }
    }

    fun setMeasure(m: ExploreMeasure) {
        _state.update { it.copy(measure = m) }
        viewModelScope.launch { explore() }
    }

    fun setSpan(span: ExploreSpan) {
        _state.update { it.copy(span = span) }
        viewModelScope.launch { explore() }
    }

    fun toggleYears() = _state.update { it.copy(showYears = !it.showYears) }
    fun toggleNormal() = _state.update { it.copy(showNormal = !it.showNormal) }
    fun toggleDecades() = _state.update { it.copy(showDecades = !it.showDecades) }

    /** The table and chart on screen as CSV, for "Save this view". */
    fun viewCsv(): String? = _state.value.explore?.let(Explorer::csv)

    /** Every stored day behind the view, all measures, as CSV. */
    fun rawCsv(): String? {
        val v = _state.value.explore ?: return null
        return Explorer.rawCsv(allObs ?: return null, v)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApp
                HistoryViewModel(app.container.history, app.container.places, app.container.extras)
            }
        }
    }
}
