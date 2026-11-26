package app.weathertrack.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.weathertrack.WeatherApp
import app.weathertrack.core.map.Gibs
import app.weathertrack.core.map.RainGrid
import app.weathertrack.core.map.RainViewer
import app.weathertrack.core.map.TileMath
import app.weathertrack.core.map.TimeZoneShapes
import app.weathertrack.core.map.ZoneClock
import app.weathertrack.core.map.Wind
import app.weathertrack.core.util.runCatchingCancellable
import app.weathertrack.data.PlaceRepository
import app.weathertrack.map.FieldKind
import app.weathertrack.map.FieldOverlay
import app.weathertrack.map.MapLayers
import app.weathertrack.map.RainMapRepository
import app.weathertrack.map.TimeZoneMap
import app.weathertrack.map.TimeZoneOverlay
import app.weathertrack.map.Viewport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One step of the timeline: a past radar image, a forecast time, or a satellite image. */
sealed class MapFrame(val time: Long) {
    class Radar(val frame: RainViewer.Frame, val latest: Boolean) : MapFrame(frame.time)
    class Forecast(time: Long) : MapFrame(time)
    class Satellite(time: Long, val latest: Boolean) : MapFrame(time)
    /** A clock time for the time zone map: now, then each hour ahead. */
    class Clock(time: Long, val now: Boolean) : MapFrame(time)
}

/** How far out a layer opens: street level, the whole country, or a good part of the world. */
enum class MapView(val zoom: Int, val minZoom: Int, val maxZoom: Int) {
    NEAR(9, 5, 13),
    WIDE(5, 3, 8),
    WORLD(3, 1, 8),
}

/** What the map shows. Near-you layers open at street level; wide ones over the whole country; world ones further out. */
enum class MapLayer(val title: String, val menuName: String, val source: String, val live: Boolean = false, val view: MapView = MapView.NEAR) {
    RAIN("Rain map", "Rain", "RainViewer radar, then Open-Meteo", live = true),
    CLOUD("Cloud map", "Cloud cover", "Low, mid and high · Open-Meteo"),
    WIND("Wind map", "Wind and gusts", "Open-Meteo"),
    TEMP("Temperature map", "Temperature / feels like", "Open-Meteo"),
    UV("UV map", "UV index", "Open-Meteo"),
    HUMIDITY("Humidity map", "Humidity", "Open-Meteo"),
    FOG("Fog map", "Fog and visibility", "Open-Meteo"),
    SATELLITE("Satellite", "Satellite", "Himawari infrared · NASA GIBS", live = true, view = MapView.WIDE),
    PRESSURE("Pressure and storms", "Pressure and storms", "Open-Meteo · GDACS cyclone tracks", view = MapView.WIDE),
    TIMEZONE("Time zones", "Time zones", "Built into the app · clocks from your phone", live = true, view = MapView.WORLD);

    /** Coloured from the hourly weather grid. */
    val usesFields get() = this in listOf(CLOUD, WIND, TEMP, UV, HUMIDITY, FOG)

    /** Anything but street level. */
    val wide get() = view != MapView.NEAR

    /** Has the pressure lines and cyclone tracks. */
    val showsStorms get() = this == SATELLITE || this == PRESSURE
}

enum class CloudLevel(val label: String, val kind: FieldKind) {
    ALL("All", FieldKind.CLOUD), LOW("Low", FieldKind.CLOUD_LOW), MID("Mid", FieldKind.CLOUD_MID), HIGH("High", FieldKind.CLOUD_HIGH)
}

/** A time zone's clock for the card under the time zone map. */
data class ZoneCard(val info: ZoneClock.Info, val at: Instant, val tapped: Boolean, val homeOffset: Int)

/** The nearest active tropical cyclone to the selected place. */
data class StormNote(val cyclone: RainMapRepository.Cyclone, val km: Double, val compass: String)

data class RainMapState(
    val viewport: Viewport? = null,
    val marker: Pair<Double, Double>? = null,
    val zone: String? = null,
    val radarHost: String? = null,
    val layer: MapLayer = MapLayer.RAIN,
    val cloudLevel: CloudLevel = CloudLevel.ALL,
    val gusts: Boolean = false,
    val feelsLike: Boolean = false,
    val radarFrames: List<MapFrame> = emptyList(),
    val satelliteTimes: List<Long> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val thunderOn: Boolean = true,
    val pressureOn: Boolean = true,
    val stormsOn: Boolean = true,
    val grid: RainGrid? = null,
    val pressure: RainGrid? = null,
    /** Null until cyclones have been checked. */
    val cyclones: List<RainMapRepository.Cyclone>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val timeZones: TimeZoneShapes? = null,
    /** A spot tapped on the time zone map, to show that zone's clock. */
    val picked: Pair<Double, Double>? = null,
    /** Epoch seconds the time zone clocks start from, ticking while that map is open. */
    val clockNow: Long = System.currentTimeMillis() / 1000,
) {
    val frames: List<MapFrame>
        get() = when (layer) {
            MapLayer.RAIN -> radarFrames
            MapLayer.SATELLITE -> satelliteTimes.mapIndexed { i, t -> MapFrame.Satellite(t, i == satelliteTimes.lastIndex) }
            MapLayer.PRESSURE -> hourFrames(pressure, every = 3, max = 25)
            MapLayer.TIMEZONE -> clockFrames(clockNow)
            else -> hourFrames(grid, every = 1, max = RainMapRepository.LAYER_HOURS)
        }

    val frame: MapFrame? get() = frames.getOrNull(index)

    /** Where the timeline starts when the layer changes: the latest image, or this hour's forecast. */
    val defaultIndex: Int
        get() = when (layer) {
            MapLayer.RAIN -> radarFrames.indexOfLast { it is MapFrame.Radar }.coerceAtLeast(0)
            MapLayer.SATELLITE -> (satelliteTimes.size - 1).coerceAtLeast(0)
            else -> 0
        }

    val fieldKind: FieldKind?
        get() = when (layer) {
            MapLayer.CLOUD -> cloudLevel.kind
            MapLayer.WIND -> if (gusts) FieldKind.GUSTS else FieldKind.WIND
            MapLayer.TEMP -> if (feelsLike) FieldKind.FEELS else FieldKind.TEMP
            MapLayer.UV -> FieldKind.UV
            MapLayer.HUMIDITY -> FieldKind.HUMIDITY
            MapLayer.FOG -> FieldKind.VISIBILITY
            else -> null
        }

    val layers: MapLayers
        get() {
            val f = frame
            val storms = cyclones.orEmpty().takeIf { stormsOn }.orEmpty()
            return when (layer) {
                MapLayer.RAIN -> MapLayers(
                    radarHost = radarHost,
                    radarFrame = (f as? MapFrame.Radar)?.frame,
                    grid = grid,
                    forecastAt = (f as? MapFrame.Forecast)?.time,
                    thunderAt = f?.time?.takeIf { thunderOn },
                    marker = marker,
                )
                MapLayer.SATELLITE -> MapLayers(
                    satelliteTime = f?.time,
                    pressure = pressure?.takeIf { pressureOn }, pressureAt = f?.time,
                    cyclones = storms, marker = marker,
                )
                MapLayer.TIMEZONE -> MapLayers(
                    timeZones = timeZones?.let { TimeZoneOverlay(it, f?.time ?: clockNow, zone, pickedZone?.id) },
                    marker = marker,
                )
                MapLayer.PRESSURE -> MapLayers(
                    grid = pressure,
                    field = f?.let { FieldOverlay(FieldKind.WIND, it.time, labels = false) },
                    pressure = pressure?.takeIf { pressureOn }, pressureAt = f?.time,
                    cyclones = storms, marker = marker,
                )
                else -> MapLayers(
                    grid = grid,
                    field = f?.let { fr -> fieldKind?.let { FieldOverlay(it, fr.time) } },
                    thunderAt = f?.time?.takeIf { thunderOn },
                    marker = marker,
                )
            }
        }

    /** The layer's value at the selected place for the frame shown, such as "18°" or "SW 24 km/h, gusts 41". */
    val spotValue: String?
        get() {
            val g = grid ?: return null
            val (lat, lon) = marker ?: return null
            val kind = fieldKind ?: return null
            val h = frame?.let { g.hourIndex(it.time) ?: g.nearestHour(it.time) } ?: return null
            if (layer == MapLayer.WIND) {
                val w: Wind = g.windAt(lat, lon, h) ?: return null
                val gust = g.fieldAt("wind_gusts_10m", lat, lon, h)
                return "${w.compass} ${w.speed.roundToInt()} km/h" + (gust?.let { ", gusts ${it.roundToInt()}" } ?: "")
            }
            return g.fieldAt(kind.varName, lat, lon, h)?.let { kind.label(it) }
        }

    /** Nearest active cyclone to the selected place, if any are active. */
    val stormNote: StormNote?
        get() {
            val (lat, lon) = marker ?: return null
            return cyclones.orEmpty().map { c ->
                val km = distanceKm(lat, lon, c.event.lat, c.event.lon)
                StormNote(c, km, compass(lat, lon, c.event.lat, c.event.lon))
            }.minByOrNull { it.km }
        }

    /** The zone tapped on the time zone map, if any. */
    val pickedZone: TimeZoneShapes.Zone?
        get() = picked?.let { (lat, lon) -> timeZones?.zoneAt(lat, lon) }

    /** What the time zone card describes: the tapped zone, or the selected place's own. */
    val zoneCard: ZoneCard?
        get() {
            if (layer != MapLayer.TIMEZONE) return null
            val at = Instant.ofEpochSecond(frame?.time ?: clockNow)
            val id = pickedZone?.id ?: zone ?: return null
            val info = ZoneClock.info(id, at) ?: return null
            return ZoneCard(info, at, tapped = pickedZone != null && id != zone, homeOffset = ZoneClock.offsetOrUtc(zone, at))
        }

    private fun clockFrames(now: Long): List<MapFrame> {
        val nextHour = (now / 3600 + 1) * 3600
        return listOf(MapFrame.Clock(now, now = true)) + (0 until 24).map { MapFrame.Clock(nextHour + it * 3600L, now = false) }
    }

    private fun hourFrames(g: RainGrid?, every: Int, max: Int): List<MapFrame> {
        g ?: return emptyList()
        val thisHour = System.currentTimeMillis() / 1000 / 3600 * 3600
        return g.hourTimes.filter { it >= thisHour }.filterIndexed { i, _ -> i % every == 0 }.take(max).map { MapFrame.Forecast(it) }
    }

    companion object {
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * 6371.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        /** Compass direction from the first point to the second, such as "north-west". */
        fun compass(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val b = Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)))
            val names = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
            return names[(((b + 360) % 360 + 22.5) / 45).toInt() % 8]
        }
    }
}

class RainMapViewModel(
    private val repo: RainMapRepository,
    private val places: PlaceRepository,
    private val timeZoneMap: TimeZoneMap,
) : ViewModel() {
    private val _state = MutableStateFlow(RainMapState())
    val state: StateFlow<RainMapState> = _state
    private var playJob: Job? = null
    private var gridJob: Job? = null
    private var pressureJob: Job? = null
    private var clockJob: Job? = null
    /** Where each kind of view was, so switching between near, wide and world layers comes back to it. */
    private val views = HashMap<MapView, Viewport>()

    init {
        viewModelScope.launch {
            places.ensureDefaults()
            places.selectedId.collectLatest {
                val p = places.selected() ?: return@collectLatest
                views.clear()
                _state.update {
                    it.copy(
                        viewport = Viewport.at(p.latitude, p.longitude, it.layer.view.zoom, it.viewport?.width ?: 400.0, it.viewport?.height ?: 700.0),
                        marker = p.latitude to p.longitude, zone = p.timezone, grid = null, pressure = null, picked = null,
                    )
                }
                refresh()
            }
        }
    }

    /** Reloads everything the current layer needs. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (_state.value.layer) {
                MapLayer.RAIN -> runCatchingCancellable { repo.radarFrames() }
                    .onSuccess { f -> setFrames(f) }
                    .onFailure { e -> _state.update { it.copy(error = "Radar: ${e.message}") } }
                MapLayer.SATELLITE -> loadSatellite()
                MapLayer.TIMEZONE -> loadTimeZones()
                else -> Unit
            }
            _state.update { it.copy(loading = false) }
            loadForView(force = true)
            if (_state.value.layer.showsStorms) loadStorms()
        }
    }

    fun selectLayer(layer: MapLayer) {
        val s = _state.value
        if (layer == s.layer) return
        playJob?.cancel()
        var v = s.viewport
        if (layer.view != s.layer.view && v != null) {
            views[s.layer.view] = v
            val (lat, lon) = s.marker ?: (v.centerLat to v.centerLon)
            v = views[layer.view]?.copy(width = v.width, height = v.height) ?: Viewport.at(lat, lon, layer.view.zoom, v.width, v.height)
        }
        _state.update { it.copy(layer = layer, viewport = v, playing = false, error = null, clockNow = System.currentTimeMillis() / 1000) }
        _state.update { it.copy(index = it.defaultIndex) }
        clockJob?.cancel()
        if (layer == MapLayer.TIMEZONE) startClock()
        viewModelScope.launch {
            when (layer) {
                MapLayer.RAIN -> if (_state.value.radarFrames.isEmpty()) runCatchingCancellable { repo.radarFrames() }.onSuccess { setFrames(it) }
                MapLayer.SATELLITE -> loadSatellite()
                MapLayer.TIMEZONE -> loadTimeZones()
                else -> Unit
            }
            if (layer.showsStorms) loadStorms()
        }
        loadForView(force = true)
    }

    private fun setFrames(f: RainViewer.Frames) {
        val past = f.past.takeLast(13)
        val last = past.lastOrNull()?.time ?: (System.currentTimeMillis() / 1000)
        // Forecast every 30 minutes for 3 hours after the latest radar image.
        val firstForecast = (last / 1800 + 1) * 1800
        val forecast = (0 until 6).map { MapFrame.Forecast(firstForecast + it * 1800L) }
        val frames = past.mapIndexed { i, fr -> MapFrame.Radar(fr, i == past.lastIndex) } + forecast
        _state.update {
            val s = it.copy(radarHost = f.host, radarFrames = frames)
            if (s.layer == MapLayer.RAIN) s.copy(index = s.defaultIndex) else s
        }
    }

    private suspend fun loadSatellite() {
        runCatchingCancellable { repo.satelliteLatest() }
            .onSuccess { latest ->
                _state.update {
                    val s = it.copy(satelliteTimes = Gibs.frames(latest))
                    if (s.layer == MapLayer.SATELLITE) s.copy(index = s.defaultIndex) else s
                }
            }
            .onFailure { e -> _state.update { it.copy(error = "Satellite: ${e.message}") } }
    }

    private suspend fun loadTimeZones() {
        if (_state.value.timeZones != null) return
        runCatchingCancellable { timeZoneMap.shapes() }
            .onSuccess { tz -> _state.update { it.copy(timeZones = tz) } }
            .onFailure { e -> _state.update { it.copy(error = "Time zones: ${e.message}") } }
    }

    /** Keeps the time zone clocks current while that map is open. */
    private fun startClock() {
        clockJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                // Tick just after each minute turns over.
                delay(60_000 - now % 60_000 + 200)
                _state.update { it.copy(clockNow = System.currentTimeMillis() / 1000) }
            }
        }
    }

    /** A tap on the time zone map picks the zone there; tapping it again goes back to your own. */
    fun tap(screenX: Double, screenY: Double) {
        val s = _state.value
        if (s.layer != MapLayer.TIMEZONE) return
        val v = s.viewport ?: return
        val size = TileMath.worldSize(v.zoom)
        val lat = TileMath.lat((v.top + screenY).coerceIn(0.0, size), v.zoom)
        val lon = TileMath.lon((v.left + screenX).mod(size), v.zoom)
        val zone = s.timeZones?.zoneAt(lat, lon)
        _state.update { it.copy(picked = if (zone == null || zone.id == it.pickedZone?.id) null else lat to lon) }
    }

    fun clearPick() = _state.update { it.copy(picked = null) }

    private suspend fun loadStorms() {
        runCatchingCancellable { repo.cyclones() }
            .onSuccess { list -> _state.update { it.copy(cyclones = list) } }
            .onFailure { e -> _state.update { it.copy(error = "Cyclones: ${e.message}") } }
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
        loadForView(force = false)
    }

    fun zoomBy(delta: Int) {
        val v = _state.value.viewport ?: return
        val view = _state.value.layer.view
        val z = (v.zoom + delta).coerceIn(view.minZoom, view.maxZoom)
        if (z == v.zoom) return
        _state.update { it.copy(viewport = v.zoomedTo(z)) }
        loadForView(force = false)
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

    fun toggleThunder() = _state.update { it.copy(thunderOn = !it.thunderOn) }
    fun togglePressure() = _state.update { it.copy(pressureOn = !it.pressureOn) }
    fun toggleStorms() {
        _state.update { it.copy(stormsOn = !it.stormsOn) }
        if (_state.value.stormsOn && _state.value.cyclones == null) viewModelScope.launch { loadStorms() }
    }
    fun setCloudLevel(l: CloudLevel) = _state.update { it.copy(cloudLevel = l) }
    fun setGusts(on: Boolean) = _state.update { it.copy(gusts = on) }
    fun setFeelsLike(on: Boolean) = _state.update { it.copy(feelsLike = on) }

    private fun loadForView(force: Boolean) {
        val layer = _state.value.layer
        when {
            layer.showsStorms -> loadPressure(force)
            layer == MapLayer.TIMEZONE -> Unit
            else -> loadGrid(force)
        }
    }

    /** True when the view is still well inside [g] and about the same size, so no new grid is needed. */
    private fun covers(g: RainGrid, v: Viewport, halfKm: Double): Boolean {
        val latSpan = g.north - g.south
        val lonSpan = g.east - g.west
        val inside = abs(v.centerLat - (g.north + g.south) / 2) < latSpan / 4 && abs(v.centerLon - (g.east + g.west) / 2) < lonSpan / 4
        val gridHalfKm = latSpan / 2 * 111.0
        return inside && halfKm in gridHalfKm * 0.5..gridHalfKm * 1.6
    }

    /** Fetches a new grid when the view has moved well away from the current one (debounced). */
    private fun loadGrid(force: Boolean) {
        val v = _state.value.viewport ?: return
        val layers = _state.value.layer.usesFields
        val g = _state.value.grid
        val halfKm = (TileMath.kmAcross(maxOf(v.width, v.height), v.centerLat, v.zoom) / 2 * 1.2).coerceIn(5.0, 250.0)
        // A grid without the weather layers' values isn't enough for those layers.
        if (!force && g != null && (!layers || g.fields.isNotEmpty()) && covers(g, v, halfKm)) return
        gridJob?.cancel()
        gridJob = viewModelScope.launch {
            if (!force) delay(800)
            runCatchingCancellable { repo.grid(v.centerLat, v.centerLon, halfKm, layers = layers) }
                .onSuccess { grid ->
                    _state.update {
                        val s = it.copy(grid = grid)
                        // The hourly timeline only exists once the grid has arrived.
                        if (s.layer.usesFields && it.grid?.hourTimes != grid.hourTimes) s.copy(index = s.index.coerceIn(0, (s.frames.size - 1).coerceAtLeast(0))) else s
                    }
                }
                .onFailure { e -> _state.update { it.copy(error = "Forecast: ${e.message}") } }
        }
    }

    /** The wide pressure grid for the satellite and storm views. */
    private fun loadPressure(force: Boolean) {
        val v = _state.value.viewport ?: return
        val g = _state.value.pressure
        val halfKm = (TileMath.kmAcross(maxOf(v.width, v.height), v.centerLat, v.zoom) / 2 * 1.2).coerceIn(300.0, 3000.0)
        if (!force && g != null && covers(g, v, halfKm)) return
        pressureJob?.cancel()
        pressureJob = viewModelScope.launch {
            if (!force) delay(800)
            runCatchingCancellable { repo.pressureGrid(v.centerLat, v.centerLon, halfKm) }
                .onSuccess { grid -> _state.update { it.copy(pressure = grid) } }
                .onFailure { e -> _state.update { it.copy(error = "Pressure: ${e.message}") } }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApp
                RainMapViewModel(app.container.rainMap, app.container.places, app.container.timeZones)
            }
        }
    }
}
