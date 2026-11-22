package app.weathertrack.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.weathertrack.core.history.DecadeStats
import app.weathertrack.core.history.PeriodStats
import app.weathertrack.ui.theme.JacketAmber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val longDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)) {
    val s by vm.state.collectAsStateWithLifecycle()
    val monthName = s.month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("History · ${s.place?.name ?: ""}") },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shiftMonth(-1) }) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous month") }
                    Text(
                        "$monthName ${s.month.year}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { vm.shiftMonth(1) }) { Icon(Icons.Filled.KeyboardArrowRight, "Next month") }
                }
            }
            val yoy = s.yearOnYear
            if (yoy != null && yoy.years.isNotEmpty()) {
                item {
                    val span = if (yoy.lastDay >= s.month.lengthOfMonth()) "All of $monthName"
                    else "1–${yoy.lastDay} $monthName"
                    Text("$span, year on year", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                item { YearTable(yoy.years, yoy.normal) }
            } else if (!s.syncing) {
                item { Text("No history for this month yet.") }
            }
            if (s.decades.isNotEmpty()) {
                item {
                    Text("$monthName by decade", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                item {
                    Text(
                        "Average daily low to high, and average total rain for the month. Complete months only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val lo = s.decades.mapNotNull { it.meanMin }.minOrNull() ?: 0.0
                val hi = s.decades.mapNotNull { it.meanMax }.maxOrNull() ?: 1.0
                items(s.decades) { d -> DecadeRow(d, lo, hi) }
            }
            item {
                Text(
                    "Source: Open-Meteo historical weather (ERA5 reanalysis), about 10 km grid. " +
                        "The newest few days arrive with a delay of about a week.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(s: HistoryState) {
    val r = s.range
    val text = when {
        s.syncing && s.waitSeconds != null -> "Open-Meteo asked for a pause. Carrying on in ${s.waitSeconds} s"
        s.syncing && s.progress != null && s.progress.first < s.progress.second -> "Downloading history: part ${s.progress.first + 1} of ${s.progress.second}, newest first"
        s.syncing -> "Downloading history…"
        r == null || r.days == 0 -> "No history stored yet."
        else -> "${"%,d".format(r.days)} days stored, ${fmt(r.first)} to ${fmt(r.last)}"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private fun fmt(iso: String?): String = iso?.let { LocalDate.parse(it).format(longDate) } ?: "–"

@Composable
private fun YearTable(years: List<PeriodStats>, normal: PeriodStats?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            TableRow("Year", "High", "Low", "Rain", "Rain days", header = true)
            HorizontalDivider()
            years.forEachIndexed { i, y ->
                TableRow(y.label, t(y.meanMax), t(y.meanMin), mm(y.totalRain), days(y.rainyDays), bold = i == 0)
            }
            normal?.let {
                HorizontalDivider()
                TableRow("1991–2020", t(it.meanMax), t(it.meanMin), mm(it.totalRain), days(it.rainyDays), muted = true)
            }
        }
    }
}

@Composable
private fun TableRow(
    a: String, b: String, c: String, d: String, e: String,
    header: Boolean = false, bold: Boolean = false, muted: Boolean = false,
) {
    val weight = if (header || bold) FontWeight.SemiBold else FontWeight.Normal
    val color = if (muted || header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(a, Modifier.weight(1.3f), fontWeight = weight, color = color)
        Text(b, Modifier.weight(1f), fontWeight = weight, color = color, textAlign = TextAlign.End)
        Text(c, Modifier.weight(1f), fontWeight = weight, color = color, textAlign = TextAlign.End)
        Text(d, Modifier.weight(1.2f), fontWeight = weight, color = color, textAlign = TextAlign.End)
        Text(e, Modifier.weight(1.2f), fontWeight = weight, color = color, textAlign = TextAlign.End)
    }
}

@Composable
private fun DecadeRow(d: DecadeStats, lo: Double, hi: Double) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${d.decadeStart}s", Modifier.width(52.dp), fontWeight = FontWeight.Medium)
        Canvas(Modifier.weight(1f).height(14.dp)) {
            val span = (hi - lo).takeIf { it > 0 } ?: 1.0
            drawRoundRect(track, size = size, cornerRadius = CornerRadius(7.dp.toPx()))
            val minV = d.meanMin ?: return@Canvas
            val maxV = d.meanMax ?: return@Canvas
            val x0 = ((minV - lo) / span * size.width).toFloat()
            val x1 = ((maxV - lo) / span * size.width).toFloat()
            drawRoundRect(
                bar, topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(4f), size.height),
                cornerRadius = CornerRadius(7.dp.toPx()),
            )
            // Amber tick at the high end makes warming trends easy to spot down the list.
            drawRoundRect(JacketAmber, topLeft = Offset((x1 - 4f).coerceAtLeast(0f), 0f), size = Size(4f, size.height))
        }
        Text("${t(d.meanMin)}–${t(d.meanMax)}", Modifier.width(76.dp), textAlign = TextAlign.End)
        Text(mm(d.meanMonthlyRain), Modifier.width(64.dp), textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun t(v: Double?) = v?.let { String.format(Locale.US, "%.1f°", it) } ?: "–"
private fun mm(v: Double?) = v?.let { String.format(Locale.US, "%.0f mm", it) } ?: "–"
private fun days(v: Double?) = v?.let { if (it % 1.0 == 0.0) "%.0f".format(it) else String.format(Locale.US, "%.1f", it) } ?: "–"
