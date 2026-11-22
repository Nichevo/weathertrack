package app.weathertrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.weathertrack.core.alerts.Trigger
import app.weathertrack.core.alerts.TriggerKind
import app.weathertrack.core.jacket.JacketResult
import app.weathertrack.core.jacket.Verdict
import app.weathertrack.core.model.DayPoint
import app.weathertrack.core.model.Forecast
import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.model.WeatherCodes
import app.weathertrack.ui.Format
import app.weathertrack.core.accuracy.Band
import app.weathertrack.core.accuracy.Confidence
import app.weathertrack.ui.accuracy.ConfidenceTag
import app.weathertrack.ui.theme.ClearBlueContainer
import app.weathertrack.ui.theme.JacketAmber
import app.weathertrack.ui.theme.JacketAmberContainer
import app.weathertrack.ui.theme.OnClearBlueContainer
import app.weathertrack.ui.theme.OnJacketAmberContainer
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit = {},
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val onReport: (Boolean) -> Unit = vm::report
    val forecast = state.stored?.forecast
    val zone = Format.zone(forecast?.timezone ?: state.place?.timezone)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { PlacePicker(state, onSelect = vm::selectPlace) },
            actions = {
                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            },
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { err ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(err, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(3, 6).forEachIndexed { i, h ->
                        SegmentedButton(
                            selected = state.windowHours == h,
                            onClick = { vm.setWindow(h) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text("Next $h hours") }
                    }
                }
            }
            if (state.triggers.isNotEmpty()) {
                item { TriggerRow(state.triggers) }
            }
            item {
                val jacket = state.jacket
                if (jacket == null) {
                    Text(if (state.loading) "Getting the forecast…" else "No forecast yet. Tap refresh to try again.")
                } else {
                    VerdictCard(jacket, zone, state.radar, state.myReport)
                }
            }
            if (state.jacket != null) {
                item { ReportRow(state.reportMessage, onReport = onReport) }
                item { SureCard(state, zone) }
            }
            if (state.walks.isNotEmpty()) {
                item { SectionTitle("Walks") }
                val nowEpoch = System.currentTimeMillis() / 1000
                items(state.walks) { w -> WalkCard(w, zone, nowEpoch) }
            }
            if (forecast != null) {
                item { NowRow(forecast) }
                item { SectionTitle("Next hours") }
                item { HourStrip(forecast, zone) }
                item { SectionTitle("Next 7 days") }
                val today = java.time.LocalDate.now(zone)
                items(forecast.daily) { d -> DayRow(d, bandFor(state.highBands, today, d.date)) }
            }
            state.stored?.let { s ->
                item {
                    Text(
                        "Updated ${Format.ago(s.fetchedAtMs)} · Open-Meteo · stored as pull #${s.pullId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The "raining now / rain from" line. The 15-minute forecast is a model, so when a recent radar
 * frame is available it decides whether it is raining at the spot right now.
 */
private fun rainNowText(
    j: JacketResult, radar: app.weathertrack.core.map.RadarSpot?, zone: ZoneId,
    mine: app.weathertrack.core.accuracy.RainReport? = null,
): String? {
    val until = j.rainEndsAt?.let { " until about ${Format.time(it, zone)}" } ?: ""
    val seen = radar?.let { " (radar ${Format.time(it.time, zone)})" } ?: ""
    return when {
        mine != null -> (if (mine.raining) "Raining (you reported at " else "Dry (you reported at ") + "${Format.time(mine.time, zone)})" +
            if (!mine.raining && j.rainStartsAt != null && j.rainStartsAt!! > mine.time) ". Rain forecast from about ${Format.time(j.rainStartsAt!!, zone)}" else ""
        radar == null -> j.rainStartsAt?.let { if (j.rainingNow) "Raining now$until" else "Rain likely from about ${Format.time(it, zone)}$until" }
        radar.wetHere -> "Raining here now$seen" + if (j.rainingNow) until else ""
        j.rainingNow -> if (radar.wetNearby) "Showers nearby, dry right here$seen" else "Dry here on radar$seen, though the forecast shows rain"
        j.rainStartsAt != null -> "Rain likely from about ${Format.time(j.rainStartsAt!!, zone)}$until" +
            if (radar.wetNearby) ". Showers nearby on radar" else ""
        radar.wetNearby -> "Showers nearby on radar$seen"
        else -> null
    }
}

/** "It's raining" / "It's dry" buttons: the report is stored with what the forecast and radar said. */
@Composable
private fun ReportRow(message: String?, onReport: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Right now here:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.OutlinedButton(onClick = { onReport(true) }) {
                app.weathertrack.ui.WeatherIcon(63, true, size = 20.dp); Spacer(Modifier.width(6.dp)); Text("Raining")
            }
            androidx.compose.material3.OutlinedButton(onClick = { onReport(false) }) {
                app.weathertrack.ui.WeatherIcon(0, true, size = 20.dp); Spacer(Modifier.width(6.dp)); Text("Dry")
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

/** The forecast's key numbers next to how often forecasts like them have been right here. */
@Composable
private fun SureCard(state: HomeState, zone: ZoneId) {
    val j = state.jacket ?: return
    val trust = state.trust
    val today = java.time.LocalDate.now(zone).toString()
    val high = state.stored?.forecast?.daily?.firstOrNull { it.date == today }?.temperatureMax
    val band = state.highBands[0]
    val cal = trust?.let { app.weathertrack.core.accuracy.Feedback.calibrated(it.reliability, j.maxRainProbability) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How sure is this?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // Rain chance against what actually happened when forecasts said the same.
            val chance = j.maxRainProbability
            if (chance != null) {
                if (cal != null) {
                    val (happened, hours) = cal
                    val diff = happened * 100 - chance
                    Text("Rain: forecast up to ${Format.pct(chance)} by ${Format.time(j.windowEnd, zone)}. " +
                        "When it has said that here, it rained ${Format.pct(happened * 100)} of the time ($hours hours).")
                    val tip = when {
                        diff <= -15 -> "Rain is less likely than the forecast suggests."
                        diff >= 15 -> "Rain is more likely than the forecast suggests."
                        else -> null
                    }
                    tip?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                } else {
                    Text("Rain: forecast up to ${Format.pct(chance)}. Not enough stored forecasts yet to check how reliable that is here.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (high != null && band != null && band.confidence != Confidence.UNKNOWN) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("High ${Format.temp(high)}, likely ${Format.range(high + band.low, high + band.high, Format::temp, " to ")}  ",
                        Modifier.weight(1f))
                    app.weathertrack.ui.accuracy.ConfidenceTag(band.confidence)
                }
            }
            trust?.jacket?.takeIf { it.decided > 0 }?.let { js ->
                Text("Jacket call right on ${js.right} of ${js.decided} mornings lately (${Format.pct((js.share ?: 0.0) * 100)}).")
            }
            trust?.reports?.takeIf { it.reports > 0 }?.let { r ->
                val parts = listOfNotNull(
                    r.forecastShare?.let { "forecast agreed ${r.forecastRight} of ${r.forecastJudged}" },
                    r.radarShare?.let { "radar agreed ${r.radarRight} of ${r.radarJudged}" },
                )
                Text("Your ${r.reports} reports: ${parts.joinToString(", ")}.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The band for a day [date] ahead of [today]; between learned leads, the next longer one is used. */
private fun bandFor(bands: Map<Int, Band>, today: java.time.LocalDate, date: String): Band? {
    val lead = runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(date)).toInt() }.getOrNull() ?: return null
    if (lead < 0) return null
    return bands.keys.filter { it >= lead }.minOrNull()?.let { bands[it] }
}

@Composable
private fun PlacePicker(state: HomeState, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.LocationOn, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(state.place?.name ?: "Choose a place", style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change place")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.places.forEach { p ->
                DropdownMenuItem(
                    text = { Text(listOfNotNull(p.name, p.region ?: p.country).distinct().joinToString(", ")) },
                    onClick = { open = false; onSelect(p.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerdictCard(
    j: JacketResult, zone: ZoneId, radar: app.weathertrack.core.map.RadarSpot?,
    mine: app.weathertrack.core.accuracy.RainReport?,
) {
    val (bg, fg) = when (j.verdict) {
        Verdict.YES -> JacketAmberContainer to OnJacketAmberContainer
        Verdict.MAYBE -> JacketAmberContainer.copy(alpha = 0.55f) to OnJacketAmberContainer
        Verdict.NO -> ClearBlueContainer to OnClearBlueContainer
    }
    val headline = when (j.verdict) {
        Verdict.YES -> "Take a jacket"
        Verdict.MAYBE -> "Maybe a jacket"
        Verdict.NO -> "No jacket needed"
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape)
                        .background(if (j.verdict == Verdict.NO) Color(0xFF1E5AA8) else JacketAmber),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (j.verdict) { Verdict.YES -> "YES"; Verdict.MAYBE -> "?"; Verdict.NO -> "NO" },
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(headline, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Until ${Format.time(j.windowEnd, zone)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            rainNowText(j, radar, zone, mine)?.let { Text(it, fontWeight = FontWeight.Medium) }
            if (j.reasons.isEmpty()) {
                Text("Dry, light winds and mild for the next ${j.windowHours} hours.")
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    j.reasons.forEach { r -> AssistChip(onClick = {}, label = { Text(r.text) }) }
                }
            }
            Text(
                "Rain ${Format.pct(j.maxRainProbability)} max · ${Format.mm(j.totalRainMm)} · " +
                    "gusts ${Format.kmh(j.maxGustKmh)} · feels ${Format.temp(j.minFeelsLike)} min",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerRow(triggers: List<Trigger>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        triggers.forEach { t ->
            val (bg, fg) = when (t.kind) {
                TriggerKind.RAIN_SOON -> Color(0xFF1E5AA8) to Color.White
                TriggerKind.TOO_HOT -> Color(0xFFFFDAD6) to Color(0xFF8C1D18)
                TriggerKind.TOO_COLD -> ClearBlueContainer to OnClearBlueContainer
            }
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(t.text, color = fg, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NowRow(f: Forecast) {
    val c = f.current ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(Format.temp(c.temperature), fontSize = 56.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(WeatherCodes.describe(c.weatherCode), style = MaterialTheme.typography.titleMedium)
            Text(
                "Feels ${Format.temp(c.apparentTemperature)} · wind ${Format.kmh(c.windSpeed)}, gusts ${Format.kmh(c.windGusts)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            f.hourly.firstOrNull { it.time + 3600 > c.time }?.uvIndex?.let {
                Text("UV ${Format.uv(it)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HourStrip(f: Forecast, zone: ZoneId) {
    val now = System.currentTimeMillis() / 1000
    val hours = f.hourly.filter { it.time + 3600 > now }.take(24)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(hours) { h -> HourCell(h, zone) }
    }
}

@Composable
private fun HourCell(h: HourPoint, zone: ZoneId) {
    val prob = h.precipitationProbability ?: 0.0
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val tint = app.weathertrack.ui.tempColor(h.apparentTemperature ?: h.temperature, dark)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.width(72.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tint, tint.copy(alpha = tint.alpha * 0.25f))))
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(Format.hour(h.time, zone), style = MaterialTheme.typography.labelMedium)
            app.weathertrack.ui.WeatherIcon(h.weatherCode, h.isDay, size = 34.dp)
            Text(Format.temp(h.temperature), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            h.apparentTemperature?.let {
                Text("feels ${Format.temp(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val gust = h.windGusts ?: 0.0
            Text(
                "💨 ${Format.kmh(h.windGusts).removeSuffix(" km/h")}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (gust >= 40) FontWeight.Bold else FontWeight.Normal,
                color = if (gust >= 40) Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RainBar(prob, h.precipitation)
        }
    }
}

/** Rain chance as a blue fill along the bottom of an hour, with the amount when there is some. */
@Composable
private fun RainBar(prob: Double, mm: Double?) {
    val wet = prob >= 40
    Box(Modifier.fillMaxWidth().height(26.dp).background(Color(0x142F7DD1)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .fillMaxHeight((prob / 100.0).toFloat().coerceIn(0f, 1f))
                .background(Color(0xFF2F7DD1).copy(alpha = 0.25f + 0.5f * (prob / 100.0).toFloat().coerceIn(0f, 1f))),
        )
        Text(
            if ((mm ?: 0.0) >= 0.1) "${Format.pct(prob)} · ${String.format(java.util.Locale.US, "%.1f", mm)}" else Format.pct(prob),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (wet) FontWeight.Bold else FontWeight.Normal,
            color = if (prob >= 60) Color.White else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DayRow(d: DayPoint, band: Band?) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Format.day(d.date), Modifier.width(64.dp), fontWeight = FontWeight.Medium)
            app.weathertrack.ui.WeatherIcon(d.weatherCode, true, size = 26.dp)
            Spacer(Modifier.width(6.dp))
            Text(WeatherCodes.describe(d.weatherCode), Modifier.weight(1f))
            Text(Format.pct(d.precipitationProbabilityMax), Modifier.width(48.dp))
            Text("UV ${Format.uv(d.uvIndexMax)}", Modifier.width(48.dp))
            Text("${Format.temp(d.temperatureMin)} / ${Format.temp(d.temperatureMax)}")
        }
        val high = d.temperatureMax
        if (band != null && high != null && band.confidence != Confidence.UNKNOWN) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(64.dp))
                Text(
                    "High likely ${Format.range(high + band.low, high + band.high, Format::temp, " to ")}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConfidenceTag(band.confidence)
            }
        }
        HorizontalDivider()
    }
    Spacer(Modifier.height(0.dp))
}
