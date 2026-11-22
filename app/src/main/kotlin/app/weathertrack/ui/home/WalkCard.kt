package app.weathertrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.weathertrack.core.walk.WalkForecast
import app.weathertrack.core.walk.WalkHour
import app.weathertrack.core.walk.WalkRating
import app.weathertrack.ui.Format
import java.time.ZoneId

fun walkDayLabel(w: WalkForecast, zone: ZoneId, nowEpoch: Long): String {
    val today = java.time.Instant.ofEpochSecond(nowEpoch).atZone(zone).toLocalDate()
    val day = when (w.date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> w.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
    }
    return "${w.window.name}, $day"
}

fun ratingLabel(r: WalkRating) = when (r) {
    WalkRating.GOOD -> "Good"
    WalkRating.FAIR -> "OK, with care"
    WalkRating.POOR -> "Not great"
}

private fun ratingColors(r: WalkRating): Pair<Color, Color> = when (r) {
    WalkRating.GOOD -> Color(0xFFCDEBD3) to Color(0xFF0F3D1E)
    WalkRating.FAIR -> Color(0xFFFFE3A3) to Color(0xFF3D2A00)
    WalkRating.POOR -> Color(0xFFFFDAD6) to Color(0xFF8C1D18)
}

@Composable
fun WalkCard(w: WalkForecast, zone: ZoneId, nowEpoch: Long) {
    var expanded by remember { mutableStateOf(false) }
    val (bg, fg) = ratingColors(w.rating)
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(walkDayLabel(w, zone, nowEpoch), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${Format.time(w.start, zone)} to ${Format.time(w.end, zone)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(ratingLabel(w.rating), color = fg, fontWeight = FontWeight.SemiBold)
                }
            }
            w.issues.forEach { Text("• ${it.text}") }
            w.bestHour?.let { Text("Best time: from ${Format.time(it, zone)}", fontWeight = FontWeight.Medium) }
            Text(
                "Feels ${Format.temp(w.feelsMin)} to ${Format.temp(w.feelsMax)} · rain ${Format.pct(w.rainChanceMax)} · " +
                    "humidity ${Format.pct(w.humidityMin)}–${Format.pct(w.humidityMax)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Cloud: low ${Format.pct(w.cloudLow)}, mid ${Format.pct(w.cloudMid)}, high ${Format.pct(w.cloudHigh)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                HourHeader()
                w.hours.forEach { HourLine(it, zone) }
            } else {
                Text("Tap for hour by hour", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun HourHeader() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth()) {
        listOf("Time" to 1.2f, "Feels" to 1f, "Rain" to 1f, "Hum." to 1f, "Low" to 1f, "Mid" to 1f, "High" to 1f, "UV" to 0.8f)
            .forEach { (t, wgt) ->
                Text(t, Modifier.weight(wgt), style = MaterialTheme.typography.labelSmall, color = c,
                    textAlign = if (t == "Time") TextAlign.Start else TextAlign.End)
            }
    }
}

@Composable
private fun HourLine(h: WalkHour, zone: ZoneId) {
    val p = h.point
    val (_, fg) = ratingColors(h.rating)
    val style = MaterialTheme.typography.bodySmall
    Row(Modifier.fillMaxWidth()) {
        Text(Format.hour(p.time, zone), Modifier.weight(1.2f), style = style, color = if (h.rating == WalkRating.GOOD) Color.Unspecified else fg,
            fontWeight = if (h.rating == WalkRating.GOOD) FontWeight.Normal else FontWeight.SemiBold)
        Text(Format.temp(p.apparentTemperature), Modifier.weight(1f), style = style, textAlign = TextAlign.End)
        Text(Format.pct(p.precipitationProbability), Modifier.weight(1f), style = style, textAlign = TextAlign.End)
        Text(Format.pct(p.humidity), Modifier.weight(1f), style = style, textAlign = TextAlign.End)
        Text(Format.pct(p.cloudCoverLow), Modifier.weight(1f), style = style, textAlign = TextAlign.End)
        Text(Format.pct(p.cloudCoverMid), Modifier.weight(1f), style = style, textAlign = TextAlign.End)
        Text(Format.pct(p.cloudCoverHigh), Modifier.weight(1f), style = style, textAlign = TextAlign.End)
        Text(Format.uv(p.uvIndex), Modifier.weight(0.8f), style = style, textAlign = TextAlign.End)
    }
}

