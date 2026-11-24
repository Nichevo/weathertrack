package app.weathertrack.ui.accuracy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.weathertrack.WeatherApp
import app.weathertrack.core.accuracy.Band
import app.weathertrack.core.accuracy.ForecastCheck
import app.weathertrack.core.accuracy.IssuedHour
import app.weathertrack.core.accuracy.JacketScore
import app.weathertrack.core.accuracy.LeadError
import app.weathertrack.core.accuracy.Miss
import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.accuracy.Reliability
import app.weathertrack.core.accuracy.RunHour
import app.weathertrack.core.accuracy.Score
import app.weathertrack.core.util.runCatchingCancellable
import app.weathertrack.data.ForecastCheckRepository
import app.weathertrack.data.PlaceRepository
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.settings.SettingsRepository
import app.weathertrack.ui.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class CheckPeriod(val label: String, val days: Long?) { D30("30 days", 30), D90("90 days", 90), ALL("All", null) }

data class ForecastCheckState(
    val place: PlaceEntity? = null,
    val period: CheckPeriod = CheckPeriod.D30,
    val syncing: Boolean = false,
    val waitSeconds: Int? = null,
    val error: String? = null,
    val jacket: JacketScore? = null,
    val rainCalls: Score? = null,
    val leads: List<LeadError> = emptyList(),
    val bands: Map<Int, Band> = emptyMap(),
    val reliability: List<Reliability> = emptyList(),
    val misses: List<Miss> = emptyList(),
    val actualDays: Int = 0,
    val firstActual: LocalDate? = null,
    val storedPulls: Int = 0,
    /** The current 7-day forecast with likely ranges from past errors. */
    val tempFan: List<app.weathertrack.core.accuracy.FanPoint> = emptyList(),
    val rainFan: List<app.weathertrack.core.accuracy.FanPoint> = emptyList(),
    /** How the forecast and radar matched the user's own raining / dry reports in the period. */
    val reports: app.weathertrack.core.accuracy.ReportScore? = null,
    /** Did the 8-in-10 ranges (built from older days) hold over the last 30 days? */
    val coverage: List<app.weathertrack.core.accuracy.Coverage> = emptyList(),
    /** True when the likely ranges come from the same time of year rather than all days. */
    val seasonal: Boolean = false,
    /** Hour-by-hour wind and cloud errors by lead. */
    val windCloud: Map<app.weathertrack.core.accuracy.Measure, List<LeadError>> = emptyMap(),
    /** How often "sunny or not" was right, by lead. */
    val sun: List<app.weathertrack.core.accuracy.SunScore> = emptyList(),
    /** Daily high errors grouped by the cloud forecast for the day. */
    val highByCloud: Map<app.weathertrack.core.accuracy.CloudRegime, List<LeadError>> = emptyMap(),
    /** Jacket calls by hours ahead (1, 3, 6, 12). */
    val jacketByLead: Map<Int, JacketScore> = emptyMap(),
    /** How far ahead each source's forecasts got it right and stayed right, over the last week. */
    val settled: List<app.weathertrack.core.sources.Settled> = emptyList(),
    /** The pollen estimate's record on days you reported. */
    val pollen: app.weathertrack.data.PollenView? = null,
    /** Links between weather factors in what actually happened here, e.g. cloud and the high. */
    val relations: List<app.weathertrack.core.accuracy.Relation> = emptyList(),
)

class ForecastCheckViewModel(
    private val repo: ForecastCheckRepository,
    private val places: PlaceRepository,
    private val settings: SettingsRepository,
    private val weather: app.weathertrack.data.WeatherRepository,
    private val sources: app.weathertrack.data.SourceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ForecastCheckState())
    val state: StateFlow<ForecastCheckState> = _state
    private var obs: List<ObsHour> = emptyList()
    private var runs: List<RunHour> = emptyList()
    private var issued: List<IssuedHour> = emptyList()
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            places.ensureDefaults()
            places.selectedId.collectLatest { onPlace() }
        }
    }

    private suspend fun onPlace() {
        val place = places.selected() ?: return
        _state.update { ForecastCheckState(place = place, period = it.period) }
        reload(place)
        if (!repo.syncedRecently(place.id)) sync()
    }

    private suspend fun reload(place: PlaceEntity) {
        obs = repo.observed(place.id, 0)
        runs = repo.runs(place.id, 0)
        issued = repo.issued(place.id, 0, System.currentTimeMillis() / 1000)
        val pulls = repo.storedPulls(place.id)
        _state.update { it.copy(storedPulls = pulls) }
        recompute()
    }

    fun setPeriod(p: CheckPeriod) {
        _state.update { it.copy(period = p) }
        viewModelScope.launch { recompute() }
    }

    fun sync() {
        val place = _state.value.place ?: return
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            runCatchingCancellable {
                repo.sync(place, onWait = { left -> _state.update { it.copy(waitSeconds = left.takeIf { s -> s > 0 }) } })
            }.onFailure { e -> _state.update { it.copy(error = e.message ?: "Download failed") } }
            _state.update { it.copy(syncing = false, waitSeconds = null) }
            reload(place)
        }
    }

    private suspend fun recompute() {
        val s = _state.value
        val place = s.place ?: return
        val zone = Format.zone(place.timezone)
        val today = LocalDate.now(zone)
        val from = s.period.days?.let { today.minusDays(it) } ?: LocalDate.of(1970, 1, 1)
        val fromEpoch = from.atStartOfDay(zone).toEpochSecond()
        val o = obs
        val r = runs
        val iss = issued
        val st = settings.current
        val daily = weather.cachedForecast(place)?.forecast?.daily.orEmpty()
        val myReports = runCatchingCancellable { repo.reports(place.id, fromEpoch) }.getOrDefault(emptyList())
        val result = withContext(Dispatchers.Default) {
            val actuals = ForecastCheck.dailyActuals(o, zone)
            val leads = ForecastCheck.highErrors(r, o, zone, from)
            val rainLeads = ForecastCheck.rainErrors(r, o, zone, from)
            // Ranges for the coming days use the same season (from all stored years) when there is enough.
            val seasonal = ForecastCheck.seasonalHighErrors(r, o, zone, today)
            val allDays = ForecastCheck.highErrors(r, o, zone, LocalDate.of(1970, 1, 1)).associate { it.lead to it.days }
            val isSeasonal = seasonal.any { allDays[it.lead] != it.days }
            val dated = daily.mapNotNull { d -> runCatchingCancellable { LocalDate.parse(d.date) }.getOrNull()?.let { it to d } }
            ForecastCheckState(
                reports = app.weathertrack.core.accuracy.Feedback.score(myReports),
                tempFan = ForecastCheck.fan(dated.map { it.first to it.second.temperatureMax }, today, seasonal.ifEmpty { leads }),
                coverage = ForecastCheck.coverage(r, o, zone, today.minusDays(30)),
                seasonal = isSeasonal,
                rainFan = ForecastCheck.fan(dated.map { it.first to it.second.precipitationSum }, today, rainLeads, floorZero = true),
                jacket = ForecastCheck.jacket(iss, o, zone, from, st.jacketThresholds, morningHour = st.morningTime.hour),
                rainCalls = ForecastCheck.rainCalls(r, o, zone, from),
                leads = leads,
                bands = ForecastCheck.bands(leads),
                reliability = ForecastCheck.reliability(iss, o, fromEpoch),
                misses = ForecastCheck.misses(r, o, zone, from),
                actualDays = actuals.keys.count { !it.isBefore(from) },
                firstActual = actuals.keys.minOrNull(),
                windCloud = app.weathertrack.core.accuracy.WindCloud.errors(r, o, fromEpoch),
                sun = app.weathertrack.core.accuracy.WindCloud.sunny(r, o, fromEpoch),
                highByCloud = app.weathertrack.core.accuracy.WindCloud.highErrorsByRegime(r, o, zone, from),
                jacketByLead = ForecastCheck.jacketByLead(iss, o, zone, from, st.jacketThresholds),
                relations = app.weathertrack.core.accuracy.Relations.all(o, zone, from),
            )
        }
        val settled = runCatchingCancellable {
            val weekObs = o.filter { it.time >= System.currentTimeMillis() / 1000 - 8 * 86400L }
            withContext(Dispatchers.Default) { sources.settled(place.id, weekObs) }
        }.getOrDefault(emptyList())
        val pollen = runCatchingCancellable { repo.pollen(place, weather.cachedForecast(place)?.forecast) }.getOrNull()
        _state.update {
            it.copy(
                jacket = result.jacket, rainCalls = result.rainCalls, leads = result.leads, bands = result.bands,
                reliability = result.reliability, misses = result.misses, actualDays = result.actualDays,
                firstActual = result.firstActual, tempFan = result.tempFan, rainFan = result.rainFan, reports = result.reports,
                coverage = result.coverage, seasonal = result.seasonal,
                windCloud = result.windCloud, sun = result.sun, highByCloud = result.highByCloud,
                jacketByLead = result.jacketByLead, settled = settled, pollen = pollen, relations = result.relations,
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApp
                ForecastCheckViewModel(app.container.forecastCheck, app.container.places, app.container.settings, app.container.weather, app.container.sources)
            }
        }
    }
}
