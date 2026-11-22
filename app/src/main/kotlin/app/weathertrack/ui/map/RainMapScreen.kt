package app.weathertrack.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.weathertrack.core.map.RainBand
import app.weathertrack.map.MapPainter
import app.weathertrack.map.TileStore
import app.weathertrack.ui.Format
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Badge = Color(0xFFD1E4FF)
private val OnBadge = Color(0xFF001D36)
private val ForecastBadge = Color(0xFFFFDCC2)
private val OnForecastBadge = Color(0xFF2E1500)
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

        // Top bar: title, Now / Past / Forecast badge, refresh.
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rain map", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    val f = s.frame
                    val (label, bg, fg) = when {
                        f is MapFrame.Forecast -> Triple("Forecast", ForecastBadge, OnForecastBadge)
                        f is MapFrame.Radar && f.latest -> Triple("Now", Badge, OnBadge)
                        f is MapFrame.Radar -> Triple("Past", Badge, OnBadge)
                        else -> Triple("…", Badge, OnBadge)
                    }
                    Text(label, Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
                        color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Reload radar and forecast") }
                }
            }
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.radarOn, onClick = vm::toggleRadar, label = { Text("Rain") })
                FilterChip(selected = s.thunderOn, onClick = vm::toggleThunder, label = { Text("Thunder risk") })
            }
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Legend()
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
                                Text(rel(s.frames.first().time - nowTime(s)), Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Now", Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(rel(s.frames.last().time - nowTime(s)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text(TileStore.ATTRIBUTION, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun nowTime(s: RainMapState): Long = s.frames.filterIsInstance<MapFrame.Radar>().lastOrNull()?.time ?: (System.currentTimeMillis() / 1000)

private fun rel(seconds: Long): String {
    val h = seconds / 3600.0
    return (if (h >= 0) "+" else "−") + String.format(Locale.US, "%.0f h", kotlin.math.abs(h))
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
