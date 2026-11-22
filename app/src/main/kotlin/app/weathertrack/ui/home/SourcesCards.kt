package app.weathertrack.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.weathertrack.core.model.Forecast
import app.weathertrack.core.sources.Sources
import app.weathertrack.data.SourceView
import app.weathertrack.ui.Format
import app.weathertrack.ui.accuracy.RainChanceChart
import app.weathertrack.ui.accuracy.TempFanChart
import java.time.ZoneId
import kotlin.math.roundToInt

/** Rain in the next 6 hours: the accuracy-weighted blend of every source, and how far apart they are. */
@Composable
fun SourcesCard(v: SourceView, zone: ZoneId) {
    val now = System.currentTimeMillis() / 1000
    val next = v.blend.filter { it.time > now && it.time <= now + 6 * 3600 }
    if (next.isEmpty()) return
    val peak = next.maxByOrNull { it.rainChance ?: 0.0 } ?: return
    // Each source's highest chance over the same hours.
    val bySource = v.latest.filter { it.time > now && it.time <= now + 6 * 3600 }.groupBy { it.source }
        .mapValues { (_, h) -> h.maxOf { it.rainChance ?: 0.0 } }.entries.sortedBy { it.value }
    val main = bySource.firstOrNull { it.key == Sources.MAIN.id }?.value
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("All sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Rain in the next 6 hours: blend ${Format.pct(peak.rainChance)} around ${Format.time(peak.time, zone)}",
                fontWeight = FontWeight.Medium)
            Text("${bySource.size} sources range from ${Format.pct(bySource.firstOrNull()?.value)} to ${Format.pct(bySource.lastOrNull()?.value)}" +
                (main?.let { ". Main forecast says ${Format.pct(it)}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            val top = v.weights.take(3).joinToString(", ") { "${Sources.label(it.source)} ${(it.rain * 100).roundToInt()}%" }
            val scored = v.scores.maxOfOrNull { it.hours } ?: 0
            val wet = v.scores.maxOfOrNull { it.wetHours + it.reportsTotal } ?: 0
            Text(
                when {
                    scored < 24 -> "Still learning which source is best here: weights are nearly equal for now."
                    wet < 6 -> "Not much rain to learn from yet ($wet wet hours), so rain weights stay close to equal."
                    else -> "Weighted most for rain: $top (from $wet wet hours and $scored hours checked here)."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("See the Sources tab for each one side by side.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val LowLine = Color(0xFF3F7FBF)

/** The next-day check on Today: likely ranges for highs and lows, and the rain chance per day. */
@Composable
fun NextDaysCheckCard(state: HomeState, f: Forecast?) {
    f ?: return
    val spread = state.spread.associateBy { it.date }
    val chances = f.daily.take(7).map { d ->
        val name = runCatching { java.time.LocalDate.parse(d.date).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) }.getOrDefault("")
        val models = spread[d.date]?.takeIf { it.models > 0 }?.let { 100.0 * it.wetModels / it.models }
        Triple(name, d.precipitationProbabilityMax, models)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Next days: likely range", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Bands show where 8 in 10 past forecasts at the same lead landed here. Wider means less sure.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.highFan.isNotEmpty()) {
                Text("Daily high", style = MaterialTheme.typography.labelLarge)
                TempFanChart(state.highFan)
            }
            if (state.lowFan.isNotEmpty()) {
                Text("Daily low", style = MaterialTheme.typography.labelLarge)
                TempFanChart(state.lowFan, LowLine)
            }
            if (state.highFan.isEmpty() && state.lowFan.isEmpty()) {
                Text("Ranges appear once Forecast check has a few weeks of history.", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Chance of rain", style = MaterialTheme.typography.labelLarge)
            RainChanceChart(chances)
            Text("Bars: the forecast's chance of rain. Line: share of weather models with 1 mm or more.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
