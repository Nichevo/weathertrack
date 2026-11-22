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
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
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

    /**
     * The step shown and a "frames version" live in Glance state. A widget's session stays alive for
     * a while after a tap, and later updates only recompose it, so anything read once up front would
     * go stale: that is why only the first tap used to change the picture.
     */
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Nothing drawn yet (just placed, or the last try failed): draw in the background.
        if (RainWidgetFrames.meta(context) == null) RainWidgetWorker.enqueue(context, force = false)
        provideContent {
            val prefs = currentState<Preferences>()
            val step = (prefs[STEP_KEY] ?: RainWidgetFrames.step(context)).coerceIn(0, STEPS - 1)
            val version = prefs[VERSION_KEY] ?: 0L
            val meta = remember(version) { RainWidgetFrames.meta(context) }
            val bitmap = remember(version, step) { meta?.let { RainWidgetFrames.bitmap(context, step) } }
            val error = remember(version) { RainWidgetFrames.lastError(context) }
            Content(meta, step, bitmap, error)
        }
    }

    companion object {
        const val STEPS = 5
        val STEP_KEY = intPreferencesKey("step")
        val VERSION_KEY = longPreferencesKey("frames_version")

        /** Tells every rain map widget that new frames (or an error) are stored, then redraws them. */
        suspend fun reloadAll(context: Context) {
            val v = System.currentTimeMillis()
            GlanceAppWidgetManager(context).getGlanceIds(RainMapWidget::class.java).forEach { id ->
                runCatching {
                    updateAppWidgetState(context, id) { it[VERSION_KEY] = v }
                    RainMapWidget().update(context, id)
                }.onFailure { Log.w("RainMapWidget", "Reload failed", it) }
            }
        }
    }
}

class RainMapWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RainMapWidget()
}

private val stepKey = ActionParameters.Key<Int>("step")

class SelectStepAction : ActionCallback {
    private companion object {
        @Volatile var lastTapStep = -1
        @Volatile var lastTapMs = 0L
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val step = (parameters[stepKey] ?: 0).coerceIn(0, RainMapWidget.STEPS - 1)
        // Ignore a double tap on the same button (within a second), so quick taps don't queue up redraws.
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (step == lastTapStep && nowMs - lastTapMs < 1000) return
        lastTapStep = step; lastTapMs = nowMs
        RainWidgetFrames.setStep(context, step)
        runCatching {
            // The step goes into this widget's own state, which its live session reads when it recomposes.
            updateAppWidgetState(context, glanceId) { it[RainMapWidget.STEP_KEY] = step }
            RainMapWidget().update(context, glanceId)
        }.onFailure { Log.w("RainMapWidget", "Step update failed", it) }
    }
}

class RefreshRainWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // Drawing needs the network and can take a while, longer than a tap handler may run, so it goes to WorkManager.
        RainWidgetFrames.setError(context, null)
        RainMapWidget.reloadAll(context)
        RainWidgetWorker.enqueue(context, force = true)
    }
}

/** Draws the widget's frames in the background, then refreshes every rain map widget. */
class RainWidgetWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val force = inputData.getBoolean("force", false)
        val placed = GlanceAppWidgetManager(applicationContext).getGlanceIds(RainMapWidget::class.java).isNotEmpty()
        if (!placed) return Result.success()
        val drew = RainWidgetFrames.render(applicationContext, force)
        RainMapWidget.reloadAll(applicationContext)
        return if (drew || RainWidgetFrames.meta(applicationContext) != null) Result.success()
        else if (runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context, force: Boolean) {
            val req = androidx.work.OneTimeWorkRequestBuilder<RainWidgetWorker>()
                .setInputData(androidx.work.workDataOf("force" to force))
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork("rain-widget-render", androidx.work.ExistingWorkPolicy.KEEP, req)
        }
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
private fun Content(meta: RainWidgetFrames.Meta?, step: Int, bitmap: Bitmap?, error: String?) {
    val root = GlanceModifier.fillMaxSize().background(ColorProvider(P.bg)).cornerRadius(24.dp)
    if (meta == null || bitmap == null) {
        Box(root.padding(16.dp).clickable(actionRunCallback<RefreshRainWidgetAction>()), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (error == null) "Drawing the rain map…" else "Couldn't draw the rain map", style = style(13, P.muted, bold = error != null))
                error?.let { Text(it, style = style(11, P.muted), maxLines = 2) }
                Text("Tap to try again", style = style(11, P.sel))
            }
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
            "Map © OpenStreetMap contributors · radar RainViewer · ${meta.placeName}, updated ${meta.updated}",
            style = style(8, P.muted), modifier = GlanceModifier.padding(start = 12.dp, bottom = 6.dp), maxLines = 1,
        )
    }
}

/** Draws and stores the widget's frames, and remembers which one is showing. */
object RainWidgetFrames {
    // Kept small: the picture travels to the home screen inside one update, which must stay well under 1 MB.
    private const val WIDTH = 480
    /** 200 world px × (480 / 280) = 342.9, so 342 leaves no unpainted row at the bottom. */
    private const val HEIGHT = 342
    /** Map area in world pixels at [ZOOM] (unchanged from before), drawn scaled to the frame. */
    private const val VIEW_W = 280.0
    private const val VIEW_H = 200.0
    private const val ZOOM = 10
    private const val MIN_INTERVAL_MS = 10 * 60_000L
    private val lock = Mutex()

    data class Step(val label: String, val time: String, val headline: String, val detail: String, val spot: String)
    data class Meta(val placeName: String, val updated: String, val renderedAt: Long, val placeId: Long, val steps: List<Step>)

    private fun dir(context: Context) = File(context.filesDir, "rainwidget").apply { mkdirs() }
    private fun prefs(context: Context) = context.getSharedPreferences("rain_widget", Context.MODE_PRIVATE)

    fun step(context: Context) = prefs(context).getInt("step", 0)
    fun lastError(context: Context): String? = prefs(context).getString("error", null)
    fun setError(context: Context, e: String?) = prefs(context).edit().putString("error", e).apply()
    fun setStep(context: Context, i: Int) = prefs(context).edit().putInt("step", i).apply()

    fun bitmap(context: Context, i: Int): Bitmap? =
        File(dir(context), "frame_$i.png").takeIf { it.exists() }?.let {
            BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }

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
        if (render(context, force = false)) RainMapWidget.reloadAll(context)
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
            // Radar and forecast rain come from different services; draw whatever arrives.
            val frames = runCatching { container.rainMap.radarFrames(trigger = "widget") }
                .onFailure { Log.w("RainMapWidget", "Radar frames failed", it) }.getOrNull()
            val grid = runCatching { container.rainMap.grid(place.latitude, place.longitude, halfWidthKm = 30.0, trigger = "widget") }
                .onFailure { Log.w("RainMapWidget", "Rain grid failed", it) }.getOrNull()
            if (frames == null && grid == null) error("No network for radar or forecast")
            val latest = frames?.past?.lastOrNull()
            val now = System.currentTimeMillis() / 1000
            val v = Viewport.at(place.latitude, place.longitude, ZOOM, VIEW_W, VIEW_H)
            val painter = MapPainter(container.tiles)
            val marker = place.latitude to place.longitude
            val steps = mutableListOf<Step>()
            for (i in 0 until RainMapWidget.STEPS) {
                val at = if (i == 0) now else ((now + i * 3600L) / 900) * 900
                val layers = if (i == 0) {
                    MapLayers(radarHost = frames?.host, radarFrame = latest, grid = grid, thunderAt = now, marker = marker)
                } else {
                    MapLayers(grid = grid, forecastAt = at, thunderAt = at, marker = marker)
                }
                runCatching { container.tiles.preload(painter.urlsFor(v, layers)) }
                // RGB_565 halves the size of what goes to the home screen; the map has no transparency.
                val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565)
                painter.draw(Canvas(bmp), v, (WIDTH / VIEW_W).toFloat(), layers)
                File(dir(context), "frame_$i.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                val outlook = grid?.let { RainOutlooks.at(it, place.latitude, place.longitude, at) }
                val rate = outlook?.rateHere
                val radarTime = if (i == 0 && latest != null) Format.time(latest.time, zone) else Format.time(at, zone)
                steps += Step(
                    label = if (i == 0) "Now" else "+$i h",
                    time = radarTime,
                    headline = outlook?.headline ?: "Rain map",
                    detail = if (i == 0) {
                        grid?.let { RainOutlooks.firstRain(it, place.latitude, place.longitude, now) }
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
            setError(context, null)
            true
        }.onFailure {
            Log.w("RainMapWidget", "Render failed", it)
            setError(context, it.message?.take(120) ?: it.javaClass.simpleName)
        }.getOrDefault(false)
    }
}
