package app.weathertrack.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.weathertrack.MainActivity
import app.weathertrack.WeatherApp
import app.weathertrack.core.alerts.Trigger
import app.weathertrack.core.alerts.TriggerCalculator
import app.weathertrack.core.alerts.TriggerKind
import app.weathertrack.core.jacket.JacketCalculator
import app.weathertrack.core.jacket.Verdict
import app.weathertrack.core.model.DayPoint
import app.weathertrack.core.model.Forecast
import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.model.WeatherCodes
import app.weathertrack.core.walk.WalkForecast
import app.weathertrack.core.walk.WalkPlanner
import app.weathertrack.core.walk.WalkRating
import app.weathertrack.ui.Format
import java.time.Instant
import java.time.ZoneId

/** Everything the widget shows, computed from the latest stored forecast (no network calls). */
data class WidgetSnapshot(
    val placeName: String,
    val forecast: Forecast,
    val fetchedAtMs: Long,
    val zone: ZoneId,
    val nowEpoch: Long,
    val jacket: Verdict,
    val triggers: List<Trigger>,
    val walk: WalkForecast?,
    /** e.g. "Low 5:42pm", the next tide turn; null away from the sea. */
    val tide: String? = null,
)

private object Palette {
    val bg = Color(0xFFF8F9FF)
    val bgDark = Color(0xFF1B1F27)
    val text = Color(0xFF1A1C20)
    val muted = Color(0xFF5A5F69)
    val blue = Color(0xFF1E5AA8)
    val amber = Color(0xFFF2A900)
    val amberSoft = Color(0xFFFFE3A3)
    val onAmber = Color(0xFF3D2A00)
    val red = Color(0xFFB3261E)
    val redSoft = Color(0xFFFFDAD6)
    val iceSoft = Color(0xFFD6E4FF)
}

class DetailWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = load(context)
        provideContent { WidgetContent(snapshot) }
    }

    private suspend fun load(context: Context): WidgetSnapshot? {
        val container = (context.applicationContext as WeatherApp).container
        container.places.ensureDefaults()
        val place = container.places.selected() ?: return null
        val stored = container.weather.cachedForecast(place) ?: return null
        val f = stored.forecast
        val zone = Format.zone(f.timezone)
        val now = System.currentTimeMillis() / 1000
        val cfg = container.settings.current
        return WidgetSnapshot(
            placeName = place.name,
            forecast = f,
            fetchedAtMs = stored.fetchedAtMs,
            zone = zone,
            nowEpoch = now,
            jacket = JacketCalculator.evaluate(f.hourly, now, 3, f.quarterHourly, cfg.jacketThresholds).verdict,
            triggers = TriggerCalculator.evaluate(f.hourly, f.quarterHourly, now, cfg.triggerThresholds) { Format.hour(it, zone) },
            walk = WalkPlanner.upcoming(f.hourly, cfg.walkWindows, zone, now, cfg.walkThresholds, count = 1).firstOrNull(),
            tide = runCatching { container.extras.tides(place, "widget") }.getOrNull()
                ?.let { app.weathertrack.core.tides.Tides.now(it, now) }?.next
                ?.let { (if (it.high) "High " else "Low ") + Format.time(it.time, zone).replace(" ", "").lowercase() },
        )
    }
}

class DetailWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DetailWidget()
}

private fun text(size: Int, color: Color = Palette.text, bold: Boolean = false) =
    TextStyle(fontSize = size.sp, color = ColorProvider(color), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable
private fun WidgetContent(s: WidgetSnapshot?) {
    val root = GlanceModifier.fillMaxSize()
        .background(ColorProvider(Palette.bg))
        .cornerRadius(20.dp)
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .clickable(actionStartActivity<MainActivity>())
    if (s == null) {
        Box(root, contentAlignment = Alignment.Center) {
            Text("Open WeatherTrack to load the forecast", style = text(13, Palette.muted))
        }
        return
    }
    Column(root) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NowBlock(s)
            Spacer(GlanceModifier.width(8.dp))
            TwoHourSteps(s, GlanceModifier.defaultWeight())
        }
        Spacer(GlanceModifier.height(4.dp))
        Badges(s)
        Spacer(GlanceModifier.height(4.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            nextDays(s).forEach { d -> DayCell(d, GlanceModifier.defaultWeight()) }
        }
    }
}

@Composable
private fun NowBlock(s: WidgetSnapshot) {
    val c = s.forecast.current
    val uvNow = s.forecast.hourly.firstOrNull { it.time + 3600 > s.nowEpoch }?.uvIndex
    Column(GlanceModifier.width(84.dp)) {
        Text(s.placeName, style = text(11, Palette.muted), maxLines = 1)
        Text(Format.temp(c?.temperature), style = text(30, bold = true))
        Text(WeatherCodes.describe(c?.weatherCode), style = text(11), maxLines = 1)
        Text("UV ${Format.uv(uvNow)} · ${Format.time(s.fetchedAtMs / 1000, s.zone)}", style = text(10, Palette.muted), maxLines = 1)
    }
}

/** Today in 2-hour steps: the next six even hours. */
@Composable
private fun TwoHourSteps(s: WidgetSnapshot, modifier: GlanceModifier) {
    val steps = s.forecast.hourly
        .filter { it.time + 3600 > s.nowEpoch }
        .filter { Instant.ofEpochSecond(it.time).atZone(s.zone).hour % 2 == 0 }
        .take(6)
    Row(modifier) {
        steps.forEach { h -> HourCell(h, s.zone, GlanceModifier.defaultWeight()) }
    }
}

@Composable
private fun HourCell(h: HourPoint, zone: ZoneId, modifier: GlanceModifier) {
    val wet = (h.precipitationProbability ?: 0.0) >= 40
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(Format.hour(h.time, zone).replace(" ", "").lowercase(), style = text(10, Palette.muted))
        Text(Format.temp(h.temperature), style = text(14, bold = true))
        Text(Format.pct(h.precipitationProbability), style = text(10, if (wet) Palette.blue else Palette.muted, bold = wet))
        Text("UV${Format.uv(h.uvIndex)}", style = text(9, Palette.muted))
    }
}

@Composable
private fun Badges(s: WidgetSnapshot) {
    Row(GlanceModifier.fillMaxWidth()) {
        val (jacketText, jacketBg, jacketFg) = when (s.jacket) {
            Verdict.YES -> Triple("Jacket", Palette.amber, Palette.onAmber)
            Verdict.MAYBE -> Triple("Maybe jacket", Palette.amberSoft, Palette.onAmber)
            Verdict.NO -> Triple("No jacket", Palette.iceSoft, Palette.blue)
        }
        Badge(jacketText, jacketBg, jacketFg)
        s.walk?.let { w ->
            Spacer(GlanceModifier.width(4.dp))
            val label = (if (w.window.name.startsWith("Morning")) "AM walk " else "PM walk ") + when (w.rating) {
                WalkRating.GOOD -> "good"
                WalkRating.FAIR -> "care"
                WalkRating.POOR -> "poor"
            }
            when (w.rating) {
                WalkRating.GOOD -> Badge(label, Color(0xFFCDEBD3), Color(0xFF0F3D1E))
                WalkRating.FAIR -> Badge(label, Palette.amberSoft, Palette.onAmber)
                WalkRating.POOR -> Badge(label, Palette.redSoft, Palette.red)
            }
        }
        s.tide?.let {
            Spacer(GlanceModifier.width(4.dp))
            Badge(it, Color(0xFFD7EEF2), Color(0xFF0B4F5C))
        }
        s.triggers.forEach { t ->
            Spacer(GlanceModifier.width(4.dp))
            when (t.kind) {
                TriggerKind.RAIN_SOON -> Badge(t.text, Palette.blue, Color.White)
                TriggerKind.TOO_HOT -> Badge("Hot ${Format.temp(t.value)}", Palette.redSoft, Palette.red)
                TriggerKind.TOO_COLD -> Badge("Cold ${Format.temp(t.value)}", Palette.iceSoft, Palette.blue)
            }
        }
    }
}

@Composable
private fun Badge(label: String, bg: Color, fg: Color) {
    Box(
        GlanceModifier.background(ColorProvider(bg)).cornerRadius(10.dp).padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = text(11, fg, bold = true), maxLines = 1)
    }
}

private fun nextDays(s: WidgetSnapshot): List<DayPoint> {
    val today = Instant.ofEpochSecond(s.nowEpoch).atZone(s.zone).toLocalDate().toString()
    return s.forecast.daily.filter { it.date > today }.take(3)
}

@Composable
private fun DayCell(d: DayPoint, modifier: GlanceModifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${Format.day(d.date).substringBefore(' ')} ${Format.temp(d.temperatureMax)}/${Format.temp(d.temperatureMin)}",
            style = text(11, bold = true), maxLines = 1,
        )
        Text("${Format.pct(d.precipitationProbabilityMax)} · UV${Format.uv(d.uvIndexMax)}", style = text(10, Palette.muted), maxLines = 1)
    }
}
