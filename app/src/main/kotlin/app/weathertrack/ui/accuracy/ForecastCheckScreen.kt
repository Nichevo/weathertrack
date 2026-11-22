package app.weathertrack.ui.accuracy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.weathertrack.core.accuracy.Band
import app.weathertrack.core.accuracy.Confidence
import app.weathertrack.core.accuracy.LeadError
import app.weathertrack.core.accuracy.MissKind
import app.weathertrack.core.accuracy.Reliability
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val missDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForecastCheckScreen(
    onBack: () -> Unit = {},
    vm: ForecastCheckViewModel = viewModel(factory = ForecastCheckViewModel.Factory),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Forecast check · ${s.place?.name ?: ""}") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = vm::sync, enabled = !s.syncing) { Icon(Icons.Filled.Refresh, contentDescription = "Download latest") }
            },
        )
        if (s.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            s.waitSeconds?.let { Text("Open-Meteo asked for a pause. Carrying on in $it s.", color = MaterialTheme.colorScheme.primary) }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckPeriod.entries.forEach { p ->
                    FilterChip(selected = s.period == p, onClick = { vm.setPeriod(p) }, label = { Text(p.label) })
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val j = s.jacket
                StatCard(
                    Modifier.weight(1f), "Jacket calls right",
                    j?.share?.let { "${(it * 100).roundToInt()}%" } ?: "–",
                    when {
                        j == null || j.decided == 0 -> "Builds up from mornings this app stored"
                        else -> "${j.right} of ${j.decided} mornings" + if (j.maybes > 0) ", ${j.maybes} maybe" else ""
                    },
                )
                val r = s.rainCalls
                StatCard(
                    Modifier.weight(1f), "Rain or dry, day ahead",
                    r?.share?.let { "${(it * 100).roundToInt()}%" } ?: "–",
                    if (r == null || r.total == 0) "No days yet" else "${r.right} of ${r.total} days",
                )
                val next = s.leads.firstOrNull { it.lead == 1 }
                StatCard(
                    Modifier.weight(1f), "Next-day high",
                    next?.let { "±${fmt1(it.meanAbs)}°" } ?: "–",
                    next?.let { "typical miss, ${biasText(it.bias)}" } ?: "No days yet",
                )
            }

            if (s.tempFan.isNotEmpty() || s.rainFan.isNotEmpty()) {
                Section("Next days: likely range") {
                    Text(
                        "The current forecast, with the range 8 in 10 past forecasts at the same lead landed in " +
                            "(${s.period.label.lowercase()} of history). Wider means less sure.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.tempFan.isNotEmpty()) {
                        Text("Daily high", style = MaterialTheme.typography.labelLarge)
                        TempFanChart(s.tempFan)
                    }
                    if (s.rainFan.isNotEmpty()) {
                        Text("Rain per day (mm)", style = MaterialTheme.typography.labelLarge)
                        RainFanChart(s.rainFan)
                    }
                }
            } else if (s.actualDays == 0 && !s.syncing) {
                Text("No past forecasts to compare yet. Tap refresh at the top to download them.")
            }

            Section("How far off the daily high was") {
                Text(
                    "Bars show where 8 in 10 days landed (actual minus forecast). The dot is the average. " +
                        "Today's 7-day forecast uses these as its likely range.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.leads.isEmpty()) Text("No days to compare yet.")
                val scale = maxOf(4.0, s.leads.maxOfOrNull { maxOf(abs(it.p10), abs(it.p90)) } ?: 4.0)
                s.leads.forEach { LeadRow(it, s.bands[it.lead], scale) }
                if (s.leads.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.width(84.dp))
                        Text("−${scale.roundToInt()}° colder", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        Text("warmer +${scale.roundToInt()}°", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
                        Box(Modifier.width(72.dp))
                    }
                }
            }

            Section("When it said a chance of rain") {
                Text(
                    "Forecasts this app stored, 1 to 24 hours ahead, against whether that hour was wet. " +
                        "A good forecast rains about as often as it says.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.reliability.all { it.hours == 0 }) Text("Needs a few days of stored forecasts.")
                else s.reliability.forEach { ReliabilityRow(it) }
            }

            if (s.misses.isNotEmpty()) {
                Section("Biggest misses") {
                    s.misses.forEach { m ->
                        Column {
                            Text(
                                "${m.date.format(missDate)} · " + when (m.kind) {
                                    MissKind.WET -> "Wetter than forecast"
                                    MissKind.DRY -> "Drier than forecast"
                                    MissKind.WARM -> "Warmer than forecast"
                                    MissKind.COLD -> "Colder than forecast"
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(m.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Section("Data behind this") {
                Text("${s.actualDays} days of actuals in this period" + (s.firstActual?.let { ", back to ${it.format(missDate)}" } ?: ""))
                Text("${s.storedPulls} forecasts stored by this app for this place")
                Text(
                    "Actuals are Open-Meteo's best estimate of each past hour at this spot, not a rain gauge. " +
                        "Earlier forecasts come from Open-Meteo Previous Runs (same day, 1, 2, 3, 5 and 7 days ahead).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, title: String, value: String, note: String) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

fun leadLabel(lead: Int) = when (lead) {
    0 -> "Same day"
    1 -> "1 day"
    else -> "$lead days"
}

@Composable
private fun LeadRow(e: LeadError, band: Band?, scale: Double) {
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val dotColor = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(84.dp)) {
            Text(leadLabel(e.lead), style = MaterialTheme.typography.bodyMedium)
            Text("${e.days} days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.weight(2f).height(22.dp)) {
            fun x(v: Double) = (size.width / 2 + (v / scale) * size.width / 2).toFloat()
            drawLine(axis, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
            val l = x(e.p10)
            val r = x(e.p90)
            drawRoundRect(bandColor, Offset(l, size.height * 0.25f), Size(maxOf(r - l, 4f), size.height * 0.5f), CornerRadius(8f, 8f))
            drawCircle(dotColor, radius = size.height * 0.22f, center = Offset(x(e.bias), size.height / 2))
        }
        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
            Text("±${fmt1(e.meanAbs)}°", style = MaterialTheme.typography.bodyMedium)
            band?.let { ConfidenceTag(it.confidence) }
        }
    }
}

@Composable
fun ConfidenceTag(c: Confidence) {
    val (bg, fg) = when (c) {
        Confidence.HIGH -> Color(0xFFCDEBD3) to Color(0xFF0F3D1E)
        Confidence.MEDIUM -> Color(0xFFFFE3A3) to Color(0xFF3D2A00)
        Confidence.LOW -> Color(0xFFFFDAD6) to Color(0xFF8C1D18)
        Confidence.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(if (c == Confidence.UNKNOWN) "Few days" else c.label, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ReliabilityRow(r: Reliability) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Said ${r.label}", Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
        Box(
            Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val share = r.observedShare
            if (share != null && share > 0) {
                Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
        Text(
            r.observedShare?.let { "wet ${(it * 100).roundToInt()}%" } ?: "–",
            Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
        )
        Text("${r.hours} h", Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)

private fun biasText(bias: Double) = when {
    bias >= 0.5 -> "runs ${fmt1(bias)}° cool"
    bias <= -0.5 -> "runs ${fmt1(-bias)}° warm"
    else -> "no lean"
}
