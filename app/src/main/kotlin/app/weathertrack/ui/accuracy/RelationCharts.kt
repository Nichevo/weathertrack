package app.weathertrack.ui.accuracy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import app.weathertrack.core.accuracy.JacketScore
import app.weathertrack.core.accuracy.Relation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val Dot = Color(0xFF2F7DD1)
private val Fit = Color(0xFFD9822B)
private val Good = Color(0xFF2E7D32)
private val Bad = Color(0xFFC62828)

/** Short chip names for each relationship. */
private fun chipLabel(r: Relation) = when (r.key) {
    "cloud_high" -> "Cloud → high"
    "lowcloud_high" -> "Low cloud → high"
    "sun_high" -> "Sun → high"
    "wind_high" -> "Wind → high"
    "rain_high" -> "Rain → high"
    "cloud_low" -> "Cloud → night low"
    "wind_low" -> "Wind → night low"
    else -> r.key
}

/** Chips to pick a relationship, its sentence and a scatter chart of every day with the fitted line. */
@Composable
fun RelationsPicker(relations: List<Relation>) {
    if (relations.isEmpty()) return
    var key by remember { mutableStateOf(relations.maxBy { abs(it.r) }.key) }
    val r = relations.firstOrNull { it.key == key } ?: relations.first()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        relations.forEach { rel -> FilterChip(selected = rel.key == r.key, onClick = { key = rel.key }, label = { Text(chipLabel(rel)) }) }
    }
    Text(r.sentence(), style = MaterialTheme.typography.bodyMedium)
    RelationScatter(r)
}

/**
 * Each day as a dot: how far the driver was from normal for the time of year (across) against how far the
 * outcome was (up), with the fitted line.
 */
@Composable
fun RelationScatter(r: Relation) {
    if (r.points.isEmpty()) return
    val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val xs = r.points.map { it.first }
    val ys = r.points.map { it.second }
    val xr = max(abs(xs.min()), abs(xs.max())).coerceAtLeast(1e-6) * 1.05
    val yr = max(abs(ys.min()), abs(ys.max())).coerceAtLeast(0.5) * 1.05
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = label; textSize = 10.dp.toPx() }
        val left = 34.dp.toPx(); val right = size.width - 6.dp.toPx()
        val top = 6.dp.toPx(); val bottom = size.height - 28.dp.toPx()
        fun x(v: Double) = (left + (v + xr) / (2 * xr) * (right - left)).toFloat()
        fun y(v: Double) = (bottom - (v + yr) / (2 * yr) * (bottom - top)).toFloat()
        // Axes through "normal for the time of year".
        drawLine(grid, Offset(left, y(0.0)), Offset(right, y(0.0)), strokeWidth = 1.5f)
        drawLine(grid, Offset(x(0.0), top), Offset(x(0.0), bottom), strokeWidth = 1.5f)
        val nc = drawContext.canvas.nativeCanvas
        text.textAlign = android.graphics.Paint.Align.RIGHT
        listOf(yr * 0.9, -yr * 0.9).forEach { v ->
            nc.drawText(String.format(java.util.Locale.US, "%+.0f%s", v, r.outcomeUnit), left - 4.dp.toPx(), y(v) + 4.dp.toPx(), text)
        }
        text.textAlign = android.graphics.Paint.Align.CENTER
        listOf(-xr * 0.8, xr * 0.8).forEach { v ->
            nc.drawText(String.format(java.util.Locale.US, "%+.0f%s", v, r.driverUnit), x(v), bottom + 13.dp.toPx(), text)
        }
        nc.drawText("${r.driver} vs normal →", (left + right) / 2, size.height - 2.dp.toPx(), text)
        r.points.forEach { (a, b) -> drawCircle(Dot.copy(alpha = 0.55f), 3.dp.toPx(), Offset(x(a), y(b))) }
        // The fitted line (it passes through the middle since both sides are relative to normal, near enough).
        val my = ys.average(); val mx = xs.average()
        drawLine(Fit, Offset(x(-xr), y(my + r.slope * (-xr - mx))), Offset(x(xr), y(my + r.slope * (xr - mx))), strokeWidth = 2.5.dp.toPx())
    }
    Text("Each dot is a day: how far ${r.driver} and ${r.outcome} were from normal for the time of year. The orange line is the fit.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Horizontal bars of jacket calls right by hours ahead. */
@Composable
fun JacketLeadBars(byLead: Map<Int, JacketScore>) {
    val rows = byLead.filterValues { it.decided > 0 }
    if (rows.isEmpty()) return
    val label = MaterialTheme.colorScheme.onSurface.toArgb()
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height((rows.size * 28 + 4).dp)) {
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = label; textSize = 12.dp.toPx() }
        val left = 72.dp.toPx(); val right = size.width - 48.dp.toPx()
        rows.entries.forEachIndexed { i, (lead, j) ->
            val share = j.share ?: 0.0
            val top = i * 28.dp.toPx() + 4.dp.toPx(); val h = 18.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText("$lead h ahead", 0f, top + h - 4.dp.toPx(), text)
            drawRoundRect(track, Offset(left, top), Size(right - left, h), CornerRadius(6.dp.toPx()))
            val c = if (share >= 0.8) Good else if (share >= 0.6) Color(0xFFF9A825) else Bad
            drawRoundRect(c, Offset(left, top), Size(((right - left) * share).toFloat(), h), CornerRadius(6.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText("${(share * 100).roundToInt()}%", right + 6.dp.toPx(), top + h - 4.dp.toPx(), text)
        }
    }
}

/** Stacked bars per source: share of hours settled by 24, 12, 6 and 3 hours out. */
@Composable
fun SettledBars(rows: List<app.weathertrack.core.sources.Settled>) {
    if (rows.isEmpty()) return
    val label = MaterialTheme.colorScheme.onSurface.toArgb()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val shades = listOf(0.95f, 0.7f, 0.45f, 0.22f)
    Canvas(Modifier.fillMaxWidth().height((rows.size * 22 + 4).dp)) {
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = label; textSize = 11.dp.toPx() }
        val left = 120.dp.toPx(); val right = size.width
        rows.forEachIndexed { i, r ->
            val top = i * 22.dp.toPx() + 3.dp.toPx(); val h = 15.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(app.weathertrack.core.sources.Sources.label(r.source).take(18), 0f, top + h - 3.dp.toPx(), text)
            drawRect(track, Offset(left, top), Size(right - left, h))
            // Widest first: settled by 3 h (palest) underneath, by 24 h (darkest) on top.
            listOf(r.by3, r.by6, r.by12, r.by24).forEachIndexed { k, v ->
                drawRect(Dot.copy(alpha = shades[3 - k]), Offset(left, top), Size(((right - left) * v).toFloat(), h))
            }
        }
    }
    Text("Bar length: share of hours already right and staying right 3 h out (palest), 6 h, 12 h and 24 h out (darkest).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
