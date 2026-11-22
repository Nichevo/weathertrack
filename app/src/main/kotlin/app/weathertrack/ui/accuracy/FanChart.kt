package app.weathertrack.ui.accuracy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import app.weathertrack.core.accuracy.FanPoint
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val TempLine = Color(0xFFD9822B)
private val RainBar = Color(0xFF2F7DD1)

/** Forecast highs as a line, with the likely range (8 in 10 past days) as a band that widens with lead. */
@Composable
fun TempFanChart(points: List<FanPoint>, lineColor: Color = TempLine) {
    if (points.isEmpty()) return
    val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val lo = floor(points.minOf { it.low }) - 1
    val hi = ceil(points.maxOf { it.high }) + 1
    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = label; textSize = 10.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
        }
        val left = 28.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 30.dp.toPx()
        val slot = (size.width - left) / points.size
        fun x(i: Int) = left + (i + 0.5f) * slot
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        // Gridlines every few degrees.
        val step = if (hi - lo > 12) 5 else 2
        var g = ceil(lo / step) * step
        text.textAlign = android.graphics.Paint.Align.LEFT
        while (g <= hi) {
            drawLine(grid, Offset(left, y(g)), Offset(size.width, y(g)), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("${g.roundToInt()}°", 0f, y(g) + 4.dp.toPx(), text)
            g += step
        }
        text.textAlign = android.graphics.Paint.Align.CENTER

        val band = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.high)) else lineTo(x(i), y(p.high)) }
            for (i in points.indices.reversed()) lineTo(x(i), y(points[i].low))
            close()
        }
        drawPath(band, lineColor.copy(alpha = 0.22f))
        val line = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.value)) else lineTo(x(i), y(p.value)) } }
        drawPath(line, lineColor, style = Stroke(width = 2.5.dp.toPx()))
        points.forEachIndexed { i, p ->
            drawCircle(lineColor, 3.5.dp.toPx(), Offset(x(i), y(p.value)))
            drawContext.canvas.nativeCanvas.drawText(day(p), x(i), size.height - 16.dp.toPx(), text)
            drawContext.canvas.nativeCanvas.drawText("${p.low.roundToInt()}–${p.high.roundToInt()}°", x(i), size.height - 3.dp.toPx(), text)
        }
    }
}

/** Forecast rain per day as bars, with a line from the likely low to the likely high. */
@Composable
fun RainFanChart(points: List<FanPoint>) {
    if (points.isEmpty()) return
    val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val whisker = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = maxOf(points.maxOf { it.high }, 2.0)
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = label; textSize = 10.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
        }
        val left = 28.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 30.dp.toPx()
        val slot = (size.width - left) / points.size
        fun x(i: Int) = left + (i + 0.5f) * slot
        fun y(v: Double) = (bottom - v / max * (bottom - top)).toFloat()

        text.textAlign = android.graphics.Paint.Align.LEFT
        listOf(0.0, max / 2, max).forEach { g ->
            drawLine(grid, Offset(left, y(g)), Offset(size.width, y(g)), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("${g.roundToInt()}", 0f, y(g) + 4.dp.toPx(), text)
        }
        text.textAlign = android.graphics.Paint.Align.CENTER

        points.forEachIndexed { i, p ->
            val w = slot * 0.5f
            // Likely range as a pale column, the forecast itself as a solid bar.
            drawRoundRect(RainBar.copy(alpha = 0.18f), Offset(x(i) - w / 2, y(p.high)), Size(w, y(p.low) - y(p.high)), CornerRadius(4f))
            drawRoundRect(RainBar, Offset(x(i) - w * 0.3f, y(p.value)), Size(w * 0.6f, bottom - y(p.value)), CornerRadius(3f))
            drawLine(whisker, Offset(x(i), y(p.low)), Offset(x(i), y(p.high)), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            drawContext.canvas.nativeCanvas.drawText(day(p), x(i), size.height - 16.dp.toPx(), text)
            drawContext.canvas.nativeCanvas.drawText("${mm(p.low)}–${mm(p.high)}", x(i), size.height - 3.dp.toPx(), text)
        }
    }
}

private fun day(p: FanPoint) = if (p.lead == 0) "Today" else p.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

private fun mm(v: Double) = if (v < 10) String.format(Locale.US, "%.0f", v).let { if (v > 0 && it == "0") "<1" else it } else v.roundToInt().toString()

/** Rain chance per day as bars: the forecast's chance, with a marker for the share of weather models that make it wet. */
@Composable
fun RainChanceChart(days: List<Triple<String, Double?, Double?>>) {
    if (days.isEmpty()) return
    val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = label; textSize = 10.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
        }
        val top = 14.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val slot = size.width / days.size
        val barW = slot * 0.5f
        days.forEachIndexed { i, (name, chance, models) ->
            val cx = slot * i + slot / 2
            drawRect(track, androidx.compose.ui.geometry.Offset(cx - barW / 2, top), androidx.compose.ui.geometry.Size(barW, bottom - top))
            chance?.let { c ->
                val h = ((c / 100.0) * (bottom - top)).toFloat()
                drawRect(RainBar.copy(alpha = (0.35 + 0.65 * c / 100).toFloat()), androidx.compose.ui.geometry.Offset(cx - barW / 2, bottom - h),
                    androidx.compose.ui.geometry.Size(barW, h))
                drawContext.canvas.nativeCanvas.drawText("${c.roundToInt()}%", cx, top - 3.dp.toPx(), text)
            }
            models?.let { m ->
                val y = (bottom - (m / 100.0) * (bottom - top)).toFloat()
                drawLine(marker, androidx.compose.ui.geometry.Offset(cx - barW / 2 - 4.dp.toPx(), y),
                    androidx.compose.ui.geometry.Offset(cx + barW / 2 + 4.dp.toPx(), y), strokeWidth = 2.dp.toPx())
            }
            drawContext.canvas.nativeCanvas.drawText(name, cx, size.height - 3.dp.toPx(), text)
        }
    }
}
