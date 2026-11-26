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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.launch
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
    /** Shown as a tab inside History: no app bar of its own (History's bar has the refresh button). */
    embedded: Boolean = false,
    vm: ForecastCheckViewModel = viewModel(factory = ForecastCheckViewModel.Factory),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val sectionY = remember { mutableStateMapOf<String, Int>() }
    Column(Modifier.fillMaxSize()) {
        if (!embedded) TopAppBar(
            title = { Text("Forecast check · ${s.place?.name ?: ""}") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = vm::sync, enabled = !s.syncing) { Icon(Icons.Filled.Refresh, contentDescription = "Download latest") }
            },
        )
        if (s.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Jump straight to a section instead of scrolling through them all.
        val jumps = JUMPS.filter { sectionY.containsKey(it.second) }
        if (jumps.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistChip(onClick = { scope.launch { scroll.animateScrollTo(0) } }, label = { Text("Summary") })
                jumps.forEach { (label, title) ->
                    AssistChip(
                        onClick = { scope.launch { scroll.animateScrollTo(sectionY[title] ?: 0) } },
                        label = { Text(label) },
                    )
                }
            }
            HorizontalDivider()
        }
        CompositionLocalProvider(LocalSectionY provides sectionY) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
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
                            (if (s.seasonal) "(highs: same time of year, all stored years; rain: ${s.period.label.lowercase()}). "
                            else "(${s.period.label.lowercase()} of history). ") + "Wider means less sure.",
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

            if (s.coverage.isNotEmpty()) {
                Section("Do the ranges hold?") {
                    Text(
                        "Ranges built only from older days, checked against the last 30 days. A good range catches about 8 in 10.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    s.coverage.forEach { c ->
                        val pct = (c.share * 100).roundToInt()
                        val verdict = when {
                            c.total < 10 -> "too few days to say"
                            pct < 65 -> "too narrow, be less sure"
                            pct > 95 -> "wider than needed"
                            else -> "about right"
                        }
                        Text("${if (c.lead == 0) "Today" else "${c.lead} day${if (c.lead == 1) "" else "s"} ahead"}: " +
                            "${c.inside} of ${c.total} days inside ($pct%), $verdict")
                    }
                }
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

            Section("Jacket call by how far ahead") {
                Text(
                    "Each day's 3-hour windows from 6 am to 9 pm, called from the forecast stored that many hours before " +
                        "and checked against what happened. A call that's right 3 hours out is more use than one right only an hour out.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                JacketLeadBars(s.jacketByLead)
                if (s.jacketByLead.values.all { it.decided == 0 }) Text("Builds up as forecasts are stored through the day.")
                else s.jacketByLead.forEach { (lead, j) ->
                    Text("$lead h ahead: " + (j.share?.let { "right ${(it * 100).roundToInt()}%, ${j.right} of ${j.decided} windows" } ?: "no windows yet") +
                        if (j.maybes > 0) ", ${j.maybes} maybe" else "", style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("How the weather links up here") {
                Text(
                    "From the actual weather stored for this place in the chosen period. Each day is compared with normal for that " +
                        "time of year (the 15 days around it), so the seasons don't count as a link. Daytime is 10 am to 4 pm; " +
                        "overnight is 9 pm to 6 am.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.relations.isEmpty()) Text("Needs at least ${app.weathertrack.core.accuracy.Relations.MIN_DAYS} days of actuals with cloud and wind. Pick a longer period or sync.")
                else {
                    RelationsPicker(s.relations)
                    Text("All links", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    s.relations.sortedByDescending { kotlin.math.abs(it.r) }.forEach { Text(it.sentence(), style = MaterialTheme.typography.bodySmall) }
                }
            }

            Section("Pollen estimate") {
                val p = s.pollen
                Text(
                    "Our own estimate, not a measured pollen count: the season calendar times what the weather does to pollen " +
                        "(warm, sunny, dry and breezy raise it; rain clears it). Checked against the weather that happened on days you tapped " +
                        "Pollen or No symptoms; Moderate or High counts as calling symptoms. After ${app.weathertrack.core.pollen.Pollen.MIN_LABELS} " +
                        "reported days (with at least ${app.weathertrack.core.pollen.Pollen.MIN_EACH} of each) it fits itself to you.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p == null || p.score.total == 0) Text("No reported days yet. Tap Pollen or No symptoms today on Today.")
                else {
                    Text("Right on ${p.score.right} of ${p.score.total} reported days (${pct(p.score.share ?: 0.0)}). " +
                        "On the ${p.score.symptomDays} days pollen bothered you it said Moderate or High on ${p.score.calledOnSymptomDays}.",
                        style = MaterialTheme.typography.bodySmall)
                    Text(if (p.personal) "Using your personal model." else
                        "Using the general rules: ${p.reportedDays} of ${app.weathertrack.core.pollen.Pollen.MIN_LABELS} reported days so far.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("When forecasts settle") {
                Text(
                    "For each hour of the last week: how far ahead every source's forecast became right and then stayed right " +
                        "through to the hour (rain or dry; temperature within ${app.weathertrack.core.sources.Drift.TEMP_OK.roundToInt()}°; " +
                        "wind within ${app.weathertrack.core.sources.Drift.WIND_OK.roundToInt()} km/h; cloud within ${app.weathertrack.core.sources.Drift.CLOUD_OK.roundToInt()} points). " +
                        "Typical is the median; the columns are the share of hours already settled that far out.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.settled.isEmpty()) Text("Needs a few days of hourly runs. Open Sources now and then, or let the app refresh in the background.")
                s.settled.groupBy { it.variable }.forEach { (v, rows) ->
                    Text(v.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    SettledBars(rows.sortedByDescending { it.medianLeadH })
                    SettledRow("Source", "Typical", "3 h", "6 h", "12 h", "24 h", header = true)
                    rows.sortedByDescending { it.medianLeadH }.forEach { r ->
                        SettledRow(app.weathertrack.core.sources.Sources.label(r.source), "${r.medianLeadH.roundToInt()} h",
                            pct(r.by3), pct(r.by6), pct(r.by12), pct(r.by24))
                    }
                }
            }

            Section("Highs by kind of cloud") {
                Text(
                    "Daily high misses grouped by the cloud forecast for the day (9 am to 5 pm). Low cloud that lingers, like sea " +
                        "stratus, usually means bigger misses, so on a low-cloud day Today uses these wider ranges once there are " +
                        "${Band.MIN_BAND_DAYS} such days.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.highByCloud.isEmpty()) Text("Needs wind and cloud from earlier forecasts. It fills in after the next download.")
                s.highByCloud.forEach { (reg, errs) ->
                    errs.filter { it.lead in listOf(1, 2) }.forEach { e ->
                        Text("${reg.label}, ${leadLabel(e.lead).lowercase()}: off by ${fmt1(e.meanAbs)}° on average, " +
                            "${biasText(e.bias)}, 8 in 10 within ${String.format(Locale.US, "%+.1f", e.p10)} to ${String.format(Locale.US, "%+.1f", e.p90)}° (${e.days} days)")
                    }
                }
            }

            Section("Wind and cloud") {
                Text(
                    "Each hour against what happened (actual minus forecast). Range is where 8 in 10 hours landed. Wind direction " +
                        "is only judged when it blew at least ${app.weathertrack.core.accuracy.WindCloud.DIRECTION_MIN_KMH.roundToInt()} km/h.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.windCloud.isEmpty()) Text("No wind or cloud to compare yet. It fills in after the next download.")
                s.windCloud.forEach { (m, errs) ->
                    Text(m.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    errs.forEach { e ->
                        val u = if (m.unit == "%") " pts" else if (m.unit == "°") "°" else " ${m.unit}"
                        Text("${leadLabel(e.lead)}: off by ${e.meanAbs.roundToInt()}$u, " +
                            "average ${if (e.bias >= 0) "+" else ""}${e.bias.roundToInt()}$u, " +
                            "range ${String.format(Locale.US, "%+d", e.p10.roundToInt())} to ${String.format(Locale.US, "%+d", e.p90.roundToInt())} (${e.days} h)",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (s.sun.isNotEmpty()) {
                    Text("Sunny or not", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    Text("Daylight hours, sunny meaning under ${app.weathertrack.core.cloud.Sky.SUNNY_MAX.roundToInt()}% low and mid cloud. " +
                        "High cloud is left out because it barely dims the sun.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.sun.forEach { sc ->
                        Text("${leadLabel(sc.lead)}: right ${sc.right} of ${sc.total} hours (${((sc.share ?: 0.0) * 100).roundToInt()}%). " +
                            "Said sunny ${sc.sunnyForecast} h, was sunny ${sc.sunnyActual} h.",
                            style = MaterialTheme.typography.bodySmall)
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

            Section("Your reports") {
                val r = s.reports
                if (r == null || r.reports == 0) {
                    Text("Tap Dry, Spitting, Rain or Heavy on Today (or tap the widget) when you're out. Each report is saved with what " +
                        "the forecast and radar said at that moment, and scored here.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${r.reports} reports in this period")
                    r.forecastShare?.let { Text("Forecast agreed with you: ${r.forecastRight} of ${r.forecastJudged} (${(it * 100).roundToInt()}%)") }
                    r.radarShare?.let { Text("Radar agreed with you: ${r.radarRight} of ${r.radarJudged} (${(it * 100).roundToInt()}%)") }
                    r.levelShare?.let {
                        Text("Forecast amount in your band: ${r.levelRight} of ${r.levelJudged} (${(it * 100).roundToInt()}%)")
                        Text("Too heavy ${r.forecastHeavier} · too light ${r.forecastLighter}", style = MaterialTheme.typography.bodySmall)
                        ReportMatrix(r.matrix)
                    }
                    Text("Today trusts your report for 45 minutes, then radar over the forecast for \"raining now\".",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
}

/** Rows: what you reported. Columns: what the 15-minute forecast amount said for that moment. */
@Composable
private fun ReportMatrix(matrix: Map<app.weathertrack.core.accuracy.RainLevel, Map<app.weathertrack.core.accuracy.RainLevel, Int>>) {
    val levels = app.weathertrack.core.accuracy.RainLevel.entries
    val small = MaterialTheme.typography.bodySmall
    Column(Modifier.padding(top = 4.dp)) {
        Row {
            Text("You ↓ forecast →", Modifier.weight(1.4f), style = small, fontWeight = FontWeight.SemiBold)
            levels.forEach { Text(it.label, Modifier.weight(1f), style = small, fontWeight = FontWeight.SemiBold) }
        }
        levels.forEach { seen ->
            val row = matrix[seen] ?: return@forEach
            Row {
                Text(seen.label, Modifier.weight(1.4f), style = small)
                levels.forEach { said ->
                    val n = row[said] ?: 0
                    Text(if (n == 0) "·" else "$n", Modifier.weight(1f), style = small,
                        fontWeight = if (said == seen && n > 0) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Text("Bands: Spitting under 0.5 mm/h, Rain 0.5 to 4, Heavy over 4. Reports from before these levels count for wet or dry only.",
            style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Chip label to section title, in page order. */
private val JUMPS = listOf(
    "Ranges" to "Next days: likely range",
    "Ranges held?" to "Do the ranges hold?",
    "Daily high" to "How far off the daily high was",
    "Jacket" to "Jacket call by how far ahead",
    "Links" to "How the weather links up here",
    "Pollen" to "Pollen estimate",
    "Settling" to "When forecasts settle",
    "Cloud" to "Highs by kind of cloud",
    "Wind & cloud" to "Wind and cloud",
    "Rain %" to "When it said a chance of rain",
    "Misses" to "Biggest misses",
    "Your reports" to "Your reports",
    "Data" to "Data behind this",
)

/** Where each section starts inside the scrolling column, so the chips can jump to it. */
private val LocalSectionY = staticCompositionLocalOf<MutableMap<String, Int>?> { null }

private fun pct(v: Double) = "${(v * 100).roundToInt()}%"

@Composable
private fun SettledRow(a: String, b: String, c: String, d: String, e: String, f: String, header: Boolean = false) {
    val w = if (header) FontWeight.SemiBold else FontWeight.Normal
    Row(Modifier.fillMaxWidth()) {
        Text(a, Modifier.weight(2.2f), style = MaterialTheme.typography.bodySmall, fontWeight = w, maxLines = 1)
        listOf(b, c, d, e, f).forEach {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = w, textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
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
    val positions = LocalSectionY.current
    Card(Modifier.fillMaxWidth().onGloballyPositioned { c -> positions?.set(title, c.positionInParent().y.toInt()) }) {
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
