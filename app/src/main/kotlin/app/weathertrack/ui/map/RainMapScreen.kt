package app.weathertrack.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.weathertrack.WeatherApp
import app.weathertrack.core.map.ColorScale
import app.weathertrack.core.map.LayerScales
import app.weathertrack.core.map.RainBand
import app.weathertrack.map.FieldKind
import app.weathertrack.map.MapPainter
import app.weathertrack.map.TileStore
import app.weathertrack.ui.Format
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val Badge = Color(0xFFD1E4FF)
private val OnBadge = Color(0xFF001D36)
private val ForecastBadge = Color(0xFFFFDCC2)
private val OnForecastBadge = Color(0xFF2E1500)
private val LiveBadge = Color(0xFFD1E4FF)
private val dayTime = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH)

@Composable
fun RainMapScreen(vm: RainMapViewModel = viewModel(factory = RainMapViewModel.Factory)) {
    val s by vm.state.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as WeatherApp
    val tiles = app.container.tiles
    val painter = remember(tiles) { MapPainter(tiles) }
    val tileVersion by tiles.version.collectAsStateWithLifecycle()
    // One world pixel per density-independent pixel: 256 dp per tile, drawn from 512 px tiles.
    val scale = LocalDensity.current.density
    val zone = Format.zone(s.zone)
    val zoomAccum = remember { floatArrayOf(1f) }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(scale) {
                detectTransformGestures { _, pan, zoom, _ ->
                    vm.pan(pan.x / scale.toDouble(), pan.y / scale.toDouble())
                    zoomAccum[0] *= zoom
                    if (zoomAccum[0] > 1.6f) { vm.zoomBy(1); zoomAccum[0] = 1f }
                    if (zoomAccum[0] < 0.6f) { vm.zoomBy(-1); zoomAccum[0] = 1f }
                }
            },
        ) {
            vm.setSize(size.width / scale.toDouble(), size.height / scale.toDouble())
            val v = s.viewport ?: return@Canvas
            @Suppress("UNUSED_EXPRESSION") tileVersion // redraw when tiles arrive
            drawIntoCanvas { painter.draw(it.nativeCanvas, v, scale, s.layers) }
        }

        // Top bar: the title is the layer menu, then the Now / Past / Forecast badge and refresh.
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        var open by remember { mutableStateOf(false) }
                        Row(
                            Modifier.clip(RoundedCornerShape(20.dp)).clickable { open = true }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.layer.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose map layer")
                        }
                        LayerMenu(open, s.layer, onDismiss = { open = false }) { open = false; vm.selectLayer(it) }
                    }
                    val f = s.frame
                    val (label, bg, fg) = when {
                        f is MapFrame.Forecast -> Triple("Forecast", ForecastBadge, OnForecastBadge)
                        f is MapFrame.Radar && f.latest -> Triple("Now", Badge, OnBadge)
                        f is MapFrame.Radar -> Triple("Past", Badge, OnBadge)
                        f is MapFrame.Satellite && f.latest -> Triple("Live", LiveBadge, OnBadge)
                        f is MapFrame.Satellite -> Triple("Past", Badge, OnBadge)
                        else -> Triple("…", Badge, OnBadge)
                    }
                    Text(label, Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
                        color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Reload the map") }
                }
            }
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Spacer(Modifier.size(8.dp))
            LayerChips(s, vm)
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                when (s.layer) {
                    MapLayer.RAIN -> Legend()
                    MapLayer.SATELLITE -> NoteBox("Satellite", "Infrared: white and colours are cold, high cloud tops.\nLines are sea-level pressure every 4 hPa.")
                    MapLayer.PRESSURE -> ScaleLegend("Wind km/h", LayerScales.WIND, "0", "50", "100", "Lines: pressure every 4 hPa")
                    else -> s.fieldKind?.let { FieldLegend(it, s.spotValue) }
                }
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = { vm.zoomBy(1) }) { Icon(Icons.Filled.Add, contentDescription = "Zoom in") }
                    FilledTonalIconButton(onClick = { vm.zoomBy(-1) }) { Text("–", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                }
            }
            s.error?.let {
                Text(it, Modifier.padding(top = 8.dp).background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
            }
        }

        if (s.layer.wide && s.stormsOn) StormCard(s, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 150.dp))

        // Timeline.
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalIconButton(onClick = vm::togglePlay, modifier = Modifier.size(48.dp)) {
                        if (s.playing) Text("❚❚", fontWeight = FontWeight.Bold) else Icon(Icons.Filled.PlayArrow, contentDescription = "Play radar loop")
                    }
                    Column(Modifier.weight(1f)) {
                        val f = s.frame
                        Row {
                            Text(f?.let { Instant.ofEpochSecond(it.time).atZone(zone).format(dayTime) } ?: "Loading…",
                                Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    f is MapFrame.Satellite -> "Satellite · NASA GIBS"
                                    f is MapFrame.Forecast -> "Forecast · Open-Meteo"
                                    f is MapFrame.Radar && f.latest -> "Radar · latest"
                                    else -> "Radar · RainViewer"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                            )
                        }
                        val n = s.frames.size
                        if (n > 1) {
                            Slider(
                                value = s.index.toFloat(),
                                onValueChange = { vm.select(it.toInt()) },
                                valueRange = 0f..(n - 1).toFloat(),
                                steps = (n - 2).coerceAtLeast(0),
                            )
                            Row {
                                val now = nowTime(s)
                                Text(rel(s.frames.first().time - now), Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (s.layer == MapLayer.RAIN) Text("Now", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(rel(s.frames.last().time - now), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text(attribution(s.layer), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun nowTime(s: RainMapState): Long =
    s.frames.filterIsInstance<MapFrame.Radar>().lastOrNull()?.time ?: (System.currentTimeMillis() / 1000)

private fun rel(seconds: Long): String {
    val h = seconds / 3600.0
    if (kotlin.math.abs(h) < 0.5) return "Now"
    return (if (h >= 0) "+" else "−") + String.format(Locale.US, "%.0f h", kotlin.math.abs(h))
}

private fun attribution(layer: MapLayer): String = when (layer) {
    MapLayer.RAIN -> TileStore.ATTRIBUTION
    MapLayer.SATELLITE -> "© OpenStreetMap contributors · Satellite: NASA GIBS, JMA Himawari · Pressure: Open-Meteo · Cyclones: GDACS"
    MapLayer.PRESSURE -> "© OpenStreetMap contributors · Forecast: Open-Meteo · Cyclones: GDACS"
    else -> "© OpenStreetMap contributors · Forecast: Open-Meteo"
}

/** The layer menu under the title: near-you layers, then whole-country ones, each with its source. */
@Composable
private fun LayerMenu(open: Boolean, current: MapLayer, onDismiss: () -> Unit, onPick: (MapLayer) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        MenuHeading("Near you")
        MapLayer.entries.filter { !it.wide }.forEach { LayerItem(it, it == current, onPick) }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        MenuHeading("Whole country")
        MapLayer.entries.filter { it.wide }.forEach { LayerItem(it, it == current, onPick) }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LayerItem(layer: MapLayer, selected: Boolean, onPick: (MapLayer) -> Unit) {
    DropdownMenuItem(
        modifier = if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
        leadingIcon = { Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(swatch(layer))) },
        text = {
            Column {
                Text(layer.menuName, fontSize = 15.sp)
                Text(layer.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingIcon = {
            val (t, bg, fg) = if (layer.live) Triple("Live", LiveBadge, OnBadge) else Triple("Forecast", ForecastBadge, OnForecastBadge)
            Text(t, Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
                color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        },
        onClick = { onPick(layer) },
    )
}

private fun swatch(layer: MapLayer): Brush = when (layer) {
    MapLayer.RAIN -> Brush.linearGradient(listOf(Color(0xFF4F8FD0), Color(0xFF0C2A5A)))
    MapLayer.CLOUD -> Brush.linearGradient(listOf(Color(0xFFDCE6F1), Color(0xFF3E4A5A)))
    MapLayer.WIND -> gradient(LayerScales.WIND, opaque = true)
    MapLayer.TEMP -> gradient(LayerScales.TEMP, opaque = true)
    MapLayer.UV -> gradient(LayerScales.UV, opaque = true)
    MapLayer.HUMIDITY -> gradient(LayerScales.HUMIDITY, opaque = true)
    MapLayer.FOG -> Brush.linearGradient(listOf(Color(0xFFEEF1F5), Color(0xFF8A94A6)))
    MapLayer.SATELLITE -> Brush.linearGradient(listOf(Color(0xFF16202C), Color(0xFFE9EEF5)))
    MapLayer.PRESSURE -> Brush.linearGradient(listOf(Color(0xFF1F5A9C), Color.White, Color(0xFFC0392B)))
}

/** A gradient through the scale's colours, spaced by value. */
private fun gradient(scale: ColorScale, opaque: Boolean = false, horizontal: Boolean = false): Brush {
    val span = scale.max - scale.min
    val stops = scale.stops.map { (v, c) ->
        val col = Color(c)
        ((v - scale.min) / span).toFloat() to if (opaque) col.copy(alpha = 1f) else col
    }.toTypedArray()
    return if (horizontal) Brush.horizontalGradient(*stops) else Brush.linearGradient(*stops)
}

/** Chips for the current layer's options and the extras drawn on top. */
@Composable
private fun LayerChips(s: RainMapState, vm: RainMapViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (s.layer) {
            MapLayer.CLOUD -> CloudLevel.entries.forEach { l ->
                FilterChip(selected = s.cloudLevel == l, onClick = { vm.setCloudLevel(l) }, label = { Text(l.label) })
            }
            MapLayer.WIND -> {
                FilterChip(selected = !s.gusts, onClick = { vm.setGusts(false) }, label = { Text("Speed") })
                FilterChip(selected = s.gusts, onClick = { vm.setGusts(true) }, label = { Text("Gusts") })
            }
            MapLayer.TEMP -> {
                FilterChip(selected = !s.feelsLike, onClick = { vm.setFeelsLike(false) }, label = { Text("Actual") })
                FilterChip(selected = s.feelsLike, onClick = { vm.setFeelsLike(true) }, label = { Text("Feels like") })
            }
            else -> Unit
        }
        if (s.layer.wide) {
            FilterChip(selected = s.pressureOn, onClick = vm::togglePressure, label = { Text("Pressure") })
            FilterChip(selected = s.stormsOn, onClick = vm::toggleStorms, label = { Text("Storms") })
        } else {
            FilterChip(selected = s.thunderOn, onClick = vm::toggleThunder, label = { Text("Thunder risk") })
        }
    }
}

@Composable
private fun LegendBox(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun ScaleLegend(title: String, scale: ColorScale, low: String, mid: String, high: String, note: String? = null, spot: String? = null) {
    LegendBox {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.width(130.dp).size(130.dp, 10.dp).clip(RoundedCornerShape(3.dp)).background(Color.White).background(gradient(scale, horizontal = true)))
        Row(Modifier.width(130.dp)) {
            Text(low, Modifier.weight(1f), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(mid, Modifier.weight(1f), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(high, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        note?.let { Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        spot?.let { Text("At your spot: $it", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun FieldLegend(kind: FieldKind, spot: String?) = when (kind) {
    FieldKind.CLOUD -> ScaleLegend("Cloud cover", kind.scale, "0%", "50%", "100%", spot = spot)
    FieldKind.CLOUD_LOW -> ScaleLegend("Low cloud", kind.scale, "0%", "50%", "100%", "Below about 2 km: fog, stratus, grey days", spot)
    FieldKind.CLOUD_MID -> ScaleLegend("Mid cloud", kind.scale, "0%", "50%", "100%", "About 2 to 6 km", spot)
    FieldKind.CLOUD_HIGH -> ScaleLegend("High cloud", kind.scale, "0%", "50%", "100%", "Thin, icy cloud above 6 km", spot)
    FieldKind.WIND -> ScaleLegend("Wind km/h", kind.scale, "0", "50", "100", "Arrows point where it blows", spot)
    FieldKind.GUSTS -> ScaleLegend("Gusts km/h", kind.scale, "0", "50", "100", "Arrows point where it blows", spot)
    FieldKind.TEMP -> ScaleLegend("Temperature °C", kind.scale, "0°", "20°", "40°", spot = spot)
    FieldKind.FEELS -> ScaleLegend("Feels like °C", kind.scale, "0°", "20°", "40°", spot = spot)
    FieldKind.UV -> ScaleLegend("UV index", kind.scale, "0", "6", "11+", "3+ sunscreen, 8+ very high", spot)
    FieldKind.HUMIDITY -> ScaleLegend("Humidity", kind.scale, "30%", "65%", "100%", spot = spot)
    FieldKind.VISIBILITY -> ScaleLegend("Fog and visibility", kind.scale, "0 km", "5", "10+", "Grey is fog or haze", spot)
}

@Composable
private fun NoteBox(title: String, text: String) {
    LegendBox {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(text, Modifier.width(170.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The nearest active tropical cyclone, or a note that there are none. */
@Composable
private fun StormCard(s: RainMapState, modifier: Modifier) {
    val cyclones = s.cyclones ?: return
    val note = s.stormNote
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (note == null || cyclones.isEmpty()) {
                Text("No tropical cyclones active anywhere right now.", fontSize = 13.sp)
                Text("Checked with GDACS. The South Pacific season runs November to April.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val e = note.cyclone.event
                val km = String.format(Locale.US, "%,d", (note.km / 10).roundToInt() * 10)
                Text("🌀 Cyclone ${e.name}: ${e.alert} alert" + (e.maxWindKmh?.let { ", winds up to ${it.roundToInt()} km/h" } ?: ""),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("About $km km ${note.compass} of you." + if (cyclones.size > 1) " ${cyclones.size - 1} more active elsewhere." else "",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Solid line: track so far. Dashed: forecast, inside the shaded cone of where it may go.", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Legend() {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Rain", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            RainBand.entries.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width(14.dp).size(14.dp, 10.dp).background(Color(MapPainter.BAND_COLORS.getValue(b)), RoundedCornerShape(2.dp)))
                    Text(b.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Forecast colours; radar uses\nRainViewer's blues", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
