package app.weathertrack.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.weathertrack.core.air.Air
import app.weathertrack.core.air.AirHour
import app.weathertrack.core.model.Forecast
import app.weathertrack.core.outdoor.DryingDay
import app.weathertrack.core.outdoor.Garden
import app.weathertrack.core.outdoor.Pavement
import app.weathertrack.core.outdoor.Sun
import app.weathertrack.core.outdoor.UvBurn
import app.weathertrack.core.warnings.Warning
import app.weathertrack.ui.Format
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private fun warningColour(w: Warning): Pair<Color, Color> = when (w.colour?.lowercase()) {
    "red" -> Color(0xFFC62828) to Color.White
    "orange" -> Color(0xFFEF6C00) to Color.White
    "yellow" -> Color(0xFFFBC02D) to Color.Black
    else -> if (w.serious) Color(0xFFEF6C00) to Color.White else Color(0xFFFFE082) to Color.Black
}

/** Official MetService warnings and watches that cover this place. */
@Composable
fun WarningsCard(warnings: List<Warning>, zone: ZoneId) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        warnings.forEach { w ->
            val (bg, fg) = warningColour(w)
            var open by remember(w.id) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().clickable { open = !open }, colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(w.headline.ifBlank { w.event }, fontWeight = FontWeight.SemiBold)
                    val from = w.onset?.let { "From ${Format.day(java.time.Instant.ofEpochSecond(it).atZone(zone).toLocalDate().toString())} ${Format.time(it, zone)}" }
                    val until = w.expires?.let { "until ${Format.day(java.time.Instant.ofEpochSecond(it).atZone(zone).toLocalDate().toString())} ${Format.time(it, zone)}" }
                    Text(listOfNotNull(from, until).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                    if (open) {
                        if (w.description.isNotBlank()) Text(w.description, style = MaterialTheme.typography.bodyMedium)
                        if (w.instruction.isNotBlank()) Text(w.instruction, style = MaterialTheme.typography.bodyMedium)
                        if (w.areaDesc.isNotBlank()) Text("Area: ${w.areaDesc}", style = MaterialTheme.typography.bodySmall)
                        w.link?.takeIf { it.startsWith("https://") }?.let { link ->
                            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }) {
                                Text("Open on MetService", color = fg)
                            }
                        }
                    }
                }
            }
        }
        Text("Warnings from MetService (CC BY 4.0). Tap one for details.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Sun, UV burn time, air quality and pollen, washing and garden: the small daily things. */
@Composable
fun OutdoorCard(f: Forecast, air: List<AirHour>, skinType: Int, zone: ZoneId, pollen: app.weathertrack.data.PollenView? = null) {
    val now = System.currentTimeMillis() / 1000
    val today = LocalDate.now(zone)
    val todayDay = f.daily.firstOrNull { it.date == today.toString() }
    val sun = remember(f.latitude, f.longitude, today) { Sun.day(f.latitude, f.longitude, today, zone) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Outside today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            // Sun and light.
            val sunrise = todayDay?.sunrise ?: sun.sunrise
            val sunset = todayDay?.sunset ?: sun.sunset
            Line("Sun", buildList {
                sun.dawn?.let { add("first light ${Format.time(it, zone)}") }
                sunrise?.let { add("up ${Format.time(it, zone)}") }
                sun.goldenFrom?.let { add("golden hour ${Format.time(it, zone)}") }
                sunset?.let { add("down ${Format.time(it, zone)}") }
                sun.dusk?.let { add("dark ${Format.time(it, zone)}") }
            }.joinToString(", "))

            // UV and burn time.
            val uvMax = todayDay?.uvIndexMax
            val burn = UvBurn.minutes(uvMax, skinType)
            val protect = UvBurn.protectHours(f.hourly, today, zone)
            if (uvMax != null) {
                Line("UV", "peak ${Format.uv(uvMax)}" +
                    (burn?.let { ", burn in about $it min" } ?: "") +
                    (protect?.let { ". Cover up ${Format.time(it.first, zone)} to ${Format.time(it.second, zone)}" } ?: ""))
            }

            // Hottest pavement in the next 12 hours.
            val pave = f.hourly.filter { it.time + 3600 > now && it.time < now + 12 * 3600 }
                .mapNotNull { h -> Pavement.estimate(h.temperature, h.shortwaveRadiation, h.windSpeed)?.let { h.time to it } }
                .maxByOrNull { it.second }
            pave?.takeIf { it.second >= Pavement.WARM - 5 }?.let { (t, v) ->
                val word = when {
                    v >= Pavement.TOO_HOT -> "too hot for paws"
                    v >= Pavement.WARM -> "hot, test it with your hand"
                    else -> "warm"
                }
                Line("Pavement", "about ${v.roundToInt()}° around ${Format.time(t, zone)}, $word",
                    if (v >= Pavement.TOO_HOT) MaterialTheme.colorScheme.error else null)
            }

            // Air quality and pollen.
            Air.now(air, now)?.let { a ->
                val worst = a.worst?.takeIf { (it.europeanAqi ?: 0.0) >= 40 && Air.category(it.europeanAqi) != a.category }
                Line("Air", "${a.category} (AQI ${a.now.europeanAqi?.roundToInt() ?: "–"}, PM2.5 ${a.now.pm25?.roundToInt() ?: "–"})" +
                    (worst?.let { ". ${Air.category(it.europeanAqi)} around ${Format.time(it.time, zone)}" } ?: ""))
                if (a.pollen.isNotEmpty()) Line("Pollen", a.pollen.joinToString(", ") { (k, v) -> "$k $v" })
            }
            // Our own estimate (not a measured count): season calendar times the weather, fitted to your reports when there are enough.
            pollen?.days?.firstOrNull()?.let { d ->
                Line("Hay fever", app.weathertrack.core.pollen.Pollen.text(d) +
                    (pollen.days.getOrNull(1)?.let { " Tomorrow ${it.level.label.lowercase()}." } ?: ""),
                    if (d.level == app.weathertrack.core.pollen.PollenLevel.HIGH) MaterialTheme.colorScheme.error else null)
            }

            // Washing and garden.
            DryingDay.of(f.hourly, today, zone)?.let { d ->
                Line("Washing", "${d.label} (${d.score}/10)" +
                    (if (d.bestFrom != null && d.bestTo != null) ". Dry ${Format.time(d.bestFrom!!, zone)} to ${Format.time(d.bestTo!!, zone)}" else ""))
            }
            Garden.watering(f.daily, today)?.let { Line("Garden", it.text) }
            if (air.isNotEmpty()) {
                Text("Air quality: Copernicus CAMS via Open-Meteo. Pavement and drying are estimates.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Line(label: String, text: String, color: Color? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, Modifier.padding(end = 8.dp).fillMaxWidth(0.24f), fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color ?: Color.Unspecified)
    }
}
