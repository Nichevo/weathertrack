package app.weathertrack.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.weathertrack.WeatherApp
import app.weathertrack.core.map.RainGrid
import app.weathertrack.core.map.RainViewer
import app.weathertrack.core.map.TileMath
import app.weathertrack.data.PlaceRepository
import app.weathertrack.map.MapLayers
import app.weathertrack.map.RainMapRepository
import app.weathertrack.map.Viewport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One step of the timeline: a past radar image, or a forecast time. */
sealed class MapFrame(val time: Long) {
    class Radar(val frame: RainViewer.Frame, val latest: Boolean) : MapFrame(frame.time)
    class Forecast(time: Long) : MapFrame(time)
}

data class RainMapState(
    val viewport: Viewport? = null,
    val marker: Pair<Double, Double>? = null,
    val zone: String? = null,
    val radarHost: String? = null,
    val frames: List<MapFrame> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val radarOn: Boolean = true,
    val thunderOn: Boolean = true,
    val grid: RainGrid? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val frame: MapFrame? get() = frames.getOrNull(index)

    val layers: MapLayers
        get() {
            val f = frame
            return MapLayers(
                radarHost = radarHost,
                radarFrame = (f as? MapFrame.Radar)?.frame?.takeIf { radarOn },
                grid = grid,
                forecastAt = (f as? MapFrame.Forecast)?.time?.takeIf { radarOn },
                thunderAt = f?.time?.takeIf { thunderOn },
                marker = marker,
            )
        }
}

class RainMapViewModel(
    private val repo: RainMapRepository,
    private val places: PlaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RainMapState())
    val state: StateFlow<RainMapState> = _state
    private var playJob: Job? = null
    private var gridJob: Job? = null

    init {
        viewModelScope.launch {
            places.ensureDefaults()
            places.selectedId.collectLatest {
                val p = places.selected() ?: return@collectLatest
                _state.update {
                    it.copy(
                        viewport = Viewport.at(p.latitude, p.longitude, DEFAULT_ZOOM, it.viewport?.width ?: 400.0, it.viewport?.height ?: 700.0),
                        marker = p.latitude to p.longitude, zone = p.timezone, grid = null,
                    )
                }
                refresh()
            }
        }
    }

    /** Reloads the radar frame list and the forecast grid for the current view. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repo.radarFrames() }
                .onSuccess { f -> setFrames(f) }
                .onFailure { e -> _state.update { it.copy(error = "Radar: ${e.message}") } }
            _state.update { it.copy(loading = false) }
            loadGrid(force = true)
        }
    }

    private fun setFrames(f: RainViewer.Frames) {
        val past = f.past.takeLast(13)
        val last = past.lastOrNull()?.time ?: (System.currentTimeMillis() / 1000)
        // Forecast every 30 minutes for 3 hours after the latest radar image.
        val firstForecast = (last / 1800 + 1) * 1800
        val forecast = (0 until 6).map { MapFrame.Forecast(firstForecast + it * 1800L) }
        val frames = past.mapIndexed { i, fr -> MapFrame.Radar(fr, i == past.lastIndex) } + forecast
        _state.update { it.copy(radarHost = f.host, frames = frames, index = (past.size - 1).coerceAtLeast(0)) }
    }

    /** Called with the drawn size in world pixels, so tiles and the grid match what's on screen. */
    fun setSize(width: Double, height: Double) {
        val v = _state.value.viewport ?: return
        if (abs(v.width - width) < 1 && abs(v.height - height) < 1) return
        _state.update { it.copy(viewport = v.copy(width = width, height = height)) }
    }

    fun pan(dx: Double, dy: Double) {
        val v = _state.value.viewport ?: return
        val size = TileMath.worldSize(v.zoom)
        _state.update { it.copy(viewport = v.copy(cx = (v.cx - dx).mod(size), cy = (v.cy - dy).coerceIn(0.0, size))) }
        loadGrid(force = false)
    }

    fun zoomBy(delta: Int) {
        val v = _state.value.viewport ?: return
        val z = (v.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z == v.zoom) return
        _state.update { it.copy(viewport = v.zoomedTo(z)) }
        loadGrid(force = false)
    }

    fun select(i: Int) {
        playJob?.cancel()
        _state.update { it.copy(index = i.coerceIn(0, (it.frames.size - 1).coerceAtLeast(0)), playing = false) }
    }

    fun togglePlay() {
        if (_state.value.playing) {
            playJob?.cancel()
            _state.update { it.copy(playing = false) }
            return
        }
        _state.update { it.copy(playing = true) }
        playJob = viewModelScope.launch {
            while (isActive) {
                delay(650)
                _state.update { s -> s.copy(index = if (s.frames.isEmpty()) 0 else (s.index + 1) % s.frames.size) }
            }
        }
    }

    fun toggleRadar() = _state.update { it.copy(radarOn = !it.radarOn) }
    fun toggleThunder() = _state.update { it.copy(thunderOn = !it.thunderOn) }

    /** Fetches a new grid when the view has moved well away from the current one (debounced). */
    private fun loadGrid(force: Boolean) {
        val v = _state.value.viewport ?: return
        val g = _state.value.grid
        val halfKm = (TileMath.kmAcross(maxOf(v.width, v.height), v.centerLat, v.zoom) / 2 * 1.2).coerceIn(5.0, 250.0)
        if (!force && g != null) {
            val latSpan = g.north - g.south
            val lonSpan = g.east - g.west
            val inside = abs(v.centerLat - (g.north + g.south) / 2) < latSpan / 4 && abs(v.centerLon - (g.east + g.west) / 2) < lonSpan / 4
            val gridHalfKm = latSpan / 2 * 111.0
            if (inside && halfKm in gridHalfKm * 0.5..gridHalfKm * 1.6) return
        }
        gridJob?.cancel()
        gridJob = viewModelScope.launch {
            if (!force) delay(800)
            runCatching { repo.grid(v.centerLat, v.centerLon, halfKm) }
                .onSuccess { grid -> _state.update { it.copy(grid = grid) } }
                .onFailure { e -> _state.update { it.copy(error = "Forecast rain: ${e.message}") } }
        }
    }

    companion object {
        const val DEFAULT_ZOOM = 9
        const val MIN_ZOOM = 5
        const val MAX_ZOOM = 13

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApp
                RainMapViewModel(app.container.rainMap, app.container.places)
            }
        }
    }
}
