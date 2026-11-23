package app.weathertrack.ui.sources

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.weathertrack.WeatherApp
import app.weathertrack.core.sources.DayScore
import app.weathertrack.core.sources.Lead
import app.weathertrack.core.sources.PastHour
import app.weathertrack.core.sources.Sources
import app.weathertrack.core.sources.Settled
import app.weathertrack.core.sources.SourceScore
import app.weathertrack.core.sources.SourceWeight
import app.weathertrack.core.sources.Variable
import androidx.compose.material3.TextButton
import app.weathertrack.data.ForecastCheckRepository
import app.weathertrack.data.PlaceRepository
import app.weathertrack.data.SourceRepository
import app.weathertrack.data.SourceView
import app.weathertrack.data.WeatherRepository
import app.weathertrack.data.db.PlaceEntity
import app.weathertrack.ui.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class SourcesState(
    val place: PlaceEntity? = null,
    val view: SourceView? = null,
    val loading: Boolean = false,
    /** How far ahead each source's forecasts settled over the last week. */
    val settled: List<Settled> = emptyList(),
)

class SourcesViewModel(
    private val sources: SourceRepository,
    private val places: PlaceRepository,
    private val weather: WeatherRepository,
    private val check: ForecastCheckRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SourcesState())
    val state: StateFlow<SourcesState> = _state

    init { viewModelScope.launch { places.selectedId.collect { load(force = false) } } }

    private var job: Job? = null
    private var loads = 0

    /** Loads the selected place, cancelling any earlier load so an old place can't overwrite a newer one. */
    fun load(force: Boolean) {
        job?.cancel()
        val mine = ++loads
        job = viewModelScope.launch {
            val place = places.selected() ?: return@launch
            _state.update { it.copy(place = place, view = if (it.place?.id == place.id) it.view else null, loading = true) }
            try {
                runCatching { sources.refresh(place, weather.cachedForecast(place), if (force) "manual" else "sources", force) }
                val from = System.currentTimeMillis() / 1000 - 60 * 86400L
                val obs = runCatching { check.observed(place.id, from) }.getOrDefault(emptyList())
                val view = runCatching { sources.view(place.id, Format.zone(place.timezone), obs, check.reports(place.id, from)) }.getOrNull()
                if (mine == loads) _state.update { it.copy(view = view) }
                val weekFrom = System.currentTimeMillis() / 1000 - 8 * 86400L
                val settled = runCatching {
                    withContext(Dispatchers.Default) { sources.settled(place.id, obs.filter { it.time >= weekFrom }) }
                }.getOrDefault(emptyList())
                if (mine == loads) _state.update { it.copy(settled = settled) }
            } finally {
                if (mine == loads) _state.update { it.copy(loading = false) }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val c = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApp).container
                SourcesViewModel(c.sources, c.places, c.weather, c.forecastCheck)
            }
        }
    }
}


private val Rain = Color(0xFF2F7DD1)
private val Cloud = Color(0xFF78909C)
private val Ink = Color(0xFF1B1B1F)

/** What the whole page shows: one switch at the top drives every section. */
private enum class Measure(val label: String, val variable: Variable) {
    RAIN("Rain", Variable.RAIN), TEMP("Temp", Variable.TEMP), WIND("Wind", Variable.WIND), CLOUD("Cloud", Variable.CLOUD)
}

/** A score for [m]: share right for rain, mean miss for the rest. */
private fun metric(sc: SourceScore?, m: Measure): Double? = when (m) {
    Measure.RAIN -> sc?.wetDryRight
    Measure.TEMP -> sc?.tempMae
    Measure.WIND -> sc?.windMae
    Measure.CLOUD -> sc?.cloudMae
}

private fun hoursOf(sc: SourceScore?, m: Measure): Int = when (m) {
    Measure.RAIN -> sc?.rainHours?.takeIf { it > 0 } ?: sc?.hours ?: 0
    Measure.TEMP -> sc?.tempHours?.takeIf { it > 0 } ?: sc?.hours ?: 0
    Measure.WIND -> sc?.windHours ?: 0
    Measure.CLOUD -> sc?.cloudHours ?: 0
}

private fun weightOf(w: SourceWeight?, m: Measure): Double = when (m) {
    Measure.RAIN -> w?.rain
    Measure.TEMP -> w?.temp
    Measure.WIND -> w?.wind
    Measure.CLOUD -> w?.cloud
} ?: 0.0

/** 0 (poor) to 1 (good) for colouring a score. */
private fun goodness(v: Double, m: Measure): Double = when (m) {
    Measure.RAIN -> (v - 0.55) / 0.40
    Measure.TEMP -> 1 - (v - 0.5) / 2.5
    Measure.WIND -> 1 - (v - 3.0) / 10.0
    Measure.CLOUD -> 1 - (v - 8.0) / 35.0
}.coerceIn(0.0, 1.0)

private fun fmt(v: Double?, m: Measure): String = when {
    v == null -> "–"
    m == Measure.RAIN -> "${(v * 100).roundToInt()}%"
    m == Measure.TEMP -> String.format(java.util.Locale.US, "%.1f°", v)
    else -> "${v.roundToInt()}"
}

private fun scoreName(m: Measure): String = when (m) {
    Measure.RAIN -> "Rain/dry right"
    Measure.TEMP -> "Temp off by"
    Measure.WIND -> "Wind off by"
    Measure.CLOUD -> "Cloud off by"
}

private fun scoreNote(m: Measure): String = when (m) {
    Measure.RAIN -> "Share of hours where rain or no rain was called right."
    Measure.TEMP -> "Average temperature miss in °C."
    Measure.WIND -> "Average wind speed miss in km/h."
    Measure.CLOUD -> "Average cloud cover miss in percentage points."
}

/** Fewer hours than this checked: the cell is faded so thin evidence doesn't read as firm. */
private const val THIN_HOURS = 24

private val HeatStops = listOf(
    0.0 to Color(0xFFD73027), 0.25 to Color(0xFFF48C43), 0.5 to Color(0xFFFAE178), 0.75 to Color(0xFF96CD6E), 1.0 to Color(0xFF1E9650),
)

/** A smooth poor-to-good colour: red, orange, yellow, light green, green. */
private fun heat(g: Double): Color {
    val x = g.coerceIn(0.0, 1.0)
    val (a, b) = HeatStops.zipWithNext().first { x <= it.second.first }
    return lerp(a.second, b.second, ((x - a.first) / (b.first - a.first)).toFloat())
}

private fun heatText(g: Double): Color = if (g < 0.12 || g > 0.9) Color.White else Ink

/** The clock times a lead group covers if a forecast were made [now]. */
private class Windows(now: Long, private val zone: java.time.ZoneId) {
    private val hourStart = now - now % 3600
    private val today = java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    fun start(l: Lead): Long = hourStart + (l.minH - 1) * 3600L
    fun end(l: Lead): Long = hourStart + l.maxH * 3600L

    private fun clock(t: Long, compact: Boolean): String {
        val z = java.time.Instant.ofEpochSecond(t).atZone(zone)
        val h = z.hour % 12
        val time = (if (h == 0) "12" else "$h") + (if (compact) "" else " ") + (if (z.hour < 12) "am" else "pm")
        return if (z.toLocalDate() == today) time
        else z.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.ENGLISH)) + " " + time
    }

    /** "7–10 am", "7 pm–Tue 7 am". */
    fun range(l: Lead): String {
        val a = clock(start(l), false).split(' '); val b = clock(end(l), false)
        val bParts = b.split(' ')
        // Same day and same half of the day: "7–10 am" rather than "7 am–10 am".
        return if (a.size == 2 && bParts.size == 2 && a[1] == bParts[1]) "${a[0]}–$b" else "${a.joinToString(" ")}–$b"
    }

    /** "to 10am", "Tue 7am+". */
    fun short(l: Lead): String = if (l == Lead.NEXT_DAY) clock(start(l), true) + "+" else "to " + clock(end(l), true)
}

/** "≤3 h", "6–12 h", "1–2 days". */
private fun hoursLabel(l: Lead): String = when (l) {
    Lead.SOON -> "≤3 h"
    Lead.NEXT_DAY -> "1–2 days"
    else -> "${l.minH - 1}–${l.maxH} h"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(vm: SourcesViewModel = viewModel(factory = SourcesViewModel.Factory)) {
    val s by vm.state.collectAsStateWithLifecycle()
    var measure by remember { mutableStateOf(Measure.RAIN) }
    var lead by remember { mutableStateOf<Lead?>(Lead.HALF_DAY) }
    var picked by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sources" + (s.place?.let { " · ${it.name}" } ?: "")) },
            actions = { IconButton(onClick = { vm.load(force = true) }) { Icon(Icons.Filled.Refresh, "Refresh") } },
        )
        if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Pinned above the scroll: picks what every section shows.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Measure.entries.forEachIndexed { i, m ->
                SegmentedButton(selected = measure == m, onClick = { measure = m },
                    shape = SegmentedButtonDefaults.itemShape(i, Measure.entries.size)) { Text(m.label) }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val v = s.view
            val zone = Format.zone(s.place?.timezone)
            if (v == null || v.latest.isEmpty()) {
                Text(if (s.loading) "Getting every source…" else "No sources yet. Tap refresh.")
                return@Column
            }
            val now = System.currentTimeMillis() / 1000
            val win = remember(now / 3600, zone) { Windows(now, zone) }
            val source = picked ?: v.weights.maxByOrNull { weightOf(it, measure) }?.source

            Heading("Next 24 hours, side by side", top = false)
            Grid(v, zone, measure)
            Note(when (measure) {
                Measure.RAIN -> "Rain chance %. Darker blue is more likely. Single-run models only say rain or no rain, so their chance shows as about 70–90% " +
                    "when they rain and 5% when dry; ensembles give a real chance, the share of their runs with rain."
                Measure.TEMP -> "Temperature in °C."
                Measure.WIND -> "Wind speed in km/h."
                Measure.CLOUD -> "Total cloud cover in %. Darker is cloudier."
            })

            BlendSection(v, win, measure, now, zone)
            MatrixSection(v, win, measure, lead) { lead = it }
            DetailSection(v, win, measure, lead, source, onLead = { lead = it }, onPick = { picked = it })
            ChartSection(v, measure, source)
            SettleSection(s.settled, measure)
            PastSection(v, zone, measure)

            Note(
                "Sources: Open-Meteo (ECMWF, NOAA GFS, DWD ICON, UK Met Office, GEM, JMA, Météo-France, CMA, " +
                    "and the ECMWF, GFS and ICON ensembles) and MET Norway (CC BY 4.0). MetService has no free forecast feed, so it isn't included.",
            )
        }
    }
}

@Composable
private fun Heading(text: String, top: Boolean = true) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = if (top) Modifier.padding(top = 10.dp) else Modifier)
}

@Composable
private fun SubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HeatLegend(poor: String = "Poor", good: String = "Good") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(poor, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))) {
            val n = 40
            for (i in 0 until n) drawRect(heat(i / (n - 1.0)), Offset(size.width * i / n, 0f), Size(size.width / n + 1, size.height))
        }
        Text(good, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A coloured score cell; [thin] fades it when few hours back it. */
@Composable
private fun HeatCell(text: String, g: Double?, modifier: Modifier, thin: Boolean = false, bold: Boolean = false) {
    val bg = g?.let { heat(it).copy(alpha = if (thin) 0.4f else 1f) } ?: Color.Transparent
    val fg = when {
        g == null || thin -> MaterialTheme.colorScheme.onSurface
        else -> heatText(g)
    }
    Box(
        modifier.height(28.dp).padding(1.dp).clip(RoundedCornerShape(4.dp)).background(bg)
            .then(if (thin && g != null) Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(4.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = fg, maxLines = 1,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun RowScope.NameCell(text: String, weight: Float = 2.2f, bold: Boolean = false) {
    Text(text, Modifier.weight(weight).padding(end = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
}

@Composable
private fun RowScope.HeadCell(text: String, sub: String? = null, weight: Float = 1f, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.weight(weight).clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** A share of the blend as a bar with its percentage. */
@Composable
private fun RowScope.WeightCell(w: Double, weight: Float = 1.6f, scale: Float = 1f) {
    Row(Modifier.weight(weight), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width((w * 120 * scale).coerceIn(2.0, 60.0).dp).height(7.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(4.dp))
        Text("${(w * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun Grid(v: SourceView, zone: java.time.ZoneId, measure: Measure) {
    val now = System.currentTimeMillis() / 1000
    val times = v.blend.map { it.time }.filter { it > now }.filterIndexed { i, _ -> i % 3 == 0 }.take(8)
    val bySource = v.latest.groupBy { it.source }.mapValues { (_, h) -> h.associateBy { it.time } }
    val order = v.weights.map { it.source }.filter { it in bySource }
    val blend = v.blend.associateBy { it.time }
    val today = java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    fun dayOf(t: Long) = java.time.Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            Box(Modifier.width(120.dp).height(22.dp))
            Cell("", 120, bold = true)
            Cell("Blend", 120, bold = true)
            order.forEach { Cell(Sources.label(it), 120) }
        }
        times.forEachIndexed { i, t ->
            Column {
                // Day band: shown on the first column of each day.
                val day = dayOf(t)
                val first = i == 0 || dayOf(times[i - 1]) != day
                Box(Modifier.width(48.dp).height(22.dp)
                    .background(if (day == today) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.CenterStart) {
                    if (first) Text(
                        if (day == today) "Today" else day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale.ENGLISH)),
                        Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
                Cell(Format.hour(t, zone).replace(" ", "").lowercase(), 48, bold = true, center = true)
                val b = blend[t]
                when (measure) {
                    Measure.TEMP -> Cell(Format.temp(b?.temperature), 48, bold = true, center = true)
                    Measure.RAIN -> ChanceCell(b?.rainChance, bold = true)
                    Measure.WIND -> Cell(b?.windSpeed?.roundToInt()?.toString() ?: "–", 48, bold = true, center = true)
                    Measure.CLOUD -> CloudCell(b?.cloudCover, bold = true)
                }
                order.forEach { src ->
                    val h = bySource[src]?.get(t)
                    when (measure) {
                        Measure.TEMP -> Cell(Format.temp(h?.temperature), 48, center = true)
                        Measure.RAIN -> ChanceCell(h?.rainChance)
                        Measure.WIND -> Cell(h?.windSpeed?.roundToInt()?.toString() ?: "–", 48, center = true)
                        Measure.CLOUD -> CloudCell(h?.cloudCover)
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, width: Int, bold: Boolean = false, center: Boolean = false) {
    Box(Modifier.width(width.dp).height(30.dp).padding(horizontal = 2.dp), contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, textAlign = if (center) TextAlign.Center else TextAlign.Start)
    }
}

@Composable
private fun ChanceCell(chance: Double?, bold: Boolean = false) {
    val c = chance ?: -1.0
    val bg = if (c < 0) Color.Transparent else Rain.copy(alpha = (0.08 + 0.8 * c / 100).toFloat())
    Box(Modifier.width(48.dp).height(30.dp).padding(1.dp).background(bg), contentAlignment = Alignment.Center) {
        Text(if (c < 0) "–" else "${c.roundToInt()}", style = MaterialTheme.typography.bodySmall,
            color = if (c >= 55) Color.White else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun CloudCell(cover: Double?, bold: Boolean = false) {
    val c = cover ?: -1.0
    val bg = if (c < 0) Color.Transparent else Cloud.copy(alpha = (0.05 + 0.7 * c / 100).toFloat())
    Box(Modifier.width(48.dp).height(30.dp).padding(1.dp).background(bg), contentAlignment = Alignment.Center) {
        Text(if (c < 0) "–" else "${c.roundToInt()}", style = MaterialTheme.typography.bodySmall,
            color = if (c >= 70) Color.White else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

/** How the blend is put together, and which lead window each upcoming hour uses right now. */
@Composable
private fun BlendSection(v: SourceView, win: Windows, measure: Measure, now: Long, zone: java.time.ZoneId) {
    Heading("How the blend works")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(
            "The blend is a weighted average of every source. Weights come from how each has done here, checked against what " +
                "happened at your spot and your Raining/Dry reports, and are worked out separately for rain, temperature, wind and cloud, " +
                "and for each window of how far ahead. Temperature, wind and cloud weights settle after a couple of days; rain weights stay " +
                "near equal until about a dozen wet hours or reports, and judge wet and dry hours equally.",
            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
        )
    }
    Note("Which window each upcoming hour falls in, from now:")
    val shades = listOf(0.25f, 0.4f, 0.6f, 0.8f, 1f)
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(6.dp))) {
        Lead.entries.forEachIndexed { i, l ->
            val bg = primary.copy(alpha = shades[i])
            Column(
                Modifier.weight(listOf(5f, 5f, 7f, 10f, 12f)[i]).fillMaxSize().background(bg).padding(horizontal = 2.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val fg = if (i < 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
                Text(hoursLabel(l), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
                Text(win.range(l), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = fg, maxLines = 1,
                    textAlign = TextAlign.Center)
            }
        }
    }
    // A worked example: an hour in the 6-12 h window, and the source it leans on most.
    val example = v.blend.firstOrNull { Lead.of((it.time - now) / 3600) == Lead.HALF_DAY }
    val top = v.leadWeights[Lead.HALF_DAY].orEmpty().maxByOrNull { weightOf(it, measure) }
    if (example != null && top != null) {
        val what = when (measure) { Measure.RAIN -> "rain chance"; Measure.TEMP -> "temperature"; Measure.WIND -> "wind"; Measure.CLOUD -> "cloud cover" }
        Text(buildAnnotatedString {
            append("For example, the ${Format.time(example.time, zone)} $what uses the ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("6–12 h") }
            append(" weights, where ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(Sources.label(top.source)) }
            append(" counts most (${(weightOf(top, measure) * 100).roundToInt()}%).")
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every source against every lead window, coloured poor to good. Tapping a column picks it for the detail below. */
@Composable
private fun MatrixSection(v: SourceView, win: Windows, measure: Measure, lead: Lead?, onLead: (Lead) -> Unit) {
    if (v.leadScores.isEmpty()) return
    Heading("Accuracy by how far ahead")
    Note("${scoreNote(measure)} Columns are how far ahead the forecast was made, with the clock times that window covers now. " +
        "Faded cells have under $THIN_HOURS hours checked. Tap a column for its detail.")
    val byLead = Lead.entries.associateWith { l -> v.leadScores[l].orEmpty().associateBy { it.source } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        NameCell("Source", bold = true)
        Lead.entries.forEach { l -> HeadCell(hoursLabel(l), win.short(l), selected = lead == l) { onLead(l) } }
    }
    v.weights.map { it.source }.forEach { src ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NameCell(Sources.label(src))
            Lead.entries.forEach { l ->
                val sc = byLead[l]?.get(src)
                val m = metric(sc, measure)
                HeatCell(fmt(m, measure).removeSuffix("%"), m?.let { goodness(it, measure) }, Modifier.weight(1f),
                    thin = hoursOf(sc, measure) < THIN_HOURS)
            }
        }
    }
    HeatLegend()
}

/** One lead window (or all of them) in full: weights as bars, the score, hours and reports. */
@Composable
private fun DetailSection(
    v: SourceView, win: Windows, measure: Measure, lead: Lead?, picked: String?,
    onLead: (Lead?) -> Unit, onPick: (String) -> Unit,
) {
    Heading("Detail by window")
    Note("Pick how far ahead. Each window shows the clock times it covers if the forecast were made now. Tap a source to follow it in the chart below.")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = lead == null, onClick = { onLead(null) }, label = {
            Column { Text("All windows"); Text("1–30 h ahead", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        })
        Lead.entries.forEach { l ->
            FilterChip(selected = lead == l, onClick = { onLead(l) }, label = {
                Column { Text(win.range(l)); Text("${hoursLabel(l)} ahead", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            })
        }
    }
    val weights = (if (lead == null) v.weights else v.leadWeights[lead].orEmpty()).sortedByDescending { weightOf(it, measure) }
    val scores = (if (lead == null) v.scores else v.leadScores[lead].orEmpty()).associateBy { it.source }
    if (weights.isEmpty()) { Note("Nothing checked in this window yet."); return }
    val top = weights.take(3).joinToString(", ") { Sources.label(it.source) }
    val what = when (measure) { Measure.RAIN -> "Rain"; Measure.TEMP -> "Temperature"; Measure.WIND -> "Wind"; Measure.CLOUD -> "Cloud" }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(
            if (lead == null) "$what overall leans most on $top."
            else "$what for ${win.range(lead)} (${hoursLabel(lead)} ahead) leans most on $top.",
            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        NameCell("Source", bold = true)
        HeadCell("Share of blend", weight = 1.6f)
        HeadCell(scoreName(measure), weight = 1.1f)
        HeadCell(if (measure == Measure.RAIN) "Wet hrs" else "Hours")
        if (measure == Measure.RAIN) HeadCell("Your reports")
    }
    weights.forEach { w ->
        val sc = scores[w.source]
        val m = metric(sc, measure)
        val sel = w.source == picked
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .clickable { onPick(w.source) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NameCell(Sources.label(w.source), bold = sel)
            WeightCell(weightOf(w, measure))
            HeatCell(fmt(m, measure), m?.let { goodness(it, measure) }, Modifier.weight(1.1f), thin = hoursOf(sc, measure) < THIN_HOURS)
            Text(if (measure == Measure.RAIN) "${sc?.wetHours ?: 0}" else "${hoursOf(sc, measure)}", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            if (measure == Measure.RAIN) Text(sc?.takeIf { it.reportsTotal > 0 }?.let { "${it.reportsRight}/${it.reportsTotal}" } ?: "–",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
    HeatLegend()
    val hours = scores.values.maxOfOrNull { hoursOf(it, measure) } ?: 0
    Note("Up to $hours hours checked${if (lead == null) " over the last 60 days" else " in this window"}. " +
        if (lead == null) "Overall weights cover forecasts made 1 to 30 hours ahead; a window leans on them until it has its own record." else "")

    SubHeading("Share of blend, every window")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        NameCell("Source", bold = true)
        Lead.entries.forEach { l -> HeadCell(hoursLabel(l), win.short(l)) }
    }
    val primary = MaterialTheme.colorScheme.primary
    v.weights.sortedByDescending { weightOf(it, measure) }.forEach { pooled ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NameCell(Sources.label(pooled.source))
            Lead.entries.forEach { l ->
                val w = weightOf(v.leadWeights[l]?.firstOrNull { it.source == pooled.source }, measure)
                val a = (0.08 + w * 3).coerceAtMost(1.0).toFloat()
                Box(Modifier.weight(1f).height(26.dp).padding(1.dp).clip(RoundedCornerShape(4.dp)).background(primary.copy(alpha = a)),
                    contentAlignment = Alignment.Center) {
                    Text("${(w * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall,
                        color = if (a > 0.6f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** Each source's score across lead windows as lines, with the picked one highlighted. Up is always better. */
@Composable
private fun ChartSection(v: SourceView, measure: Measure, picked: String?) {
    if (v.leadScores.isEmpty()) return
    Heading("How right, by how far ahead")
    Note("Each line is a source, from 1–2 days ahead on the left to the last 3 hours on the right. Higher is better. " +
        "Tap a source in the detail table to highlight it.")
    val leads = Lead.entries.reversed()
    val series = v.weights.map { it.source }.associateWith { src ->
        leads.map { l -> v.leadScores[l]?.firstOrNull { it.source == src }?.let { sc -> metric(sc, measure)?.takeIf { hoursOf(sc, measure) > 0 } } }
    }
    val all = series.values.flatten().filterNotNull()
    if (all.isEmpty()) { Note("Lines appear once forecasts at each lead have been checked."); return }
    val better = measure == Measure.RAIN
    val lo = all.min(); val hi = all.max()
    val pad = ((hi - lo) * 0.1).coerceAtLeast(if (better) 0.02 else 0.2)
    val yMin = lo - pad; val yMax = hi + pad
    val grid = MaterialTheme.colorScheme.outlineVariant
    val others = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val main = MaterialTheme.colorScheme.primary
    val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = label; textSize = 10.dp.toPx() }
        val left = 40.dp.toPx(); val right = size.width - 14.dp.toPx(); val top = 8.dp.toPx(); val bottom = size.height - 30.dp.toPx()
        fun x(i: Int) = left + (right - left) * i / (leads.size - 1)
        // Errors are drawn upside down so a smaller miss sits higher.
        fun y(v: Double): Float { val f = ((v - yMin) / (yMax - yMin)).toFloat(); return if (better) bottom - (bottom - top) * f else top + (bottom - top) * f }
        for (k in 0..3) {
            val value = yMin + (yMax - yMin) * k / 3
            val yy = y(value)
            drawLine(grid, Offset(left, yy), Offset(right, yy), 1f)
            text.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(fmt(value, measure), left - 4.dp.toPx(), yy + 3.dp.toPx(), text)
        }
        text.textAlign = android.graphics.Paint.Align.CENTER
        leads.forEachIndexed { i, l -> drawContext.canvas.nativeCanvas.drawText(hoursLabel(l), x(i), bottom + 16.dp.toPx(), text) }
        fun line(points: List<Double?>, color: Color, width: Float, dots: Boolean) {
            val pts = points.mapIndexedNotNull { i, p -> p?.let { Offset(x(i), y(it)) } }
            pts.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, width) }
            if (dots) pts.forEach { drawCircle(color, 4.dp.toPx(), it) }
        }
        series.filterKeys { it != picked }.values.forEach { line(it, others, 1.5.dp.toPx(), false) }
        series[picked]?.let { line(it, main, 3.dp.toPx(), true) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(18.dp).height(3.dp).background(main))
        Text(picked?.let { Sources.label(it) } ?: "", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(18.dp).height(2.dp).background(others))
        Text("other sources", style = MaterialTheme.typography.labelSmall)
    }
}

/** How far ahead each source usually gets it right and sticks with it, as bars on a 2-days-to-now axis, plus the shares. */
@Composable
private fun SettleSection(settled: List<Settled>, measure: Measure) {
    Heading("When forecasts settle")
    Note(
        "For each hour of the last week: how far ahead every source's forecast became right and then stayed right through to the hour " +
            "(rain or dry; temperature within ${app.weathertrack.core.sources.Drift.TEMP_OK.roundToInt()}°; wind within " +
            "${app.weathertrack.core.sources.Drift.WIND_OK.roundToInt()} km/h; cloud within ${app.weathertrack.core.sources.Drift.CLOUD_OK.roundToInt()} points). " +
            "Longer bar: you can trust it earlier.",
    )
    val rows = settled.filter { it.variable == measure.variable }.sortedByDescending { it.medianLeadH }
    if (rows.isEmpty()) { Note("Needs a few days of hourly runs. Open Sources now and then, or let the app refresh in the background."); return }
    val label = MaterialTheme.colorScheme.onSurface.toArgb()
    val sub = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height((rows.size * 20 + 26).dp)) {
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = label; textSize = 11.dp.toPx() }
        val left = 118.dp.toPx(); val right = size.width - 10.dp.toPx(); val rowsBottom = rows.size * 20.dp.toPx()
        fun x(h: Double) = right - (right - left) * (h.coerceIn(0.0, 48.0) / 48.0).toFloat()
        listOf(48.0 to "2 days", 24.0 to "day before", 12.0 to "12 h", 0.0 to "now").forEach { (h, name) ->
            drawLine(grid, Offset(x(h), 0f), Offset(x(h), rowsBottom), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            text.color = sub; text.textAlign = android.graphics.Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(name, x(h).coerceIn(left + 14.dp.toPx(), right - 12.dp.toPx()), rowsBottom + 16.dp.toPx(), text)
        }
        rows.forEachIndexed { i, r ->
            val top = i * 20.dp.toPx() + 3.dp.toPx(); val h = 14.dp.toPx()
            text.color = label; text.textAlign = android.graphics.Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText(Sources.label(r.source).take(18), 0f, top + h - 3.dp.toPx(), text)
            val x0 = x(r.medianLeadH)
            drawRoundRect(heat(r.medianLeadH / 24.0), Offset(x0, top), Size(right - x0, h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            text.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText("${r.medianLeadH.roundToInt()} h", x0 - 3.dp.toPx(), top + h - 3.dp.toPx(), text)
        }
    }
    SubHeading("Share of hours already settled")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        NameCell("Source", bold = true)
        HeadCell("Typical"); HeadCell("24 h out"); HeadCell("12 h out"); HeadCell("6 h out"); HeadCell("3 h out")
    }
    rows.forEach { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NameCell(Sources.label(r.source))
            Text("${r.medianLeadH.roundToInt()} h", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            listOf(r.by24, r.by12, r.by6, r.by3).forEach { share ->
                HeatCell("${(share * 100).roundToInt()}%", share, Modifier.weight(1f), thin = r.hours < THIN_HOURS)
            }
        }
    }
    HeatLegend("Few", "Most")
}

private enum class PastView(val label: String) { HOURS("Hour by hour"), DAYS("Day by day"), WEEKS("By week") }

/** Past forecasts: the last 7 days hour by hour or day by day, and earlier weeks summed. */
@Composable
private fun PastSection(v: SourceView, zone: java.time.ZoneId, measure: Measure) {
    var view by remember { mutableStateOf(PastView.DAYS) }
    Heading("Past forecasts")
    Note(
        "What each source said (its latest forecast made at least an hour ahead) against what happened. " +
            "The last 7 days show every hour; earlier days are kept as daily totals and shown by week.",
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PastView.entries.forEach { FilterChip(selected = view == it, onClick = { view = it }, label = { Text(it.label) }) }
    }
    val order = v.weights.map { it.source }
    when (view) {
        PastView.HOURS -> PastHours(v.past, order, zone, measure)
        PastView.DAYS -> PeriodTable(v.days, order, measure, empty = "Days show here once a forecast hour has passed and been checked.") {
            it.format(java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale.ENGLISH))
        }
        PastView.WEEKS -> PeriodTable(v.weeks, order, measure, empty = "Weeks before the last 7 days build up here over time.") {
            "w/c " + it.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH))
        }
    }
    HeatLegend()
    Note(
        when (measure) {
            Measure.TEMP -> "Average temperature miss in °C. A + means it ran warm, − cold."
            Measure.RAIN -> "Share of hours where rain or no rain was called right."
            Measure.WIND -> "Average wind speed miss in km/h."
            Measure.CLOUD -> "Average cloud cover miss in percentage points."
        },
    )
}

@Composable
private fun PastHours(past: List<PastHour>, order: List<String>, zone: java.time.ZoneId, measure: Measure) {
    val byDay = past.groupBy { java.time.Instant.ofEpochSecond(it.time).atZone(zone).toLocalDate() }
    val dates = byDay.keys.sortedDescending()
    if (dates.isEmpty()) {
        Text("Nothing checked yet. A forecast can only be checked once its hour has passed and the actual weather is in.",
            style = MaterialTheme.typography.bodySmall)
        return
    }
    var i by remember(dates.size) { mutableStateOf(0) }
    val date = dates[i.coerceIn(0, dates.lastIndex)]
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { i++ }, enabled = i < dates.lastIndex) { Text("‹ Earlier") }
        Text(date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)),
            Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { i-- }, enabled = i > 0) { Text("Later ›") }
    }
    val hours = byDay.getValue(date).sortedBy { it.time }
    val shown = order.filter { src -> hours.any { src in it.forecasts } } + hours.flatMap { it.forecasts.keys }.distinct().filter { it !in order }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            Cell("", 120, bold = true)
            Cell("Actual", 120, bold = true)
            shown.forEach { Cell(Sources.label(it), 120) }
        }
        hours.forEach { h ->
            Column {
                Cell(Format.hour(h.time, zone).replace(" ", "").lowercase(), 48, bold = true, center = true)
                when (measure) {
                    Measure.TEMP -> Cell(Format.temp(h.temperature), 48, bold = true, center = true)
                    Measure.RAIN -> Cell(h.precipitation?.let { if (it >= Sources.WET_MM) String.format(java.util.Locale.US, "%.1f", it) else "dry" } ?: "–", 48, bold = true, center = true)
                    Measure.WIND -> Cell(h.windSpeed?.roundToInt()?.toString() ?: "–", 48, bold = true, center = true)
                    Measure.CLOUD -> Cell(h.cloudCover?.let { "${it.roundToInt()}%" } ?: "–", 48, bold = true, center = true)
                }
                shown.forEach { src ->
                    val f = h.forecasts[src]
                    fun miss(a: Double?, b: Double?) = if (a != null && b != null) kotlin.math.abs(a - b) else null
                    val cell = Modifier.width(48.dp)
                    when (measure) {
                        Measure.TEMP -> HeatCell(Format.temp(f?.temperature), miss(f?.temperature, h.temperature)?.let { goodness(it, measure) }, cell)
                        Measure.RAIN -> {
                            val fp = f?.precipitation; val ap = h.precipitation
                            val right = if (fp != null && ap != null) (fp >= Sources.WET_MM) == (ap >= Sources.WET_MM) else null
                            HeatCell(f?.rainChance?.let { "${it.roundToInt()}%" } ?: "–", right?.let { if (it) 0.85 else 0.05 }, cell)
                        }
                        Measure.WIND -> HeatCell(f?.windSpeed?.roundToInt()?.toString() ?: "–", miss(f?.windSpeed, h.windSpeed)?.let { goodness(it, measure) }, cell)
                        Measure.CLOUD -> HeatCell(f?.cloudCover?.let { "${it.roundToInt()}%" } ?: "–", miss(f?.cloudCover, h.cloudCover)?.let { goodness(it, measure) }, cell)
                    }
                }
            }
        }
    }
    Note(when (measure) {
        Measure.TEMP -> "Each source's forecast temperature, coloured by how far off it was."
        Measure.RAIN -> "Actual row: rain in mm or dry. Source rows: their rain chance, green when rain or no rain was right, red when wrong."
        Measure.WIND -> "Actual row: wind speed in km/h. Source rows: their forecast, coloured by how far off it was."
        Measure.CLOUD -> "Actual row: cloud cover. Source rows: their forecast, coloured by how far off it was."
    })
}

@Composable
private fun PeriodTable(rows: List<DayScore>, order: List<String>, measure: Measure, empty: String, label: (java.time.LocalDate) -> String) {
    if (rows.isEmpty()) { Text(empty, style = MaterialTheme.typography.bodySmall); return }
    val periods = rows.map { it.date }.distinct().sortedDescending()
    val bySource = rows.groupBy { it.source }.mapValues { (_, v) -> v.associateBy { it.date } }
    val shown = order.filter { it in bySource } + bySource.keys.filter { it !in order }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            Cell("", 120, bold = true)
            shown.forEach { Cell(Sources.label(it), 120) }
        }
        periods.forEach { d ->
            Column {
                Cell(label(d), 64, bold = true, center = true)
                shown.forEach { src ->
                    val sc = bySource[src]?.get(d)
                    val cell = Modifier.width(64.dp)
                    when (measure) {
                        Measure.TEMP -> {
                            val bias = sc?.tempBias
                            val text = sc?.tempMae?.let { mae ->
                                val sign = bias?.let { if (it > 0.3) "+" else if (it < -0.3) "−" else "" } ?: ""
                                String.format(java.util.Locale.US, "%s%.1f°", sign, mae)
                            } ?: "–"
                            HeatCell(text, sc?.tempMae?.let { goodness(it, measure) }, cell)
                        }
                        Measure.RAIN -> HeatCell(sc?.wetDryRight?.let { "${(it * 100).roundToInt()}%" } ?: "–", sc?.wetDryRight?.let { goodness(it, measure) }, cell)
                        Measure.WIND -> HeatCell(sc?.windMae?.let { "±${it.roundToInt()}" } ?: "–", sc?.windMae?.let { goodness(it, measure) }, cell)
                        Measure.CLOUD -> HeatCell(sc?.cloudMae?.let { "±${it.roundToInt()}" } ?: "–", sc?.cloudMae?.let { goodness(it, measure) }, cell)
                    }
                }
            }
        }
    }
}
