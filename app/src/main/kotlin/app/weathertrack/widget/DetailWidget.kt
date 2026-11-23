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
    /** The second clock chosen in Settings: place name and its time zone. */
    val clock: Pair<String, ZoneId>? = null,
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
    /**
     * Every size but the very smallest shows the 2-hour steps: below "now" whenever there is the
     * height (compact under [ROOMY_H]), otherwise beside it when the widget is wide enough. An unfolded
     * phone gives the widget different cells from the folded one, so the in-between sizes matter.
     * The launcher picks the largest of these that fits.
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 100.dp), DpSize(180.dp, 150.dp), DpSize(180.dp, 180.dp),
            DpSize(290.dp, 100.dp), DpSize(290.dp, 150.dp), DpSize(290.dp, 180.dp),
            DpSize(400.dp, 100.dp), DpSize(400.dp, 150.dp), DpSize(400.dp, 180.dp)),
    )

    companion object {
        /** From this width the hours fit beside "now". */
        val SIDE_BY_SIDE_W = 290.dp
        /** From this height the hours fit below "now", compact; from [ROOMY_H] at full size. */
        val STACK_H = 150.dp
        val ROOMY_H = 180.dp
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
            tide = runCatching { container.extras.tides(place, "widget", container.settings.current.tidePort) }.getOrNull()
                ?.let { app.weathertrack.core.tides.Tides.now(it, now) }?.next
                ?.let { (if (it.high) "High " else "Low ") + Format.time(it.time, zone).replace(" ", "").lowercase() },
            clock = container.places.all().firstOrNull { it.id == cfg.clockPlaceId }?.let { it.name to Format.zone(it.timezone) },
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
        .padding(horizontal = 14.dp, vertical = 8.dp)
        .clickable(actionStartActivity<MainActivity>())
    if (s == null) {
        Box(root, contentAlignment = Alignment.Center) {
            Text("Open WeatherTrack to load the forecast", style = text(13, Palette.muted))
        }
        return
    }
    val size = LocalSize.current
    val tall = size.height >= DetailWidget.STACK_H
    val roomy = size.height >= DetailWidget.ROOMY_H
    val wide = size.width >= DetailWidget.SIDE_BY_SIDE_W
    Column(root) {
        if (wide && !tall) {
            // Wide but short (an unfolded phone): now on the left, the next hours on the right.
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    NowRow(s)
                    Spacer(GlanceModifier.height(4.dp))
                    Badges(s)
                }
                Spacer(GlanceModifier.width(8.dp))
                TwoHourSteps(s, GlanceModifier.defaultWeight(), compact = true, count = if (size.width >= 400.dp) 6 else 4)
            }
        } else {
            NowRow(s)
            Spacer(GlanceModifier.height(4.dp))
            Badges(s)
            if (roomy) {
                // A tall widget (a folded phone's cover screen) splits the spare height around the hours
                // instead of leaving one empty band above the days.
                Spacer(GlanceModifier.defaultWeight())
                TwoHourSteps(s, GlanceModifier.fillMaxWidth())
            } else if (tall) {
                // Not much height: one line of times and temperatures (rain chance when it's likely).
                Spacer(GlanceModifier.height(4.dp))
                MiniHours(s, if (wide) 6 else 4)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        // The next four days in two rows of two, so each can use bigger text; bigger still when the
        // widget is stacked and tall, to match the unfolded look.
        val days = nextDays(s)
        val bigDays = roomy && !(wide && !tall)
        Column(GlanceModifier.fillMaxWidth().background(ColorProvider(Palette.chip)).cornerRadius(14.dp)
            .padding(horizontal = 8.dp, vertical = if (bigDays) 4.dp else 2.dp)) {
            days.chunked(2).forEach { pair ->
                Row(GlanceModifier.fillMaxWidth()) {
                    pair.forEachIndexed { i, d ->
                        if (i > 0) Spacer(GlanceModifier.width(12.dp))
                        DayCell(d, GlanceModifier.defaultWeight(), big = bigDays)
                    }
                    if (pair.size == 1) Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun NowRow(s: WidgetSnapshot) {
    val c = s.forecast.current
    val today = s.forecast.daily.firstOrNull { it.date == Instant.ofEpochSecond(s.nowEpoch).atZone(s.zone).toLocalDate().toString() }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(iconFor(c?.weatherCode, c?.isDay != false)), contentDescription = WeatherCodes.describe(c?.weatherCode),
            modifier = GlanceModifier.size(38.dp))
        Spacer(GlanceModifier.width(6.dp))
        Text(Format.temp(c?.temperature), style = text(36, bold = true))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(WeatherCodes.describe(c?.weatherCode), style = text(15, bold = true), maxLines = 1)
                // Wind: arrow pointing downwind, where it comes from, and speed.
                val dir = c?.windDirection ?: s.forecast.hourly.firstOrNull { it.time + 3600 > s.nowEpoch }?.windDirection
                val W = app.weathertrack.core.wind.Wind
                Text("  ${W.arrow(dir) ?: ""}${W.compass8(dir) ?: ""} ${c?.windSpeed?.let { kotlin.math.round(it).toInt() } ?: "–"}",
                    style = text(12, Palette.muted, bold = true), maxLines = 1)
            }
            Text("Feels ${Format.temp(c?.apparentTemperature)} · ${Format.temp(today?.temperatureMax)}/${Format.temp(today?.temperatureMin)}",
                style = text(12, Palette.muted), maxLines = 1)
        }
        // Each line ends in a bold time, so the update time and the second clock line up on the right.
        Column(horizontalAlignment = Alignment.End) {
            Text(s.placeName, style = text(13, bold = true), maxLines = 1)
            val uvNow = s.forecast.hourly.firstOrNull { it.time + 3600 > s.nowEpoch }?.uvIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("UV ${Format.uv(uvNow)} · ", style = text(11, Palette.faint), maxLines = 1)
                Text(Format.time(s.fetchedAtMs / 1000, s.zone), style = text(11, bold = true), maxLines = 1)
            }
            s.clock?.let { (name, zone) -> SecondClock(name, zone) }
        }
    }
}

/** The next six even hours. */
@Composable
private fun TwoHourSteps(s: WidgetSnapshot, modifier: GlanceModifier, compact: Boolean = false, count: Int = 6) {
    val steps = s.forecast.hourly
        .filter { it.time + 3600 > s.nowEpoch }
        .filter { Instant.ofEpochSecond(it.time).atZone(s.zone).hour % 2 == 0 }
        .take(count)
    Row(modifier) {
        steps.forEach { h -> HourCell(h, s.zone, GlanceModifier.defaultWeight(), compact) }
    }
}

@Composable
private fun HourCell(h: HourPoint, zone: ZoneId, modifier: GlanceModifier, compact: Boolean = false) {
    val chance = h.precipitationProbability ?: 0.0
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(Format.hour(h.time, zone).replace(" ", "").lowercase(), style = text(if (compact) 10 else 12, Palette.faint), maxLines = 1)
        Image(ImageProvider(iconFor(h.weatherCode, h.isDay != false)), contentDescription = null,
            modifier = GlanceModifier.size(if (compact) 14.dp else 15.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.temp(h.temperature), style = text(if (compact) 13 else 16, bold = true), maxLines = 1)
            Spacer(GlanceModifier.width(2.dp))
            // Rain chance on every hour: faint when low, blue from 20%, bold from 40%.
            Text(Format.pct(h.precipitationProbability),
                style = text(if (compact) 9 else 11, if (chance >= 20) Palette.rain else Palette.faint, bold = chance >= 40), maxLines = 1)
        }
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
        Text(label, style = text(12, fg, bold = true), maxLines = 1)
    }
}

private fun nextDays(s: WidgetSnapshot): List<DayPoint> {
    val today = Instant.ofEpochSecond(s.nowEpoch).atZone(s.zone).toLocalDate().toString()
    return s.forecast.daily.filter { it.date > today }.take(4)
}

@Composable
private fun DayCell(d: DayPoint, modifier: GlanceModifier, big: Boolean = false) {
    val wet = (d.precipitationProbabilityMax ?: 0.0) >= 40
    val up = if (big) 2 else 0
    Row(modifier.padding(vertical = if (big) 4.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Format.day(d.date).substringBefore(' '), style = text(13 + up, Palette.muted, bold = true), maxLines = 1)
        Spacer(GlanceModifier.width(6.dp))
        Image(ImageProvider(iconFor(d.weatherCode, true)), contentDescription = WeatherCodes.describe(d.weatherCode),
            modifier = GlanceModifier.size(if (big) 21.dp else 17.dp))
        Spacer(GlanceModifier.width(4.dp))
        Text("${Format.temp(d.temperatureMax)}/${Format.temp(d.temperatureMin)}", style = text(14 + up, bold = true), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        Text(Format.pct(d.precipitationProbabilityMax), style = text(12 + up, if (wet) Palette.rain else Palette.faint, bold = wet), maxLines = 1)
    }
}

/** The second clock: the place, its live time (a TextClock, so it keeps time between updates) and the offset. */
@Composable
private fun SecondClock(name: String, zone: ZoneId) {
    val context = androidx.glance.LocalContext.current
    val now = Instant.now()
    val diffS = zone.rules.getOffset(now).totalSeconds - ZoneId.systemDefault().rules.getOffset(now).totalSeconds
    val h = kotlin.math.abs(diffS) / 3600.0
    val hText = if (h % 1.0 == 0.0) "${h.toInt()}h" else String.format(java.util.Locale.US, "%.1fh", h)
    val offset = when { diffS > 0 -> "+$hText"; diffS < 0 -> "−$hText"; else -> "same" }
    val views = android.widget.RemoteViews(context.packageName, R.layout.widget_second_clock).apply {
        setTextViewText(R.id.second_clock_name, name)
        setString(R.id.second_clock_time, "setTimeZone", zone.id)
        setTextViewText(R.id.second_clock_offset, "$offset · ")
    }
    androidx.glance.appwidget.AndroidRemoteViews(views)
}

/** One line of the next even hours, for widgets with little height. */
@Composable
private fun MiniHours(s: WidgetSnapshot, count: Int) {
    val steps = s.forecast.hourly
        .filter { it.time + 3600 > s.nowEpoch }
        .filter { Instant.ofEpochSecond(it.time).atZone(s.zone).hour % 2 == 0 }
        .take(count)
    Row(GlanceModifier.fillMaxWidth()) {
        steps.forEach { h ->
            val wet = (h.precipitationProbability ?: 0.0) >= 40
            Row(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.hour(h.time, s.zone).replace(" ", "").lowercase() + " ", style = text(10, Palette.faint), maxLines = 1)
                Text(if (wet) Format.pct(h.precipitationProbability) else Format.temp(h.temperature),
                    style = text(11, if (wet) Palette.rain else Palette.text, bold = true), maxLines = 1)
            }
        }
    }
}
