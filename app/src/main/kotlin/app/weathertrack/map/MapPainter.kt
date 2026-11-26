package app.weathertrack.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.graphics.Typeface
import app.weathertrack.core.map.ColorScale
import app.weathertrack.core.map.Contours
import app.weathertrack.core.map.Gibs
import app.weathertrack.core.map.LayerScales
import app.weathertrack.core.map.RainBand
import app.weathertrack.core.map.RainGrid
import app.weathertrack.core.map.RainViewer
import app.weathertrack.core.map.TileMath
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** What part of the world to draw: centre in world pixels at [zoom], size in world pixels. */
data class Viewport(val cx: Double, val cy: Double, val zoom: Int, val width: Double, val height: Double) {
    val centerLat get() = TileMath.lat(cy, zoom)
    val centerLon get() = TileMath.lon(cx, zoom)
    val left get() = cx - width / 2
    val top get() = cy - height / 2

    fun zoomedTo(z: Int): Viewport {
        val f = 2.0.pow(z - zoom)
        return Viewport(cx * f, cy * f, z, width, height)
    }

    companion object {
        fun at(lat: Double, lon: Double, zoom: Int, width: Double, height: Double) =
            Viewport(TileMath.x(lon, zoom), TileMath.y(lat, zoom), zoom, width, height)
    }
}

/** What to draw on top of the base map. */
data class MapLayers(
    val radarHost: String? = null,
    val radarFrame: RainViewer.Frame? = null,
    val grid: RainGrid? = null,
    /** Epoch seconds of the forecast step to draw from [grid]; null draws no forecast rain. */
    val forecastAt: Long? = null,
    /** Epoch seconds whose hour's thunderstorm points to draw; null draws none. */
    val thunderAt: Long? = null,
    val marker: Pair<Double, Double>? = null,
    /** A weather layer to colour from [grid]'s hourly values. */
    val field: FieldOverlay? = null,
    /** Epoch seconds of the satellite image to draw under everything else; null draws none. */
    val satelliteTime: Long? = null,
    /** Wide pressure grid for isobars, highs and lows at [pressureAt]. */
    val pressure: RainGrid? = null,
    val pressureAt: Long? = null,
    val cyclones: List<RainMapRepository.Cyclone> = emptyList(),
    /** Time zone boundaries and clocks, drawn over the base map. */
    val timeZones: TimeZoneOverlay? = null,
)

/** Which hourly value a weather layer colours, its colour scale and how a value reads on the map. */
enum class FieldKind(val varName: String, val scale: ColorScale) {
    CLOUD("cloud_cover", LayerScales.CLOUD),
    CLOUD_LOW("cloud_cover_low", LayerScales.CLOUD),
    CLOUD_MID("cloud_cover_mid", LayerScales.CLOUD),
    CLOUD_HIGH("cloud_cover_high", LayerScales.CLOUD),
    WIND("wind_speed_10m", LayerScales.WIND),
    GUSTS("wind_gusts_10m", LayerScales.WIND),
    TEMP("temperature_2m", LayerScales.TEMP),
    FEELS("apparent_temperature", LayerScales.TEMP),
    UV("uv_index", LayerScales.UV),
    HUMIDITY("relative_humidity_2m", LayerScales.HUMIDITY),
    VISIBILITY("visibility", LayerScales.VISIBILITY);

    val isWind get() = this == WIND || this == GUSTS

    /** How a value reads on the map, such as "18°", "45%" or "0.8 km". */
    fun label(v: Double): String = when (this) {
        CLOUD, CLOUD_LOW, CLOUD_MID, CLOUD_HIGH, HUMIDITY -> "${v.roundToInt()}%"
        WIND, GUSTS -> "${v.roundToInt()}"
        TEMP, FEELS -> "${v.roundToInt()}°"
        UV -> "${v.roundToInt()}"
        VISIBILITY -> if (v < 9950) String.format(java.util.Locale.US, "%.1f km", v / 1000) else "${(v / 1000).roundToInt()} km"
    }
}

/** Colour [kind] for the hour containing [at]; [labels] writes the value at each grid point. */
data class FieldOverlay(val kind: FieldKind, val at: Long, val labels: Boolean = true)

/** Draws the base map, radar or forecast rain, thunder and a position marker onto any Canvas. */
class MapPainter(private val tiles: TileStore) {
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** The base map, softened (less colour, a little lighter) so rain stands out on top. */
    private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Faded and nearly grey, so rain stands out on top.
        val m = ColorMatrix().apply { setSaturation(0.15f) }
        m.postConcat(ColorMatrix(floatArrayOf(0.62f, 0f, 0f, 0f, 92f, 0f, 0.62f, 0f, 0f, 92f, 0f, 0f, 0.62f, 0f, 96f, 0f, 0f, 0f, 1f, 0f)))
        colorFilter = ColorMatrixColorFilter(m)
    }
    /** Radar tiles darkened and made more solid: RainViewer's free blue scheme is pale on a light map. */
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.45f, 0f, 0f, 0f, 0f,
            0f, 0.55f, 0f, 0f, 0f,
            0f, 0f, 0.85f, 0f, 0f,
            0f, 0f, 0f, 1.6f, 0f,
        )))
    }
    private val bg = Paint().apply { color = Color.rgb(0xDC, 0xE6, 0xF1) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2F, 0x62, 0x95) }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(64, 0x2F, 0x62, 0x95) }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val boltFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xF3, 0xD3, 0x5B) }
    private val boltStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x2E, 0x23, 0x00); style = Paint.Style.STROKE; strokeWidth = 1.2f; strokeJoin = Paint.Join.ROUND
    }
    private var overlayKey: Triple<RainGrid, Long, Int>? = null
    private var overlay: Bitmap? = null
    private var fieldKey: List<Any>? = null
    private var fieldBmp: Bitmap? = null
    private val satPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 225 }
    private val labelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x1B, 0x1B, 0x21); textSize = 11f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val labelHalo = Paint(labelFill).apply { color = Color.argb(220, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0x28, 0x2D, 0x3C); strokeWidth = 1.4f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val arrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 0x28, 0x2D, 0x3C) }
    private val isobar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f; strokeCap = Paint.Cap.ROUND }
    private val centreText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val centreHalo = Paint(labelHalo).apply { textSize = 22f }
    private val centreSmall = Paint(labelFill).apply { textSize = 10f }
    private val coneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 0xD9, 0x53, 0x4A) }
    private val coneEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0xD9, 0x53, 0x4A); style = Paint.Style.STROKE; strokeWidth = 1.2f; pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
    }
    private val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f; strokeCap = Paint.Cap.ROUND }
    private val legDash = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    private val stormFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xC0, 0x39, 0x2B) }
    private val stormRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val timeZonePainter = TimeZonePainter()

    /** Tile URLs [draw] needs for [v] and [layers], to preload before drawing off screen. */
    fun urlsFor(v: Viewport, layers: MapLayers): List<String> {
        val base = baseTiles(v).map { it.second }
        val radar = radarTiles(v, layers)?.map { it.second } ?: emptyList()
        val sat = satelliteTiles(v, layers)?.map { it.second } ?: emptyList()
        return base + sat + radar
    }

    /** Draws [v] into [canvas], where one world pixel is [scale] canvas pixels. */
    fun draw(canvas: Canvas, v: Viewport, scale: Float, layers: MapLayers) {
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate((-v.left).toFloat(), (-v.top).toFloat())
        canvas.drawRect(v.left.toFloat(), v.top.toFloat(), (v.left + v.width).toFloat(), (v.top + v.height).toFloat(), bg)

        for ((rect, url) in baseTiles(v)) {
            val bmp = tiles.peek(url) ?: continue
            canvas.drawBitmap(bmp, null, rect, basePaint)
        }

        satelliteTiles(v, layers)?.forEach { (rect, url) -> tiles.peek(url)?.let { canvas.drawBitmap(it, null, rect, satPaint) } }
        layers.timeZones?.let { timeZonePainter.draw(canvas, v, it) }

        val grid = layers.grid
        val field = layers.field
        val fieldHour = if (grid != null && field != null) grid.hourIndex(field.at) ?: grid.nearestHour(field.at) else null
        if (grid != null && field != null && fieldHour != null) {
            val bmp = fieldOverlay(grid, field.kind, fieldHour, v.zoom)
            canvas.drawBitmap(bmp, null, gridRect(grid, v.zoom), tilePaint)
        }

        radarTiles(v, layers)?.forEach { (rect, url) -> tiles.peek(url)?.let { canvas.drawBitmap(it, null, rect, radarPaint) } }

        if (grid != null && layers.forecastAt != null) {
            grid.timeIndex(layers.forecastAt)?.let { t ->
                val bmp = forecastOverlay(grid, t, v.zoom)
                val l = TileMath.x(grid.west, v.zoom).toFloat()
                val r = TileMath.x(grid.east, v.zoom).toFloat()
                val tp = TileMath.y(grid.north, v.zoom).toFloat()
                val b = TileMath.y(grid.south, v.zoom).toFloat()
                canvas.drawBitmap(bmp, null, RectF(l, tp, r, b), tilePaint)
            }
        }
        if (grid != null && field != null && fieldHour != null) {
            if (field.kind.isWind) drawWindArrows(canvas, v, grid, fieldHour)
            if (field.labels) drawLabels(canvas, v, grid, field.kind, fieldHour)
        }
        val pressure = layers.pressure
        if (pressure != null && layers.pressureAt != null) {
            pressure.nearestHour(layers.pressureAt)?.let { drawIsobars(canvas, v, pressure, it, onSatellite = layers.satelliteTime != null) }
        }
        if (grid != null && layers.thunderAt != null) {
            for ((lat, lon) in grid.thunderPoints(layers.thunderAt)) {
                drawBolt(canvas, TileMath.x(lon, v.zoom).toFloat(), TileMath.y(lat, v.zoom).toFloat())
            }
        }
        layers.cyclones.forEach { drawCyclone(canvas, v, it) }
        layers.marker?.let { (lat, lon) ->
            val x = TileMath.x(lon, v.zoom).toFloat()
            val y = TileMath.y(lat, v.zoom).toFloat()
            canvas.drawCircle(x, y, 11f, halo)
            canvas.drawCircle(x, y, 6.5f, white)
            canvas.drawCircle(x, y, 5f, dot)
        }
        canvas.restore()
    }

    /**
     * Base tiles one zoom level deeper than the view, each drawn at half size, so 256-pixel tiles
     * stay sharp on high-density screens (one world pixel is about 2.6 screen pixels).
     */
    private fun baseTiles(v: Viewport): List<Pair<RectF, String>> {
        val bz = minOf(v.zoom + 1, TileStore.BASE_MAX_ZOOM)
        val f = 2.0.pow(bz - v.zoom)
        val size = (TileMath.TILE / f).toFloat()
        return TileMath.tilesFor(v.cx * f, v.cy * f, bz, v.width * f, v.height * f).map { t ->
            val x = (t.x * TileMath.TILE / f).toFloat()
            val y = (t.y * TileMath.TILE / f).toFloat()
            RectF(x, y, x + size, y + size) to TileStore.baseUrl(bz, TileMath.wrapX(t.x, bz), t.y)
        }
    }

    /** Radar tiles at RainViewer's zoom (max 7), with where each lands in world pixels at the view's zoom. */
    private fun radarTiles(v: Viewport, layers: MapLayers): List<Pair<RectF, String>>? {
        val frame = layers.radarFrame ?: return null
        val host = layers.radarHost ?: return null
        val rz = minOf(v.zoom, RainViewer.MAX_ZOOM)
        val f = 2.0.pow(v.zoom - rz)
        val size = (TileMath.TILE * f).toFloat()
        return TileMath.tilesFor(v.cx / f, v.cy / f, rz, v.width / f, v.height / f).map { t ->
            val x = (t.x * TileMath.TILE * f).toFloat()
            val y = (t.y * TileMath.TILE * f).toFloat()
            RectF(x, y, x + size, y + size) to RainViewer.tileUrl(host, frame, rz, TileMath.wrapX(t.x, rz), t.y)
        }
    }

    /** Satellite tiles at GIBS's zoom (max 6), with where each lands in world pixels at the view's zoom. */
    private fun satelliteTiles(v: Viewport, layers: MapLayers): List<Pair<RectF, String>>? {
        val time = layers.satelliteTime ?: return null
        val sz = minOf(v.zoom, Gibs.MAX_ZOOM)
        val f = 2.0.pow(v.zoom - sz)
        val size = (TileMath.TILE * f).toFloat()
        return TileMath.tilesFor(v.cx / f, v.cy / f, sz, v.width / f, v.height / f).map { t ->
            val x = (t.x * TileMath.TILE * f).toFloat()
            val y = (t.y * TileMath.TILE * f).toFloat()
            RectF(x, y, x + size, y + size) to Gibs.tileUrl(time, sz, TileMath.wrapX(t.x, sz), t.y)
        }
    }

    private fun gridRect(grid: RainGrid, zoom: Int) = RectF(
        TileMath.x(grid.west, zoom).toFloat(), TileMath.y(grid.north, zoom).toFloat(),
        TileMath.x(grid.east, zoom).toFloat(), TileMath.y(grid.south, zoom).toFloat(),
    )

    /** Colours one hourly value of the grid into a small bitmap, blended between points. */
    private fun fieldOverlay(grid: RainGrid, kind: FieldKind, h: Int, zoom: Int): Bitmap {
        val key = listOf(grid, kind, h, zoom)
        fieldBmp?.takeIf { fieldKey == key }?.let { return it }
        val n = 96
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val top = TileMath.y(grid.north, zoom)
        val bottom = TileMath.y(grid.south, zoom)
        val pixels = IntArray(n * n)
        for (py in 0 until n) {
            val lat = TileMath.lat(top + (bottom - top) * (py + 0.5) / n, zoom)
            for (px in 0 until n) {
                val lon = grid.west + (grid.east - grid.west) * (px + 0.5) / n
                pixels[py * n + px] = grid.fieldAt(kind.varName, lat, lon, h)?.let { kind.scale.colorAt(it) } ?: Color.TRANSPARENT
            }
        }
        bmp.setPixels(pixels, 0, n, 0, 0, n, n)
        fieldBmp = bmp
        fieldKey = key
        return bmp
    }

    /** The value at each grid point that is on screen. */
    private fun drawLabels(canvas: Canvas, v: Viewport, grid: RainGrid, kind: FieldKind, h: Int) {
        for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
            val value = grid.fieldAtPoint(kind.varName, r, c, h) ?: continue
            val x = TileMath.x(grid.lon(c), v.zoom).toFloat()
            val y = TileMath.y(grid.lat(r), v.zoom).toFloat()
            if (x < v.left + 12 || x > v.left + v.width - 12 || y < v.top + 12 || y > v.top + v.height - 12) continue
            val text = kind.label(value)
            canvas.drawText(text, x, y + 4f, labelHalo)
            canvas.drawText(text, x, y + 4f, labelFill)
        }
    }

    /** Arrows showing which way the wind blows, longer when stronger, every 46 world pixels. */
    private fun drawWindArrows(canvas: Canvas, v: Viewport, grid: RainGrid, h: Int) {
        val step = 46.0
        var y = v.top + step / 2
        while (y < v.top + v.height) {
            var x = v.left + step / 2
            while (x < v.left + v.width) {
                val w = grid.windAt(TileMath.lat(y, v.zoom), TileMath.lon(x, v.zoom), h)
                if (w != null && w.speed >= 1) {
                    val to = Math.toRadians(w.fromDeg + 180)
                    val len = (8 + w.speed / 4).coerceAtMost(24.0)
                    val dx = (sin(to) * len / 2).toFloat()
                    val dy = (-cos(to) * len / 2).toFloat()
                    val x0 = x.toFloat() - dx
                    val y0 = y.toFloat() - dy
                    val x1 = x.toFloat() + dx
                    val y1 = y.toFloat() + dy
                    canvas.drawLine(x0, y0, x1, y1, arrowPaint)
                    val a = kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                    val head = Path().apply {
                        moveTo(x1, y1)
                        lineTo((x1 - 6 * cos(a - 0.45)).toFloat(), (y1 - 6 * sin(a - 0.45)).toFloat())
                        lineTo((x1 - 6 * cos(a + 0.45)).toFloat(), (y1 - 6 * sin(a + 0.45)).toFloat())
                        close()
                    }
                    canvas.drawPath(head, arrowHead)
                }
                x += step
            }
            y += step
        }
    }

    /** Isobars every 4 hPa, with H and L at highs and lows. */
    private fun drawIsobars(canvas: Canvas, v: Viewport, grid: RainGrid, h: Int, onSatellite: Boolean) {
        val nx = 64
        val ny = (nx * v.height / v.width).roundToInt().coerceIn(16, 160)
        val dx = v.width / (nx - 1)
        val dy = v.height / (ny - 1)
        val values = DoubleArray(nx * ny) { i ->
            val x = v.left + (i % nx) * dx
            val y = v.top + (i / nx) * dy
            grid.fieldAt("pressure_msl", TileMath.lat(y, v.zoom), TileMath.lon(x, v.zoom) + lonShift(grid, v), h) ?: Double.NaN
        }
        isobar.color = if (onSatellite) Color.argb(170, 255, 255, 255) else Color.argb(170, 0x3C, 0x3C, 0x50)
        var level = 940.0
        while (level <= 1060.0) {
            for (sgm in Contours.lines(values, nx, ny, level)) {
                canvas.drawLine(
                    (v.left + sgm.x1 * dx).toFloat(), (v.top + sgm.y1 * dy).toFloat(),
                    (v.left + sgm.x2 * dx).toFloat(), (v.top + sgm.y2 * dy).toFloat(), isobar,
                )
            }
            level += 4.0
        }
        for (cn in Contours.centres(grid, h)) {
            val x = TileMath.x(cn.lon - lonShift(grid, v), v.zoom).toFloat()
            val y = TileMath.y(cn.lat, v.zoom).toFloat()
            centreText.color = if (cn.high) Color.rgb(0x1F, 0x5A, 0x9C) else Color.rgb(0xC0, 0x39, 0x2B)
            canvas.drawText(if (cn.high) "H" else "L", x, y + 8f, centreHalo)
            canvas.drawText(if (cn.high) "H" else "L", x, y + 8f, centreText)
            canvas.drawText("${cn.hPa.roundToInt()}", x, y + 21f, centreSmall)
        }
    }

    /**
     * Degrees to add to a view longitude to land in [grid]'s longitudes. The view's longitudes run
     * from -180 to 180 while a grid across the date line can run past 180.
     */
    private fun lonShift(grid: RainGrid, v: Viewport): Double {
        val centre = (grid.west + grid.east) / 2
        return 360.0 * Math.round((centre - v.centerLon) / 360.0)
    }

    /** A cyclone's forecast cone, track (dashed ahead) and where it is now. */
    private fun drawCyclone(canvas: Canvas, v: Viewport, c: RainMapRepository.Cyclone) {
        fun px(lat: Double, lon: Double): Pair<Float, Float> {
            val l = lon + 360.0 * Math.round((v.centerLon - lon) / 360.0)
            return TileMath.x(l, v.zoom).toFloat() to TileMath.y(lat, v.zoom).toFloat()
        }
        val cone = c.track.cone
        if (cone.size > 2) {
            val path = Path()
            cone.forEachIndexed { i, (lat, lon) -> val (x, y) = px(lat, lon); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            path.close()
            canvas.drawPath(path, coneFill)
            canvas.drawPath(path, coneEdge)
        }
        for (leg in c.track.legs) {
            val (x1, y1) = px(leg.lat1, leg.lon1)
            val (x2, y2) = px(leg.lat2, leg.lon2)
            legPaint.color = when (leg.stage.uppercase()) {
                "TD" -> Color.rgb(0x4C, 0xA8, 0x6A)
                "TS" -> Color.rgb(0xE0, 0xB0, 0x2A)
                else -> Color.rgb(0xC0, 0x39, 0x2B)
            }
            legPaint.pathEffect = if (leg.forecast) legDash else null
            canvas.drawLine(x1, y1, x2, y2, legPaint)
        }
        val (x, y) = px(c.event.lat, c.event.lon)
        canvas.drawCircle(x, y, 8f, stormFill)
        canvas.drawCircle(x, y, 8f, stormRing)
        canvas.drawText(c.event.name, x, y - 13f, labelHalo)
        canvas.drawText(c.event.name, x, y - 13f, labelFill)
    }

    /** Colours the grid's rain for step [t] into a small bitmap, interpolated between points. */
    private fun forecastOverlay(grid: RainGrid, t: Int, zoom: Int): Bitmap {
        val key = Triple(grid, grid.times[t], zoom)
        overlay?.takeIf { overlayKey == key }?.let { return it }
        val n = 96
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val top = TileMath.y(grid.north, zoom)
        val bottom = TileMath.y(grid.south, zoom)
        val pixels = IntArray(n * n)
        for (py in 0 until n) {
            // Rows are spaced evenly in map (Mercator) space, so convert each back to a latitude.
            val lat = TileMath.lat(top + (bottom - top) * (py + 0.5) / n, zoom)
            for (px in 0 until n) {
                val lon = grid.west + (grid.east - grid.west) * (px + 0.5) / n
                pixels[py * n + px] = colorFor(grid.rateAt(lat, lon, t))
            }
        }
        bmp.setPixels(pixels, 0, n, 0, 0, n, n)
        overlay = bmp
        overlayKey = key
        return bmp
    }

    private fun drawBolt(canvas: Canvas, x: Float, y: Float) {
        val p = Path().apply {
            moveTo(x + 1f, y - 10f); lineTo(x - 7f, y + 2f); lineTo(x - 1f, y + 2f)
            lineTo(x - 2f, y + 10f); lineTo(x + 7f, y - 2f); lineTo(x + 1f, y - 2f); close()
        }
        canvas.drawPath(p, boltFill)
        canvas.drawPath(p, boltStroke)
    }

    companion object {
        /** Legend colours (light, moderate, heavy, downpour), matching the design. */
        val BAND_COLORS = mapOf(
            RainBand.LIGHT to Color.argb(215, 0x4F, 0x8F, 0xD0),
            RainBand.MODERATE to Color.argb(230, 0x1F, 0x5A, 0x9C),
            RainBand.HEAVY to Color.argb(240, 0x0C, 0x2A, 0x5A),
            RainBand.DOWNPOUR to Color.argb(230, 0xE8, 0x96, 0x2E),
        )

        fun colorFor(rate: Double?): Int = RainBand.of(rate)?.let { BAND_COLORS.getValue(it) } ?: Color.TRANSPARENT
    }
}
