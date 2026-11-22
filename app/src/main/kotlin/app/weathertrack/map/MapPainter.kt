package app.weathertrack.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.weathertrack.core.map.RainBand
import app.weathertrack.core.map.RainGrid
import app.weathertrack.core.map.RainViewer
import app.weathertrack.core.map.TileMath
import kotlin.math.pow

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
)

/** Draws the base map, radar or forecast rain, thunder and a position marker onto any Canvas. */
class MapPainter(private val tiles: TileStore) {
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 }
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

    /** Tile URLs [draw] needs for [v] and [layers], to preload before drawing off screen. */
    fun urlsFor(v: Viewport, layers: MapLayers): List<String> {
        val base = TileMath.tilesFor(v.cx, v.cy, v.zoom, v.width, v.height)
            .map { TileStore.baseUrl(it.z, TileMath.wrapX(it.x, it.z), it.y) }
        val radar = radarTiles(v, layers)?.map { it.second } ?: emptyList()
        return base + radar
    }

    /** Draws [v] into [canvas], where one world pixel is [scale] canvas pixels. */
    fun draw(canvas: Canvas, v: Viewport, scale: Float, layers: MapLayers) {
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate((-v.left).toFloat(), (-v.top).toFloat())
        canvas.drawRect(v.left.toFloat(), v.top.toFloat(), (v.left + v.width).toFloat(), (v.top + v.height).toFloat(), bg)

        for (t in TileMath.tilesFor(v.cx, v.cy, v.zoom, v.width, v.height)) {
            val bmp = tiles.peek(TileStore.baseUrl(t.z, TileMath.wrapX(t.x, t.z), t.y)) ?: continue
            val x = (t.x * TileMath.TILE).toFloat()
            val y = (t.y * TileMath.TILE).toFloat()
            canvas.drawBitmap(bmp, null, RectF(x, y, x + 256f, y + 256f), tilePaint)
        }

        radarTiles(v, layers)?.forEach { (rect, url) -> tiles.peek(url)?.let { canvas.drawBitmap(it, null, rect, radarPaint) } }

        val grid = layers.grid
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
        if (grid != null && layers.thunderAt != null) {
            for ((lat, lon) in grid.thunderPoints(layers.thunderAt)) {
                drawBolt(canvas, TileMath.x(lon, v.zoom).toFloat(), TileMath.y(lat, v.zoom).toFloat())
            }
        }
        layers.marker?.let { (lat, lon) ->
            val x = TileMath.x(lon, v.zoom).toFloat()
            val y = TileMath.y(lat, v.zoom).toFloat()
            canvas.drawCircle(x, y, 11f, halo)
            canvas.drawCircle(x, y, 6.5f, white)
            canvas.drawCircle(x, y, 5f, dot)
        }
        canvas.restore()
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
            RainBand.LIGHT to Color.argb(200, 0x9F, 0xCB, 0xF2),
            RainBand.MODERATE to Color.argb(210, 0x3A, 0x7F, 0xC8),
            RainBand.HEAVY to Color.argb(220, 0x1B, 0x3F, 0x78),
            RainBand.DOWNPOUR to Color.argb(230, 0xE8, 0x96, 0x2E),
        )

        fun colorFor(rate: Double?): Int = RainBand.of(rate)?.let { BAND_COLORS.getValue(it) } ?: Color.TRANSPARENT
    }
}
