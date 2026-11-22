package app.weathertrack.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.compose.ui.unit.DpSize
import androidx.glance.layout.size
import app.weathertrack.R
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
    val text = Color.White
    val muted = Color(0xD9FFFFFF)
    val faint = Color(0xB3FFFFFF)
    val chip = Color(0x29FFFFFF)
    val rain = Color(0xFFB3E5FC)
    val amber = Color(0xFFFFC94D)
    val onAmber = Color(0xFF3D2A00)
    val redSoft = Color(0xFFFFDAD6)
    val red = Color(0xFF8C1D18)
    val greenSoft = Color(0xFFCDEBD3)
    val green = Color(0xFF0F3D1E)
    val teal = Color(0xFFD7EEF2)
    val onTeal = Color(0xFF0B4F5C)
}

/** A drawn weather icon for a WMO code, day or night. */
private fun iconFor(code: Int?, isDay: Boolean): Int = when (code) {
    null -> R.drawable.ic_w_cloud
    0, 1 -> if (isDay) R.drawable.ic_w_sun else R.drawable.ic_w_moon
    2 -> if (isDay) R.drawable.ic_w_partly else R.drawable.ic_w_partly_night
    3 -> R.drawable.ic_w_cloud
    45, 48 -> R.drawable.ic_w_fog
    in 71..77, 85, 86 -> R.drawable.ic_w_snow
    in 95..99 -> R.drawable.ic_w_storm
    else -> if (code >= 51) R.drawable.ic_w_rain else R.drawable.ic_w_cloud
}

class DetailWidget : GlanceAppWidget() {
    /** Short (4x2) shows now, badges and the next days; taller adds the 2-hour steps. */
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SHORT, TALL))

    companion object {
        private val SHORT = DpSize(250.dp, 110.dp)
        val TALL = DpSize(250.dp, 210.dp)
    }

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
    val c = s?.forecast?.current
    val bg = when {
        c == null -> R.drawable.widget_bg_day
        WeatherCodes.isWet(c.weatherCode) -> R.drawable.widget_bg_wet
        c.isDay == false -> R.drawable.widget_bg_night
        else -> R.drawable.widget_bg_day
    }
    val root = GlanceModifier.fillMaxSize()
        .background(ImageProvider(bg))
        .cornerRadius(24.dp)
        .padding(horizontal = 14.dp, vertical = 10.dp)
        .clickable(actionStartActivity<MainActivity>())
    if (s == null) {
        Box(root, contentAlignment = Alignment.Center) {
            Text("Open WeatherTrack to load the forecast", style = text(13, Palette.muted))
        }
        return
    }
    val tall = LocalSize.current.height >= DetailWidget.TALL.height
    Column(root) {
        NowRow(s)
        Spacer(GlanceModifier.height(6.dp))
        Badges(s)
        if (tall) {
            Spacer(GlanceModifier.height(6.dp))
            TwoHourSteps(s, GlanceModifier.fillMaxWidth())
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth().background(ColorProvider(Palette.chip)).cornerRadius(14.dp).padding(vertical = 4.dp)) {
            nextDays(s).forEach { d -> DayCell(d, GlanceModifier.defaultWeight()) }
        }
    }
}

@Composable
private fun NowRow(s: WidgetSnapshot) {
    val c = s.forecast.current
    val today = s.forecast.daily.firstOrNull { it.date == Instant.ofEpochSecond(s.nowEpoch).atZone(s.zone).toLocalDate().toString() }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(iconFor(c?.weatherCode, c?.isDay != false)), contentDescription = WeatherCodes.describe(c?.weatherCode),
            modifier = GlanceModifier.size(40.dp))
        Spacer(GlanceModifier.width(8.dp))
        Text(Format.temp(c?.temperature), style = text(32, bold = true))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(WeatherCodes.describe(c?.weatherCode), style = text(13, bold = true), maxLines = 1)
            Text("Feels ${Format.temp(c?.apparentTemperature)} · ${Format.temp(today?.temperatureMax)}/${Format.temp(today?.temperatureMin)}",
                style = text(11, Palette.muted), maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(s.placeName, style = text(12, bold = true), maxLines = 1)
            val uvNow = s.forecast.hourly.firstOrNull { it.time + 3600 > s.nowEpoch }?.uvIndex
            Text("UV ${Format.uv(uvNow)} · ${Format.time(s.fetchedAtMs / 1000, s.zone)}", style = text(10, Palette.faint), maxLines = 1)
        }
    }
}

/** The next six even hours. */
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
        Text(Format.hour(h.time, zone).replace(" ", "").lowercase(), style = text(10, Palette.faint))
        Image(ImageProvider(iconFor(h.weatherCode, h.isDay != false)), contentDescription = null, modifier = GlanceModifier.size(20.dp))
        Text(Format.temp(h.temperature), style = text(13, bold = true))
        Text(Format.pct(h.precipitationProbability), style = text(10, if (wet) Palette.rain else Palette.faint, bold = wet))
    }
}

@Composable
private fun Badges(s: WidgetSnapshot) {
    Row(GlanceModifier.fillMaxWidth()) {
        val (jacketText, jacketBg, jacketFg) = when (s.jacket) {
            Verdict.YES -> Triple("Jacket", Palette.amber, Palette.onAmber)
            Verdict.MAYBE -> Triple("Maybe jacket", Color(0xFFFFE3A3), Palette.onAmber)
            Verdict.NO -> Triple("No jacket", Palette.chip, Palette.text)
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
                WalkRating.GOOD -> Badge(label, Palette.greenSoft, Palette.green)
                WalkRating.FAIR -> Badge(label, Palette.amber, Palette.onAmber)
                WalkRating.POOR -> Badge(label, Palette.redSoft, Palette.red)
            }
        }
        s.tide?.let {
            Spacer(GlanceModifier.width(4.dp))
            Badge(it, Palette.teal, Palette.onTeal)
        }
        s.triggers.forEach { t ->
            Spacer(GlanceModifier.width(4.dp))
            when (t.kind) {
                TriggerKind.RAIN_SOON -> Badge(t.text, Palette.rain, Color(0xFF01579B))
                TriggerKind.TOO_HOT -> Badge("Hot ${Format.temp(t.value)}", Palette.redSoft, Palette.red)
                TriggerKind.TOO_COLD -> Badge("Cold ${Format.temp(t.value)}", Palette.chip, Palette.text)
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
    return s.forecast.daily.filter { it.date > today }.take(4)
}

@Composable
private fun DayCell(d: DayPoint, modifier: GlanceModifier) {
    val wet = (d.precipitationProbabilityMax ?: 0.0) >= 40
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(Format.day(d.date).substringBefore(' '), style = text(11, Palette.muted, bold = true), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(iconFor(d.weatherCode, true)), contentDescription = WeatherCodes.describe(d.weatherCode),
                modifier = GlanceModifier.size(20.dp))
            Spacer(GlanceModifier.width(3.dp))
            Text("${Format.temp(d.temperatureMax)}/${Format.temp(d.temperatureMin)}", style = text(11, bold = true), maxLines = 1)
        }
        Text(Format.pct(d.precipitationProbabilityMax), style = text(10, if (wet) Palette.rain else Palette.faint, bold = wet), maxLines = 1)
    }
}
