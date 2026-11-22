package app.weathertrack.ui.climate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.weathertrack.core.climate.ClimateSummary
import app.weathertrack.core.climate.EnergyFit
import app.weathertrack.core.climate.EnergyMonth
import app.weathertrack.core.climate.HomeClimate
import app.weathertrack.core.climate.PeriodRow
import app.weathertrack.ui.theme.JacketAmber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val HeatColor = JacketAmber
private val CoolColor = Color(0xFF3B7DD8)
private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val longDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/** The Home tab: how the weather drives heating and cooling at home, over time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClimateScreen(onBack: (() -> Unit)? = null, vm: ClimateViewModel = viewModel(factory = ClimateViewModel.Factory)) {
    val s by vm.state.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importEnergy)
    }
    val monthName = s.month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Home · ${s.place?.name ?: ""}") },
            navigationIcon = { onBack?.let { b -> IconButton(onClick = b) { Icon(Icons.Filled.ArrowBack, "Back") } } },
            actions = {
                IconButton(onClick = vm::sync, enabled = !s.syncing) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Download new days")
                }
            },
        )
        if (s.syncing) {
            val p = s.progress
            if (p != null && p.second > 0) {
                LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusLine(s) }
            item {
                Text(
                    "Heating degree days (heat DD) add up how far each day's mean temperature falls below " +
                        "${s.bases.heating.roundToInt()}°; cooling degree days (cool DD) how far it rises above " +
                        "${s.bases.cooling.roundToInt()}°. Double the degree days, roughly double the heating or cooling. " +
                        "Change the bases in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // This month so far, against the same days in past years.
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shiftMonth(-1) }) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous month") }
                    Text("$monthName ${s.month.year}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    IconButton(onClick = { vm.shiftMonth(1) }) { Icon(Icons.Filled.KeyboardArrowRight, "Next month") }
                }
            }
            val mtd = s.monthToDate
            if (mtd != null) {
                item {
                    val span = if (mtd.end == s.month.atEndOfMonth()) "All of $monthName" else "1–${mtd.end.dayOfMonth} $monthName"
                    Title("$span, year on year")
                }
                item { Headline(mtd.years.first(), mtd.normal) }
                item { YearTable(mtd.years, mtd.normal) }
                item { HumidityLine(mtd.years.first(), mtd.normal) }
            } else if (!s.syncing) {
                item { Text("No climate data for this month yet.") }
            }

            if (s.weeks.isNotEmpty()) {
                item { Title("Last 8 weeks") }
                item { PeriodTable(s.weeks) { "${it.start.format(shortDate)}–${it.end.format(shortDate)}" } }
            }
            if (s.months.isNotEmpty()) {
                item { Title("Last 12 months") }
                item { PeriodTable(s.months) { it.start.format(monthYear) } }
            }
            if (s.years.isNotEmpty()) {
                item { Title("Heating and cooling need by year") }
                item {
                    Note("Total degree days in each full year. Shorter amber bars mean milder winters; longer blue bars, hotter summers.")
                }
                val maxH = s.years.mapNotNull { it.hdd }.maxOrNull() ?: 1.0
                val maxC = s.years.mapNotNull { it.cdd }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
                items(s.years) { y -> YearBar(y, maxH, maxC) }
            }

            item { Title("Your power use") }
            item {
                EnergyCard(
                    s, onImport = { importLauncher.launch(arrayOf("*/*")) }, onClear = vm::clearEnergy,
                )
            }
            item {
                Note(
                    "Source: Open-Meteo historical weather (ERA5 reanalysis), about 10 km grid, from 1991. " +
                        "Averages are 1991–2020 over the same dates. The newest days arrive about a week late.",
                )
            }
        }
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun StatusLine(s: ClimateState) {
    val r = s.range
    val text = when {
        s.syncing && s.waitSeconds != null -> "Open-Meteo asked for a pause. Carrying on in ${s.waitSeconds} s"
        s.syncing && s.progress != null && s.progress.first < s.progress.second -> "Downloading climate data: part ${s.progress.first + 1} of ${s.progress.second}, newest first"
        s.syncing -> "Downloading climate data…"
        r == null || r.days == 0 -> "No climate data stored yet."
        else -> "${"%,d".format(r.days)} days stored, ${fmt(r.first)} to ${fmt(r.last)}"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private fun fmt(iso: String?): String = iso?.let { LocalDate.parse(it).format(longDate) } ?: "–"

/** "Heating need so far is 12% below average." */
@Composable
private fun Headline(now: ClimateSummary, normal: ClimateSummary?) {
    val parts = mutableListOf<String>()
    val h = now.hdd
    val nh = normal?.hdd
    if (h != null && nh != null && nh >= 5) parts += "Heating need is ${vsText(h, nh)} the 1991–2020 average"
    val c = now.cdd
    val nc = normal?.cdd
    if (c != null && nc != null && (c >= 3 || nc >= 3)) parts += "cooling need is ${if (nc >= 1) vsText(c, nc) else "${dd(c)} DD against ${dd(nc)}"}"
    if (parts.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Text(parts.joinToString(", ") + ".", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun vsText(v: Double, normal: Double): String {
    val pct = ((v - normal) / normal * 100).roundToInt()
    return when {
        abs(pct) < 5 -> "close to"
        pct > 0 -> "$pct% above"
        else -> "${-pct}% below"
    }
}

private fun pctVs(v: Double?, normal: Double?): String {
    if (v == null || normal == null || normal < 1) return "–"
    val pct = ((v - normal) / normal * 100).roundToInt()
    return if (pct > 0) "+$pct%" else "$pct%"
}

@Composable
private fun YearTable(years: List<ClimateSummary>, normal: ClimateSummary?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Cells("Year", "Heat DD", "Cool DD", "Sun h/d", "Humidity", header = true)
            HorizontalDivider()
            years.forEachIndexed { i, y ->
                Cells(y.label, dd(y.hdd), dd(y.cdd), hrs(y.sunshineHoursPerDay), pct(y.meanHumidity), bold = i == 0)
            }
            normal?.let {
                HorizontalDivider()
                Cells("1991–2020", dd(it.hdd), dd(it.cdd), hrs(it.sunshineHoursPerDay), pct(it.meanHumidity), muted = true)
            }
        }
    }
}

@Composable
private fun HumidityLine(now: ClimateSummary, normal: ClimateSummary?) {
    val lines = mutableListOf<String>()
    now.solarKwhPerDay?.let { lines += "Sun energy ${kwh(it)}/m² a day" + (normal?.solarKwhPerDay?.let { n -> " (average ${kwh(n)})" } ?: "") }
    now.meanDewPoint?.let { lines += "dew point ${one(it)}°" + (normal?.meanDewPoint?.let { n -> " (average ${one(n)}°)" } ?: "") }
    now.muggyDays?.let { lines += "muggy days ${days(it)}" + (normal?.muggyDays?.let { n -> " (average ${days(n)})" } ?: "") }
    now.dampColdDays?.let { lines += "damp cold days ${days(it)}" + (normal?.dampColdDays?.let { n -> " (average ${days(n)})" } ?: "") }
    if (lines.isEmpty()) return
    Note(
        lines.joinToString(", ").replaceFirstChar { it.uppercase() } + ". Muggy means a mean dew point of " +
            "${HomeClimate.MUGGY_DEW_POINT.roundToInt()}° or more; damp cold means humidity of ${HomeClimate.DAMP_HUMIDITY.roundToInt()}% " +
            "or more on a day under ${HomeClimate.DAMP_COLD_MAX_TEMP.roundToInt()}°.",
    )
}

@Composable
private fun PeriodTable(rows: List<PeriodRow>, label: (PeriodRow) -> String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Cells("", "Heat DD", "vs avg", "Cool DD", "Humidity", header = true)
            HorizontalDivider()
            rows.forEachIndexed { i, r ->
                Cells(label(r), dd(r.summary.hdd), pctVs(r.summary.hdd, r.normal?.hdd), dd(r.summary.cdd), pct(r.summary.meanHumidity), bold = i == 0)
            }
        }
    }
}

@Composable
private fun Cells(
    a: String, b: String, c: String, d: String, e: String,
    header: Boolean = false, bold: Boolean = false, muted: Boolean = false,
) {
    val weight = if (header || bold) FontWeight.SemiBold else FontWeight.Normal
    val color = if (muted || header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(a, Modifier.weight(1.6f), fontWeight = weight, color = color, style = MaterialTheme.typography.bodyMedium)
        Text(b, Modifier.weight(1f), fontWeight = weight, color = color, textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
        Text(c, Modifier.weight(1f), fontWeight = weight, color = color, textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
        Text(d, Modifier.weight(1f), fontWeight = weight, color = color, textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
        Text(e, Modifier.weight(1.1f), fontWeight = weight, color = color, textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun YearBar(y: ClimateSummary, maxH: Double, maxC: Double) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(y.label, Modifier.width(44.dp), fontWeight = FontWeight.Medium)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Bar((y.hdd ?: 0.0) / maxH, HeatColor, track)
            Bar((y.cdd ?: 0.0) / maxC, CoolColor, track)
        }
        Text(dd(y.hdd), Modifier.width(52.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurface)
        Text(dd(y.cdd), Modifier.width(36.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Bar(fraction: Double, color: Color, track: Color) {
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(track, size = size, cornerRadius = CornerRadius(4.dp.toPx()))
        val w = (fraction.coerceIn(0.0, 1.0) * size.width).toFloat()
        if (w > 0f) drawRoundRect(color, topLeft = Offset.Zero, size = Size(w.coerceAtLeast(3f), size.height), cornerRadius = CornerRadius(4.dp.toPx()))
    }
}

@Composable
private fun EnergyCard(s: ClimateState, onImport: () -> Unit, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = s.energyRange
            if (r == null || r.days == 0) {
                Text(
                    "Import a CSV of your electricity or gas use (most NZ retailers let you download daily or " +
                        "half-hourly usage) to see how much of it follows the weather. It needs a date column " +
                        "(day first, like 30/06/2025, or 2025-06-30) and a kWh column. It stays on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("${"%,d".format(r.days)} days of power use, ${fmt(r.first)} to ${fmt(r.last)}", style = MaterialTheme.typography.bodyMedium)
                FitText(s.energyFit, s.bases.heating, s.bases.cooling)
                if (s.energyMonths.isNotEmpty()) EnergyTable(s.energyMonths.take(12))
            }
            s.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport) { Text(if (r == null || r.days == 0) "Import CSV" else "Import more") }
                if (r != null && r.days > 0) TextButton(onClick = onClear) { Text("Remove all") }
            }
        }
    }
}

@Composable
private fun FitText(fit: EnergyFit?, heatBase: Double, coolBase: Double) {
    if (fit == null) {
        Note("Needs at least 14 days that have both power use and weather to work out the link.")
        return
    }
    val lines = mutableListOf("On a mild day you use about ${kwh(fit.baseKwhPerDay)}.")
    fit.kwhPerHdd?.let {
        lines += "Each heating degree day adds about ${kwh(it)}, so a day at ${(heatBase - 8).roundToInt()}° " +
            "costs about ${kwh(fit.baseKwhPerDay + it * 8)}."
    }
    fit.kwhPerCdd?.let {
        lines += "Each cooling degree day adds about ${kwh(it)}, so a day at ${(coolBase + 4).roundToInt()}° " +
            "costs about ${kwh(fit.baseKwhPerDay + it * 4)}."
    }
    lines += "The weather explains ${(fit.r2 * 100).roundToInt()}% of the day-to-day change in use (${fit.days} days)."
    Text(lines.joinToString(" "), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun EnergyTable(months: List<EnergyMonth>) {
    Column {
        Cells("Month", "kWh/day", "Days", "Heat DD", "Cool DD", header = true)
        HorizontalDivider()
        months.forEach { m ->
            Cells(m.month.atDay(1).format(monthYear), one(m.kwhPerDay), m.days.toString(), dd(m.hdd), dd(m.cdd))
        }
    }
}

private fun dd(v: Double?) = v?.let { if (it < 10) String.format(Locale.US, "%.1f", it) else "%,d".format(it.roundToInt()) } ?: "–"
private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
private fun hrs(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "–"
private fun pct(v: Double?) = v?.let { "${it.roundToInt()}%" } ?: "–"
private fun kwh(v: Double) = String.format(Locale.US, "%.1f kWh", v)
private fun days(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(v) else String.format(Locale.US, "%.1f", v)
