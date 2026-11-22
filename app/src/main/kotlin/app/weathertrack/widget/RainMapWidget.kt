package app.weathertrack.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import app.weathertrack.core.map.RainBand
import app.weathertrack.core.map.RainOutlooks
import app.weathertrack.map.MapLayers
import app.weathertrack.map.MapPainter
import app.weathertrack.map.Viewport
import app.weathertrack.ui.Format
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 4x4 widget: a local map of the selected place, Now (latest radar) to +4 h (forecast rain).
 * Widgets can't animate, so each step is drawn ahead of time and a tap on a step shows it.
 */
class RainMapWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val meta = RainWidgetFrames.meta(context)
        val step = RainWidgetFrames.step(context).coerceIn(0, STEPS - 1)
        val bitmap = meta?.let { RainWidgetFrames.bitmap(context, step) }
        provideContent { Content(meta, step, bitmap) }
    }

    companion object {
        const val STEPS = 5
    }
}

class RainMapWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RainMapWidget()
}

private val stepKey = ActionParameters.Key<Int>("step")

class SelectStepAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RainWidgetFrames.setStep(context, parameters[stepKey] ?: 0)
        RainMapWidget().update(context, glanceId)
    }
}

class RefreshRainWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RainWidgetFrames.render(context, force = true)
        RainMapWidget().update(context, glanceId)
    }
}

private object P {
    val bg = Color(0xFFF8F9FF)
    val text = Color(0xFF191C20)
    val muted = Color(0xFF43474E)
    val chip = Color(0xFFECEEF4)
    val sel = Color(0xFF2F6295)
    val nowBadge = Color(0xFFD1E4FF)
    val onNow = Color(0xFF001D36)
    val fcBadge = Color(0xFFFFDCC2)
    val onFc = Color(0xFF2E1500)
}

private fun style(size: Int, color: Color = P.text, bold: Boolean = false) =
    TextStyle(fontSize = size.sp, color = ColorProvider(color), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable
private fun Content(meta: RainWidgetFrames.Meta?, step: Int, bitmap: Bitmap?) {
    val root = GlanceModifier.fillMaxSize().background(ColorProvider(P.bg)).cornerRadius(24.dp)
    if (meta == null || bitmap == null) {
        Box(root.padding(16.dp).clickable(actionRunCallback<RefreshRainWidgetAction>()), contentAlignment = Alignment.Center) {
            Text("Loading the rain map… tap to retry", style = style(13, P.muted))
        }
        return
    }
    val s = meta.steps[step]
    Column(root) {
        Row(
            GlanceModifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)
                .clickable(actionRunCallback<RefreshRainWidgetAction>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(GlanceModifier.defaultWeight()) {
                Text(s.headline, style = style(14, bold = true), maxLines = 1)
                Text(s.detail, style = style(11, P.muted), maxLines = 1)
            }
            Spacer(GlanceModifier.width(6.dp))
            Box(
                GlanceModifier.background(ColorProvider(if (step == 0) P.nowBadge else P.fcBadge)).cornerRadius(12.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(s.time, style = style(11, if (step == 0) P.onNow else P.onFc, bold = true))
            }
        }
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Rain map, ${s.label}",
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(horizontal = 8.dp).cornerRadius(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
        )
        Row(GlanceModifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
            meta.steps.forEachIndexed { i, st ->
                val selected = i == step
                Box(GlanceModifier.defaultWeight().padding(horizontal = 2.dp)) {
                    Column(
                        GlanceModifier.fillMaxWidth().height(42.dp).cornerRadius(12.dp)
                            .background(ColorProvider(if (selected) P.sel else P.chip))
                            .clickable(actionRunCallback<SelectStepAction>(actionParametersOf(stepKey to i))),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(st.label, style = style(12, if (selected) Color.White else P.text, bold = true))
                        Text(st.spot, style = style(10, if (selected) Color.White else P.muted))
                    }
                }
            }
        }
        Text(
            "Map © OpenStreetMap, CARTO · radar RainViewer · ${meta.placeName}, updated ${meta.updated}",
            style = style(8, P.muted), modifier = GlanceModifier.padding(start = 12.dp, bottom = 6.dp), maxLines = 1,
        )
    }
}

/** Draws and stores the widget's frames, and remembers which one is showing. */
object RainWidgetFrames {
    private const val WIDTH = 720
    private const val HEIGHT = 520
    private const val ZOOM = 10
    private const val MIN_INTERVAL_MS = 10 * 60_000L
    private val lock = Mutex()

    data class Step(val label: String, val time: String, val headline: String, val detail: String, val spot: String)
    data class Meta(val placeName: String, val updated: String, val renderedAt: Long, val placeId: Long, val steps: List<Step>)

    private fun dir(context: Context) = File(context.filesDir, "rainwidget").apply { mkdirs() }
    private fun prefs(context: Context) = context.getSharedPreferences("rain_widget", Context.MODE_PRIVATE)

    fun step(context: Context) = prefs(context).getInt("step", 0)
    fun setStep(context: Context, i: Int) = prefs(context).edit().putInt("step", i).apply()

    fun bitmap(context: Context, i: Int): Bitmap? =
        File(dir(context), "frame_$i.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun meta(context: Context): Meta? = runCatching {
        val o = JSONObject(File(dir(context), "meta.json").readText())
        val steps = o.getJSONArray("steps").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Step(it.getString("label"), it.getString("time"), it.getString("headline"), it.getString("detail"), it.getString("spot"))
            }
        }
        Meta(o.getString("place"), o.getString("updated"), o.getLong("renderedAt"), o.optLong("placeId", -1), steps)
    }.getOrNull()

    /** Redraws all widgets' frames if any rain-map widget is placed. Safe to call often: it throttles itself. */
    suspend fun refreshIfPlaced(context: Context) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(RainMapWidget::class.java)
        if (ids.isEmpty()) return
        if (render(context, force = false)) RainMapWidget().updateAll(context)
    }

    /** Draws the 5 frames for the selected place. Returns true if new frames were written. */
    suspend fun render(context: Context, force: Boolean): Boolean = lock.withLock {
        val container = (context.applicationContext as WeatherApp).container
        container.places.ensureDefaults()
        val place = container.places.selected() ?: return@withLock false
        val old = meta(context)
        if (!force && old != null && old.placeId == place.id && System.currentTimeMillis() - old.renderedAt < MIN_INTERVAL_MS) {
            return@withLock false
        }
        runCatching {
            val zone = Format.zone(place.timezone)
            val frames = container.rainMap.radarFrames(trigger = "widget")
            val grid = container.rainMap.grid(place.latitude, place.longitude, halfWidthKm = 30.0, trigger = "widget")
            val latest = frames.past.lastOrNull()
            val now = System.currentTimeMillis() / 1000
            val v = Viewport.at(place.latitude, place.longitude, ZOOM, WIDTH / 2.0, HEIGHT / 2.0)
            val painter = MapPainter(container.tiles)
            val marker = place.latitude to place.longitude
            val steps = mutableListOf<Step>()
            for (i in 0 until RainMapWidget.STEPS) {
                val at = if (i == 0) now else ((now + i * 3600L) / 900) * 900
                val layers = if (i == 0) {
                    MapLayers(radarHost = frames.host, radarFrame = latest, grid = grid, thunderAt = now, marker = marker)
                } else {
                    MapLayers(grid = grid, forecastAt = at, thunderAt = at, marker = marker)
                }
                container.tiles.preload(painter.urlsFor(v, layers))
                val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                painter.draw(Canvas(bmp), v, 2f, layers)
                File(dir(context), "frame_$i.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                val outlook = RainOutlooks.at(grid, place.latitude, place.longitude, at)
                val rate = outlook?.rateHere
                val radarTime = if (i == 0 && latest != null) Format.time(latest.time, zone) else Format.time(at, zone)
                steps += Step(
                    label = if (i == 0) "Now" else "+$i h",
                    time = radarTime,
                    headline = outlook?.headline ?: "Rain map",
                    detail = if (i == 0) {
                        RainOutlooks.firstRain(grid, place.latitude, place.longitude, now)
                            ?.takeIf { RainBand.of(rate) == null }
                            ?.let { "Rain at your spot from about ${Format.time(it, zone)}" } ?: (outlook?.detail ?: "")
                    } else outlook?.detail ?: "",
                    spot = if (RainBand.of(rate) == null) "dry" else String.format(Locale.US, "%.1f mm", rate),
                )
            }
            val o = JSONObject()
                .put("place", place.name)
                .put("placeId", place.id)
                .put("updated", Format.time(now, zone))
                .put("renderedAt", System.currentTimeMillis())
                .put("steps", JSONArray(steps.map {
                    JSONObject().put("label", it.label).put("time", it.time).put("headline", it.headline)
                        .put("detail", it.detail).put("spot", it.spot)
                }))
            File(dir(context), "meta.json").writeText(o.toString())
            if (old?.placeId != place.id) setStep(context, 0)
            true
        }.onFailure { Log.w("RainMapWidget", "Render failed", it) }.getOrDefault(false)
    }
}
