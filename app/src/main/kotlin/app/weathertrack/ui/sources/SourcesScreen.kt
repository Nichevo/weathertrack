package app.weathertrack.ui.sources

import androidx.compose.foundation.background
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
import kotlin.math.roundToInt

data class SourcesState(val place: PlaceEntity? = null, val view: SourceView? = null, val loading: Boolean = false)

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
                val view = runCatching { sources.view(place.id, Format.zone(place.timezone), check.observed(place.id, from), check.reports(place.id, from)) }.getOrNull()
                if (mine == loads) _state.update { it.copy(view = view) }
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

/** What the side-by-side grid and the past tables show. */
private enum class Measure(val label: String) { RAIN("Rain chance"), TEMP("Temperature"), WIND("Wind"), CLOUD("Cloud") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(vm: SourcesViewModel = viewModel(factory = SourcesViewModel.Factory)) {
    val s by vm.state.collectAsStateWithLifecycle()
    var measure by remember { mutableStateOf(Measure.RAIN) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sources" + (s.place?.let { " · ${it.name}" } ?: "")) },
            actions = { IconButton(onClick = { vm.load(force = true) }) { Icon(Icons.Filled.Refresh, "Refresh") } },
        )
        if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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
            Text("Next 24 hours, side by side", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Measure.entries.forEach { m -> FilterChip(selected = measure == m, onClick = { measure = m }, label = { Text(m.label) }) }
            }
            Grid(v, zone, measure)
            if (measure == Measure.WIND) Text("Wind speed in km/h.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (measure == Measure.CLOUD) Text("Total cloud cover in %. Darker is cloudier.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Single-run models only say rain or no rain, so their chance is shown as about 70–90% when they rain and 5% when dry. " +
                    "Ensembles give a real chance: the share of their runs with rain.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("How each has done here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp))
            Text(
                "Overall: forecasts made 1 to 30 hours ahead, checked against what happened at your spot (Open-Meteo's analysis) " +
                    "and against your Raining/Dry reports. Rain and temperature are blended separately. Temperature weights settle " +
                    "after a couple of days; rain weights stay near equal until about a dozen wet hours or reports, and judge wet and dry hours equally.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val scores = v.scores.associateBy { it.source }
            ScoreRow("Source", "Rain wt", "Wet hrs", "Wet/dry", "Temp wt", "Temp ±", "Reports", header = true)
            v.weights.forEach { w ->
                val sc = scores[w.source]
                ScoreRow(
                    Sources.label(w.source), "${(w.rain * 100).roundToInt()}%", "${sc?.wetHours ?: 0}",
                    sc?.wetDryRight?.let { "${(it * 100).roundToInt()}%" } ?: "–",
                    "${(w.temp * 100).roundToInt()}%",
                    sc?.tempMae?.let { String.format(java.util.Locale.US, "%.1f°", it) } ?: "–",
                    sc?.takeIf { it.reportsTotal > 0 }?.let { "${it.reportsRight}/${it.reportsTotal}" } ?: "–",
                )
            }
            Text("Wind and cloud", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp))
            Text(
                "Weighted on their own records too: wind speed miss in km/h and cloud cover miss in percentage points, " +
                    "settling after a couple of days of hours.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ScoreRow("Source", "Wind wt", "Wind ±", "Hours", "Cloud wt", "Cloud ±", "Hours", header = true)
            v.weights.sortedByDescending { it.wind }.forEach { w ->
                val sc = scores[w.source]
                ScoreRow(
                    Sources.label(w.source), "${(w.wind * 100).roundToInt()}%",
                    sc?.windMae?.let { "${it.roundToInt()}" } ?: "–", "${sc?.windHours ?: 0}",
                    "${(w.cloud * 100).roundToInt()}%",
                    sc?.cloudMae?.let { "${it.roundToInt()}%" } ?: "–", "${sc?.cloudHours ?: 0}",
                )
            }
            LeadSection(v)
            PastSection(v, zone)

            Text(
                "Rain wt and Temp wt are each source's share of the blend. Sources: Open-Meteo (ECMWF, NOAA GFS, DWD ICON, UK Met Office, GEM, JMA, Météo-France, CMA, " +
                    "and the ECMWF, GFS and ICON ensembles) and MET Norway (CC BY 4.0). MetService has no free forecast feed, so it isn't included.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Grid(v: SourceView, zone: java.time.ZoneId, measure: Measure) {
    val now = System.currentTimeMillis() / 1000
    val times = v.blend.map { it.time }.filter { it > now }.filterIndexed { i, _ -> i % 3 == 0 }.take(8)
    val bySource = v.latest.groupBy { it.source }.mapValues { (_, h) -> h.associateBy { it.time } }
    val order = v.weights.map { it.source }.filter { it in bySource }
    val blend = v.blend.associateBy { it.time }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            Cell("", 120, bold = true)
            Cell("Blend", 120, bold = true)
            order.forEach { Cell(Sources.label(it), 120) }
        }
        times.forEach { t ->
            Column {
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

/** Each source's record and blend weights for one lead group at a time. */
@Composable
private fun LeadSection(v: SourceView) {
    if (v.leadWeights.isEmpty()) return
    var lead by remember { mutableStateOf(Lead.SOON) }
    Text("By how far ahead", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp))
    Text(
        "A source can be good a day out and poor in the last few hours, or the other way round, so each lead has its own " +
            "record. The blend uses these for each hour by how far ahead it is, leaning on the overall weights until a lead has a record.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Lead.entries.forEach { l -> FilterChip(selected = lead == l, onClick = { lead = l }, label = { Text(l.label) }) }
    }
    val scores = v.leadScores[lead].orEmpty().associateBy { it.source }
    ScoreRow("Source", "Rain wt", "Wet/dry", "Temp wt", "Temp ±", "Wind ±", "Cloud ±", header = true)
    v.leadWeights[lead].orEmpty().forEach { w ->
        val sc = scores[w.source]
        ScoreRow(
            Sources.label(w.source), "${(w.rain * 100).roundToInt()}%",
            sc?.wetDryRight?.let { "${(it * 100).roundToInt()}%" } ?: "–",
            "${(w.temp * 100).roundToInt()}%",
            sc?.tempMae?.let { String.format(java.util.Locale.US, "%.1f°", it) } ?: "–",
            sc?.windMae?.let { "${it.roundToInt()}" } ?: "–",
            sc?.cloudMae?.let { "${it.roundToInt()}%" } ?: "–",
        )
    }
    val hours = scores.values.maxOfOrNull { it.hours } ?: 0
    Text("Up to $hours hours checked at this lead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ScoreRow(a: String, b: String, c: String, d: String, e: String, f: String, g: String, header: Boolean = false) {
    val w = if (header) FontWeight.SemiBold else FontWeight.Normal
    Row(Modifier.fillMaxWidth()) {
        Text(a, Modifier.weight(2.2f), style = MaterialTheme.typography.bodySmall, fontWeight = w, maxLines = 1)
        listOf(b, c, d, e, f, g).forEach {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = w, textAlign = TextAlign.End, maxLines = 1)
        }
    }
}

private enum class PastView(val label: String) { HOURS("Hour by hour"), DAYS("Day by day"), WEEKS("By week") }

private val Good = Color(0xFF2E7D32)
private val Fair = Color(0xFFF9A825)
private val Poor = Color(0xFFC62828)

/** Shading for a temperature miss: green within 1°, amber within 2.5°, red beyond. */
private fun tempShade(miss: Double?): Color = when {
    miss == null -> Color.Transparent
    miss <= 1.0 -> Good.copy(alpha = 0.22f)
    miss <= 2.5 -> Fair.copy(alpha = 0.30f)
    else -> Poor.copy(alpha = 0.28f)
}

/** Shading for a wind miss: green within 5 km/h, amber within 10, red beyond. */
private fun windShade(miss: Double?): Color = when {
    miss == null -> Color.Transparent
    miss <= 5.0 -> Good.copy(alpha = 0.22f)
    miss <= 10.0 -> Fair.copy(alpha = 0.30f)
    else -> Poor.copy(alpha = 0.28f)
}

/** Shading for a cloud cover miss: green within 15 points, amber within 30, red beyond. */
private fun cloudShade(miss: Double?): Color = when {
    miss == null -> Color.Transparent
    miss <= 15.0 -> Good.copy(alpha = 0.22f)
    miss <= 30.0 -> Fair.copy(alpha = 0.30f)
    else -> Poor.copy(alpha = 0.28f)
}

/** Shading for a share of rain calls right. */
private fun rainShade(right: Double?): Color = when {
    right == null -> Color.Transparent
    right >= 0.85 -> Good.copy(alpha = 0.22f)
    right >= 0.65 -> Fair.copy(alpha = 0.30f)
    else -> Poor.copy(alpha = 0.28f)
}

/** Past forecasts: the last 7 days hour by hour or day by day, and earlier weeks summed. */
@Composable
private fun PastSection(v: SourceView, zone: java.time.ZoneId) {
    var view by remember { mutableStateOf(PastView.HOURS) }
    var measure by remember { mutableStateOf(Measure.TEMP) }
    Text("Past forecasts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp))
    Text(
        "What each source said (its latest forecast made at least an hour ahead) against what happened. " +
            "The last 7 days show every hour; earlier days are kept as daily totals and shown by week.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PastView.entries.forEach { FilterChip(selected = view == it, onClick = { view = it }, label = { Text(it.label) }) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Measure.TEMP, Measure.RAIN, Measure.WIND, Measure.CLOUD).forEach { m ->
            FilterChip(selected = measure == m, onClick = { measure = m }, label = { Text(if (m == Measure.RAIN) "Rain" else m.label) })
        }
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
    Text(
        when (measure) {
            Measure.TEMP -> "Green: within 1° on average; amber: within 2.5°; red: further out. A + means it ran warm, − cold."
            Measure.RAIN -> "Cells show the share of hours where rain or no rain was called right (green 85%+, red under 65%)."
            Measure.WIND -> "Wind speed miss in km/h: green within 5, amber within 10, red further out."
            Measure.CLOUD -> "Cloud cover miss in percentage points: green within 15, amber within 30, red further out."
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    when (measure) {
                        Measure.TEMP -> ShadedCell(Format.temp(f?.temperature), tempShade(miss(f?.temperature, h.temperature)))
                        Measure.RAIN -> {
                            val fp = f?.precipitation; val ap = h.precipitation
                            val right = if (fp != null && ap != null) (fp >= Sources.WET_MM) == (ap >= Sources.WET_MM) else null
                            ShadedCell(f?.rainChance?.let { "${it.roundToInt()}%" } ?: "–", rainShade(right?.let { if (it) 1.0 else 0.0 }))
                        }
                        Measure.WIND -> ShadedCell(f?.windSpeed?.roundToInt()?.toString() ?: "–", windShade(miss(f?.windSpeed, h.windSpeed)))
                        Measure.CLOUD -> ShadedCell(f?.cloudCover?.let { "${it.roundToInt()}%" } ?: "–", cloudShade(miss(f?.cloudCover, h.cloudCover)))
                    }
                }
            }
        }
    }
    Text(when (measure) {
            Measure.TEMP -> "Each source's forecast temperature, shaded by how far off it was."
            Measure.RAIN -> "Actual row: rain in mm or dry. Source rows: their rain chance, green when rain or no rain was right."
            Measure.WIND -> "Actual row: wind speed in km/h. Source rows: their forecast, shaded by how far off it was."
            Measure.CLOUD -> "Actual row: cloud cover. Source rows: their forecast, shaded by how far off it was."
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    when (measure) {
                        Measure.TEMP -> {
                            val bias = sc?.tempBias
                            val text = sc?.tempMae?.let { mae ->
                                val sign = bias?.let { if (it > 0.3) "+" else if (it < -0.3) "−" else "" } ?: ""
                                String.format(java.util.Locale.US, "%s%.1f°", sign, mae)
                            } ?: "–"
                            ShadedCell(text, tempShade(sc?.tempMae), 64)
                        }
                        Measure.RAIN -> ShadedCell(sc?.wetDryRight?.let { "${(it * 100).roundToInt()}%" } ?: "–", rainShade(sc?.wetDryRight), 64)
                        Measure.WIND -> ShadedCell(sc?.windMae?.let { "±${it.roundToInt()}" } ?: "–", windShade(sc?.windMae), 64)
                        Measure.CLOUD -> ShadedCell(sc?.cloudMae?.let { "±${it.roundToInt()}" } ?: "–", cloudShade(sc?.cloudMae), 64)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShadedCell(text: String, bg: Color, width: Int = 48) {
    Box(Modifier.width(width.dp).height(30.dp).padding(1.dp).background(bg), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}
