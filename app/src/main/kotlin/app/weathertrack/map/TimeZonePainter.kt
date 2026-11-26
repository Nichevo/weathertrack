package app.weathertrack.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.weathertrack.core.map.TileMath
import app.weathertrack.core.map.TimeZoneShapes
import app.weathertrack.core.map.ZoneClock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Time zones to draw: coloured by offset at [at], with each zone's clock written on it. */
data class TimeZoneOverlay(
    val shapes: TimeZoneShapes,
    /** Epoch seconds the clocks show. */
    val at: Long,
    /** The selected place's zone, outlined and used for "same day" in labels. */
    val homeZone: String?,
    /** A zone tapped on the map, outlined more strongly. */
    val picked: String?,
)

/** Draws [TimeZoneOverlay]: fills, borders and clock labels, with paths cached per zoom level. */
class TimeZonePainter {
    private var pathZoom = -1
    private var pathShapes: TimeZoneShapes? = null
    private var paths: List<Path> = emptyList()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.7f; color = Color.argb(150, 0x3C, 0x3C, 0x50); strokeJoin = Paint.Join.ROUND
    }
    private val homeEdge = Paint(border).apply { strokeWidth = 1.8f; color = Color.argb(220, 0x2F, 0x62, 0x95) }
    private val pickedEdge = Paint(border).apply { strokeWidth = 2.4f; color = Color.argb(235, 0x1B, 0x1B, 0x21) }
    private val timeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x1B, 0x1B, 0x21); textSize = 12f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val timeHalo = Paint(timeText).apply { color = Color.argb(225, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val subText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x44, 0x47, 0x4E); textSize = 9.5f; textAlign = Paint.Align.CENTER
    }
    private val subHalo = Paint(subText).apply { color = Color.argb(215, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2.6f }
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val dayClock = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH)

    fun draw(canvas: Canvas, v: Viewport, o: TimeZoneOverlay) {
        val zones = o.shapes.zones
        ensurePaths(o.shapes, v.zoom)
        val world = TileMath.worldSize(v.zoom)
        val at = Instant.ofEpochSecond(o.at)
        val offsets = IntArray(zones.size) { ZoneClock.offsetSeconds(zones[it].id, at) ?: 0 }
        val viewRect = RectF(v.left.toFloat(), v.top.toFloat(), (v.left + v.width).toFloat(), (v.top + v.height).toFloat())

        // Which copies of the world (one each side for the date line) each zone shows up in.
        val shifts = zones.map { z ->
            val l = TileMath.x(z.west, v.zoom); val r = TileMath.x(z.east, v.zoom)
            val t = TileMath.y(z.north, v.zoom); val b = TileMath.y(z.south, v.zoom)
            (-1..1).map { it * world }.filter { s -> l + s < viewRect.right && r + s > viewRect.left && t < viewRect.bottom && b > viewRect.top }
        }

        // Fills, open ocean first so land zones sit on top.
        val order = zones.indices.sortedBy { if (zones[it].id.startsWith("Etc/")) 0 else 1 }
        for (i in order) {
            if (shifts[i].isEmpty()) continue
            fill.color = ZoneClock.color(offsets[i], alpha = if (zones[i].id.startsWith("Etc/")) 45 else 80)
            for (s in shifts[i]) drawShifted(canvas, paths[i], s, fill)
        }
        for (i in zones.indices) for (s in shifts[i]) drawShifted(canvas, paths[i], s, border)
        for (i in zones.indices) {
            val edge = when (zones[i].id) { o.picked -> pickedEdge; o.homeZone -> homeEdge; else -> null } ?: continue
            for (s in shifts[i]) drawShifted(canvas, paths[i], s, edge)
        }

        drawLabels(canvas, v, o, offsets, viewRect, world)
    }

    private fun drawShifted(canvas: Canvas, path: Path, shift: Double, paint: Paint) {
        if (shift == 0.0) { canvas.drawPath(path, paint); return }
        canvas.save()
        canvas.translate(shift.toFloat(), 0f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    /** Each zone's clock at its label point: big zones first, skipping any that would overlap. */
    private fun drawLabels(canvas: Canvas, v: Viewport, o: TimeZoneOverlay, offsets: IntArray, view: RectF, world: Double) {
        val zones = o.shapes.zones
        val at = Instant.ofEpochSecond(o.at)
        val homeDate = ZoneClock.zone(o.homeZone ?: "")?.let { at.atZone(it).toLocalDate() }
        val priority = zones.indices.sortedWith(
            compareByDescending<Int> { zones[it].id == o.picked }
                .thenByDescending { zones[it].id == o.homeZone }
                .thenBy { zones[it].id.startsWith("Etc/") }
                .thenByDescending { zones[it].size },
        )
        val taken = ArrayList<RectF>()
        val cx = v.cx
        for (i in priority) {
            if (taken.size >= MAX_LABELS) break
            val z = zones[i]
            var x = TileMath.x(z.labelLon, v.zoom)
            x += world * Math.round((cx - x) / world)
            val y = TileMath.y(z.labelLat, v.zoom)
            if (x < view.left + 20 || x > view.right - 20 || y < view.top + 16 || y > view.bottom - 16) continue
            val id = ZoneClock.zone(z.id) ?: continue
            val local = at.atZone(id)
            val time = (if (homeDate != null && local.toLocalDate() != homeDate) dayClock else clock).format(local).lowercase(Locale.ENGLISH)
                .replaceFirstChar { it.uppercase() }
            val sub = listOfNotNull(placeName(z.id), ZoneClock.offsetLabel(offsets[i])).joinToString(" · ")
            val w = maxOf(timeText.measureText(time), subText.measureText(sub)) / 2 + 3
            val rect = RectF((x - w).toFloat(), (y - 12).toFloat(), (x + w).toFloat(), (y + 14).toFloat())
            if (taken.any { RectF.intersects(it, rect) }) continue
            taken += rect
            val fx = x.toFloat(); val fy = y.toFloat()
            canvas.drawText(time, fx, fy, timeHalo)
            canvas.drawText(time, fx, fy, timeText)
            canvas.drawText(sub, fx, fy + 11f, subHalo)
            canvas.drawText(sub, fx, fy + 11f, subText)
        }
    }

    /** Builds one path per zone in world pixels, dropping points less than a pixel apart. */
    private fun ensurePaths(shapes: TimeZoneShapes, zoom: Int) {
        if (zoom == pathZoom && shapes === pathShapes) return
        paths = shapes.zones.map { z ->
            Path().apply {
                fillType = Path.FillType.EVEN_ODD
                for (p in z.polygons) for (r in p.rings) {
                    var lx = 0.0; var ly = 0.0
                    for (k in 0 until r.size) {
                        val x = TileMath.x(r.lons[k], zoom)
                        val y = TileMath.y(r.lats[k], zoom)
                        if (k == 0) { moveTo(x.toFloat(), y.toFloat()); lx = x; ly = y; continue }
                        if (k < r.size - 1 && abs(x - lx) < 0.8 && abs(y - ly) < 0.8) continue
                        lineTo(x.toFloat(), y.toFloat()); lx = x; ly = y
                    }
                    close()
                }
            }
        }
        pathZoom = zoom
        pathShapes = shapes
    }

    companion object {
        private const val MAX_LABELS = 60

        /** "Pacific/Auckland" → "Auckland", "America/Argentina/Buenos_Aires" → "Buenos Aires"; none for open ocean. */
        fun placeName(id: String): String? =
            if (id.startsWith("Etc/")) null else id.substringAfterLast('/').replace('_', ' ')
    }
}
