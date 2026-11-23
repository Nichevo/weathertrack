package app.weathertrack.ui.history

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle as UiTextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.weathertrack.core.history.ExploreMeasure
import app.weathertrack.core.history.ExploreSpan
import app.weathertrack.core.history.ExploreView
import app.weathertrack.core.history.Explorer
import app.weathertrack.core.history.PeriodStats
import app.weathertrack.ui.accuracy.ForecastCheckScreen
import app.weathertrack.ui.accuracy.ForecastCheckViewModel
import app.weathertrack.ui.climate.ClimateScreen
import app.weathertrack.ui.climate.ClimateViewModel
import app.weathertrack.ui.data.DataScreen
import app.weathertrack.ui.theme.JacketAmber
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val longDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private val PAGES = listOf("Past weather", "Forecast check", "Home energy", "Data")

private val NowColor = Color(0xFF2F6FB0)
private val TempNowColor = Color(0xFFD9822B)

/**
 * The History tab. Past weather, Forecast check, Home energy and Data are tabs inside it, so the bottom bar
 * stays on History. Each tap on History in the bottom bar bumps [resetKey], which brings back Past weather at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    resetKey: Int = 0,
    vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState { PAGES.size }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var handledReset by rememberSaveable { mutableIntStateOf(resetKey) }
    LaunchedEffect(resetKey) {
        if (resetKey != handledReset) {
            handledReset = resetKey
            pager.scrollToPage(0)
            list.scrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("History · ${s.place?.name ?: ""}") },
            actions = {
                // Each tab downloads its own data; the button follows the tab on screen.
                when (pager.currentPage) {
                    0 -> IconButton(onClick = vm::sync, enabled = !s.syncing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Download new days")
                    }
                    1 -> {
                        val check: ForecastCheckViewModel = viewModel(factory = ForecastCheckViewModel.Factory)
                        val cs by check.state.collectAsStateWithLifecycle()
                        IconButton(onClick = check::sync, enabled = !cs.syncing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Download latest")
                        }
                    }
                    2 -> {
                        val home: ClimateViewModel = viewModel(factory = ClimateViewModel.Factory)
                        val hs by home.state.collectAsStateWithLifecycle()
                        IconButton(onClick = home::sync, enabled = !hs.syncing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Download new days")
                        }
                    }
                }
            },
        )
        PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp) {
            PAGES.forEachIndexed { i, title ->
                Tab(
                    selected = pager.currentPage == i,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    text = { Text(title, maxLines = 1) },
                )
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
            when (page) {
                0 -> PastWeather(s, vm, list)
                1 -> ForecastCheckScreen(embedded = true)
                2 -> ClimateScreen(embedded = true)
                else -> DataScreen(embedded = true)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PastWeather(s: HistoryState, vm: HistoryViewModel, list: LazyListState) {
    val context = LocalContext.current
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val text = pendingCsv
        pendingCsv = null
        if (uri != null && text != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream")
            }.isSuccess
            Toast.makeText(context, if (ok) "Saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }
    fun save(name: String, text: String?) {
        if (text == null) return
        pendingCsv = text
        saver.launch(name)
    }

    Column(Modifier.fillMaxSize()) {
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
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusLine(s) }
            s.yearOnYear?.takeIf { it.years.isNotEmpty() }?.let { yoy ->
                item { Headline(s.month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH), yoy.lastDay, s.month.lengthOfMonth(), yoy.years.first(), yoy.normal) }
            }
            item { Controls(s, vm) }
            val v = s.explore
            if (v == null) {
                if (!s.syncing) item { Text("No history for this period yet.") }
            } else {
                item {
                    val spanWord = s.span.label.lowercase()
                    Title(if (v.measure.cumulative) "${v.measure.label} building up through the $spanWord" else "Daily ${v.measure.label.lowercase()} through the $spanWord")
                }
                item { ExploreChart(v, s.showYears, s.showNormal) }
                item {
                    val span = if (v.days >= v.periodLength) "Whole ${s.span.label.lowercase()}" else "First ${v.days} days"
                    Title("$span, year on year")
                }
                item { ExploreTable(v) }
                if (s.showDecades && v.decades.isNotEmpty()) {
                    item { Title("By decade") }
                    item { Note("Average over the same days, years with at least 9 in 10 days stored.") }
                    val lo = v.decades.mapNotNull { it.value }.minOrNull() ?: 0.0
                    val hi = v.decades.mapNotNull { it.value }.maxOrNull() ?: 1.0
                    items(v.decades) { d -> DecadeBar(d.decadeStart, d.value, lo, hi, v.measure) }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val stem = "history-${v.measure.name.lowercase()}-${v.start}"
                        OutlinedButton(onClick = { save("$stem.csv", vm.viewCsv()) }) { Text("Save this view (CSV)") }
                        OutlinedButton(onClick = { save("$stem-raw-days.csv", vm.rawCsv()) }) { Text("Raw days (CSV)") }
                    }
                }
            }
            s.seasons?.let { sv ->
                item { Title("Last 2 years and the year ahead") }
                item { SeasonCard(sv, s.outlook) }
            }
            item {
                Note(
                    "Source: Open-Meteo historical weather (ERA5 reanalysis), about 10 km grid. " +
                        "The newest few days arrive with a delay of about a week. Usual range = 1 in 10 years either side, 1991–2020.",
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
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private fun fmt(iso: String?): String = iso?.let { LocalDate.parse(it).format(longDate) } ?: "–"

/** "November so far": the month's highs, rain and rain days against 1991–2020. */
@Composable
private fun Headline(monthName: String, lastDay: Int, length: Int, now: PeriodStats, normal: PeriodStats?) {
    val span = if (lastDay >= length) "ALL OF ${monthName.uppercase()}" else "${monthName.uppercase()} SO FAR · 1–$lastDay"
    val nowHigh = now.meanMax
    val normalHigh = normal?.meanMax
    val nowRain = now.totalRain
    val normalRain = normal?.totalRain
    val dHigh = if (nowHigh != null && normalHigh != null) nowHigh - normalHigh else null
    val rainPct = if (nowRain != null && normalRain != null && normalRain > 0) nowRain / normalRain * 100 else null
    val words = listOfNotNull(
        dHigh?.let { if (it >= 0.5) "Warmer" else if (it <= -0.5) "Cooler" else "About as warm" },
        rainPct?.let { if (it >= 120) "wetter" else if (it <= 80) "drier" else "about as wet" },
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(span, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (words.isNotEmpty()) {
                Text(words.joinToString(" and ").replaceFirstChar { it.uppercase() } + " than normal",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kpi(Modifier.weight(1f),
                    dHigh?.let { String.format(Locale.US, "%+.1f°", it) } ?: t(now.meanMax),
                    if (dHigh != null) "highs vs normal" else "average high",
                    if (dHigh == null) null else if (dHigh >= 0) TempNowColor else NowColor)
                Kpi(Modifier.weight(1f),
                    rainPct?.let { "${it.roundToInt()}%" } ?: mm(now.totalRain),
                    if (rainPct != null) "of normal rain" else "rain", NowColor)
                Kpi(Modifier.weight(1f),
                    days(now.rainyDays),
                    "rain days" + (normal?.rainyDays?.let { " (normal ${it.roundToInt()})" } ?: ""), null)
            }
        }
    }
}

@Composable
private fun Kpi(modifier: Modifier, value: String, label: String, color: Color?) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = color ?: MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(s: HistoryState, vm: HistoryViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ControlRow("Measure") {
            ExploreMeasure.entries.forEach { m ->
                FilterChip(selected = s.measure == m, onClick = { vm.setMeasure(m) }, label = { Text(m.label) })
            }
        }
        ControlRow("Period") {
            ExploreSpan.entries.forEach { sp ->
                FilterChip(selected = s.span == sp, onClick = { vm.setSpan(sp) }, label = { Text(sp.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.shiftMonth(-1) }) { Icon(Icons.Filled.KeyboardArrowLeft, "Earlier") }
            Text(
                Explorer.periodLabel(s.span, s.span.start(s.month), southern = (s.place?.latitude ?: -1.0) < 0),
                Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            )
            IconButton(onClick = { vm.shiftMonth(1) }) { Icon(Icons.Filled.KeyboardArrowRight, "Later") }
        }
        ControlRow("Against") {
            FilterChip(selected = s.showYears, onClick = vm::toggleYears, label = { Text("Last 2 years") })
            FilterChip(selected = s.showNormal, onClick = vm::toggleNormal, label = { Text("Usual range") })
            FilterChip(selected = s.showDecades, onClick = vm::toggleDecades, label = { Text("Decades") })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlRow(label: String, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(68.dp).padding(top = 14.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

/** Current period in colour, earlier years grey, the 1991–2020 average dashed and its 1-in-10 range shaded. */
@Composable
private fun ExploreChart(v: ExploreView, showYears: Boolean, showNormal: Boolean) {
    val measurer = rememberTextMeasurer()
    val labelStyle = UiTextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val nowColor = if (v.measure == ExploreMeasure.HIGH || v.measure == ExploreMeasure.LOW) TempNowColor else NowColor
    val pastColors = listOf(Color(0xFF8A8A8A), Color(0xFFBDBDBD))
    val band = v.band.takeIf { showNormal }
    val past = if (showYears) v.series.drop(1) else emptyList()
    val now = v.series.firstOrNull()

    val values = buildList {
        now?.values?.filterNotNull()?.let(::addAll)
        past.forEach { addAll(it.values.filterNotNull()) }
        band?.let { b -> addAll(b.p10.filterNotNull()); addAll(b.p90.filterNotNull()) }
    }
    if (values.isEmpty()) return
    var lo = values.min()
    var hi = values.max()
    if (v.measure.cumulative) lo = 0.0
    if (hi - lo < 1e-6) hi = lo + 1
    val pad = (hi - lo) * 0.05
    if (!v.measure.cumulative) lo -= pad
    hi += pad
    val steps = 4
    val n = v.periodLength

    Column {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val left = 34.dp.toPx()
            val bottom = size.height - 16.dp.toPx()
            val top = 4.dp.toPx()
            val w = size.width - left - 4.dp.toPx()
            fun x(i: Int) = left + w * i / (n - 1).coerceAtLeast(1)
            fun y(value: Double) = (bottom - (value - lo) / (hi - lo) * (bottom - top)).toFloat()

            for (k in 0..steps) {
                val value = lo + (hi - lo) * k / steps
                val yy = y(value)
                drawLine(grid, Offset(left, yy), Offset(left + w, yy), strokeWidth = 1f)
                val text = measurer.measure(axisText(value, v.measure), labelStyle)
                drawText(text, topLeft = Offset(left - text.size.width - 4.dp.toPx(), yy - text.size.height / 2))
            }
            band?.let { b ->
                val path = Path()
                var started = false
                for (i in 0 until n) { val p = b.p90.getOrNull(i) ?: continue; if (!started) { path.moveTo(x(i), y(p)); started = true } else path.lineTo(x(i), y(p)) }
                for (i in n - 1 downTo 0) { val p = b.p10.getOrNull(i) ?: continue; path.lineTo(x(i), y(p)) }
                if (started) { path.close(); drawPath(path, nowColor.copy(alpha = 0.14f)) }
                drawSeries(b.mean, ::x, ::y, nowColor.copy(alpha = 0.8f), 1.5.dp.toPx(), dashed = true)
            }
            past.forEachIndexed { i, sr -> drawSeries(sr.values, ::x, ::y, pastColors[i % pastColors.size], 1.5.dp.toPx()) }
            now?.let { drawSeries(it.values, ::x, ::y, nowColor, 3.dp.toPx()) }

            val first = measurer.measure(v.start.format(shortDate), labelStyle)
            drawText(first, topLeft = Offset(left, bottom + 2.dp.toPx()))
            val last = measurer.measure(v.end.format(shortDate), labelStyle)
            drawText(last, topLeft = Offset(left + w - last.size.width, bottom + 2.dp.toPx()))
        }
        FlowRowLegend(
            buildList {
                now?.let { add(Triple(it.label, nowColor, false)) }
                past.forEachIndexed { i, sr -> add(Triple(sr.label, pastColors[i % pastColors.size], false)) }
                band?.let { add(Triple("1991–2020 average · shaded: usual range", nowColor.copy(alpha = 0.8f), true)) }
            },
        )
    }
}

private fun DrawScope.drawSeries(values: List<Double?>, x: (Int) -> Float, y: (Double) -> Float, color: Color, width: Float, dashed: Boolean = false) {
    val path = Path()
    var pen = false
    values.forEachIndexed { i, v ->
        if (v == null) { pen = false; return@forEachIndexed }
        if (pen) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
        pen = true
    }
    drawPath(path, color, style = Stroke(width = width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowLegend(items: List<Triple<String, Color, Boolean>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (label, color, dashed) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
                    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3.dp.toPx(),
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null)
                }
                Text(" $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ExploreTable(v: ExploreView) {
    val m = v.measure
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            TableRow("Year", if (m.cumulative) "Total" else "Average", "vs normal", m.countLabel, "Rank", header = true)
            HorizontalDivider()
            v.rows.forEachIndexed { i, r ->
                TableRow(
                    r.label, value(r.value, m), r.vsNormal?.let { signed(it, m) } ?: "–", r.count?.toString() ?: "–",
                    Explorer.rankText(r, m) ?: "–", bold = i == 0,
                    accent = r.vsNormal?.let { d -> if (abs(d) < 1e-9) null else if ((d > 0) == (m == ExploreMeasure.RAIN)) NowColor else TempNowColor },
                )
            }
            v.normal?.let {
                HorizontalDivider()
                TableRow("1991–2020", value(it, m), "", "", "", muted = true)
            }
            v.rows.firstOrNull()?.let { r ->
                val ext = r.extreme
                val extDate = r.extremeDate
                if (ext != null && extDate != null) {
                    val word = when (m) {
                        ExploreMeasure.HIGH -> "Hottest day"; ExploreMeasure.LOW -> "Coldest night"; ExploreMeasure.RAIN -> "Wettest day"
                        ExploreMeasure.SUN -> "Sunniest day"; ExploreMeasure.GUST -> "Strongest gust"
                    }
                    Text("$word in ${r.label}: ${value(ext, m)} on ${extDate.format(shortDate)}",
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TableRow(
    a: String, b: String, c: String, d: String, e: String,
    header: Boolean = false, bold: Boolean = false, muted: Boolean = false, accent: Color? = null,
) {
    val weight = if (header || bold) FontWeight.SemiBold else FontWeight.Normal
    val color = if (muted || header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(a, Modifier.weight(1.1f), fontWeight = weight, color = color, style = style)
        Text(b, Modifier.weight(1.1f), fontWeight = weight, color = color, style = style, textAlign = TextAlign.End)
        Text(c, Modifier.weight(1.1f), fontWeight = weight, color = if (header) color else accent ?: color, style = style, textAlign = TextAlign.End)
        Text(d, Modifier.weight(1f), fontWeight = weight, color = color, style = style, textAlign = TextAlign.End)
        Text(e, Modifier.weight(1.4f), fontWeight = weight, color = color, style = style, textAlign = TextAlign.End)
    }
}

@Composable
private fun DecadeBar(decade: Int, v: Double?, lo: Double, hi: Double, m: ExploreMeasure) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${decade}s", Modifier.width(52.dp), fontWeight = FontWeight.Medium)
        Canvas(Modifier.weight(1f).height(14.dp)) {
            drawRoundRect(track, size = size, cornerRadius = CornerRadius(7.dp.toPx()))
            val value = v ?: return@Canvas
            // Start the bars a little below the lowest decade so differences show.
            val base = lo - (hi - lo).coerceAtLeast(1e-6) * 0.5
            val frac = ((value - base) / (hi - base)).coerceIn(0.02, 1.0)
            val wd = (size.width * frac).toFloat()
            drawRoundRect(bar, size = Size(wd, size.height), cornerRadius = CornerRadius(7.dp.toPx()))
            drawRoundRect(JacketAmber, topLeft = Offset((wd - 4f).coerceAtLeast(0f), 0f), size = Size(4f, size.height))
        }
        Text(value(v, m), Modifier.width(76.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun value(v: Double?, m: ExploreMeasure): String = v?.let {
    when (m.unit) {
        "°" -> String.format(Locale.US, "%.1f°", it)
        "km/h" -> "${it.roundToInt()} km/h"
        "h" -> "${it.roundToInt()} h"
        else -> "${it.roundToInt()} mm"
    }
} ?: "–"

private fun signed(v: Double, m: ExploreMeasure): String = when (m.unit) {
    "°" -> String.format(Locale.US, "%+.1f°", v)
    else -> (if (v >= 0) "+" else "−") + value(abs(v), m)
}

private fun axisText(v: Double, m: ExploreMeasure): String =
    if (m.unit == "°") String.format(Locale.US, "%.0f°", v) else v.roundToInt().toString()

private fun t(v: Double?) = v?.let { String.format(Locale.US, "%.1f°", it) } ?: "–"
private fun mm(v: Double?) = v?.let { String.format(Locale.US, "%.0f mm", it) } ?: "–"
private fun days(v: Double?) = v?.let { if (it % 1.0 == 0.0) "%.0f".format(it) else String.format(Locale.US, "%.1f", it) } ?: "–"
