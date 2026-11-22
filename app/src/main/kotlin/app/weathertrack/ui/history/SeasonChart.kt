package app.weathertrack.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.weathertrack.core.history.Season
import app.weathertrack.core.history.SeasonCompare
import app.weathertrack.core.history.SeasonView
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val RainColor = Color(0xFF2F6FB0)
private val TempColor = Color(0xFFD9822B)

private fun seasonTint(s: Season) = when (s) {
    Season.SUMMER -> Color(0x1FF2B134)
    Season.AUTUMN -> Color(0x1FB5651D)
    Season.WINTER -> Color(0x1F4A78B5)
    Season.SPRING -> Color(0x1F4CA35A)
}

private fun monthName(m: java.time.YearMonth) = m.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

/** Rain bars (this year against average) and temperature lines for the last 12 months, by season. */
@Composable
fun SeasonCard(v: SeasonView) {
    val first = v.months.first().month
    val last = v.months.last().month
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last 12 months by season", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${monthName(first)} ${first.year} to ${monthName(last)} ${last.year}, against the ${v.normalLabel} average for each month.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Chart(v)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Key(RainColor, "Rain")
                Key(MaterialTheme.colorScheme.outlineVariant, "Avg rain")
                Key(TempColor, "Temp", line = true)
                Key(MaterialTheme.colorScheme.outline, "Avg temp", line = true)
            }
            v.seasons.forEach { SeasonLine(it) }
        }
    }
}

@Composable
private fun Key(color: Color, label: String, line: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = if (line) 3.dp else 10.dp).background(color))
        Text(" $label", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Chart(v: SeasonView) {
    val avgBar = MaterialTheme.colorScheme.outlineVariant
    val avgLine = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val months = v.months
    val maxRain = (months.flatMap { listOfNotNull(it.rain, it.normalRain) }.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val temps = months.flatMap { listOfNotNull(it.temp, it.normalTemp) }
    val tLo = (temps.minOrNull() ?: 0.0) - 1
    val tHi = (temps.maxOrNull() ?: 1.0) + 1
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor; textSize = 10.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
        }
        val top = 16.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val left = 30.dp.toPx()
        val right = size.width - 30.dp.toPx()
        val slot = (right - left) / months.size
        fun ry(r: Double) = (bottom - (r / maxRain) * (bottom - top)).toFloat()
        fun ty(t: Double) = (bottom - (t - tLo) / (tHi - tLo) * (bottom - top)).toFloat()

        // Season bands with their names along the top.
        var i = 0
        while (i < months.size) {
            val s = months[i].season
            var j = i
            while (j + 1 < months.size && months[j + 1].season == s) j++
            drawRect(seasonTint(s), Offset(left + i * slot, top), Size((j - i + 1) * slot, bottom - top))
            drawContext.canvas.nativeCanvas.drawText(s.label, left + (i + j + 1) / 2f * slot, top - 4.dp.toPx(), textPaint)
            i = j + 1
        }

        // Rain: average bar behind, this year in front.
        months.forEachIndexed { k, m ->
            val x = left + k * slot
            m.normalRain?.let { drawRoundRect(avgBar, Offset(x + slot * 0.18f, ry(it)), Size(slot * 0.64f, bottom - ry(it)), CornerRadius(3f)) }
            m.rain?.let { drawRoundRect(RainColor, Offset(x + slot * 0.3f, ry(it)), Size(slot * 0.4f, bottom - ry(it)), CornerRadius(3f)) }
            drawContext.canvas.nativeCanvas.drawText(monthName(m.month).take(1), x + slot / 2, size.height - 2.dp.toPx(), textPaint)
        }

        // Temperature lines.
        fun line(values: List<Double?>, color: Color, dashed: Boolean) {
            val path = Path()
            var started = false
            values.forEachIndexed { k, t ->
                if (t == null) { started = false; return@forEachIndexed }
                val p = Offset(left + (k + 0.5f) * slot, ty(t))
                if (started) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                started = true
                if (!dashed) drawCircle(color, 3.dp.toPx(), p)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
        }
        line(months.map { it.normalTemp }, avgLine, dashed = true)
        line(months.map { it.temp }, TempColor, dashed = false)

        // Axes labels: rain on the left, temperature on the right.
        textPaint.textAlign = android.graphics.Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText("${maxRain.roundToInt()}mm", 0f, top + 10.dp.toPx(), textPaint)
        drawContext.canvas.nativeCanvas.drawText("0", 0f, bottom, textPaint)
        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText("${tHi.roundToInt()}°", size.width, top + 10.dp.toPx(), textPaint)
        drawContext.canvas.nativeCanvas.drawText("${tLo.roundToInt()}°", size.width, bottom, textPaint)
    }
}

@Composable
private fun SeasonLine(s: SeasonCompare) {
    val span = if (s.from == s.to) monthName(s.from) else "${monthName(s.from)}–${monthName(s.to)}"
    val rain = if (s.rain != null && s.normalRain != null && s.normalRain!! > 0) {
        val pct = ((s.rain!! / s.normalRain!! - 1) * 100).roundToInt()
        "rain ${s.rain!!.roundToInt()} mm (${if (pct >= 0) "+" else ""}$pct% on avg)"
    } else null
    val temp = if (s.temp != null && s.normalTemp != null) {
        val d = s.temp!! - s.normalTemp!!
        "${String.format(Locale.US, "%.1f", s.temp)}° (${if (d >= 0) "+" else ""}${String.format(Locale.US, "%.1f", d)}°)"
    } else null
    Column {
        Text("${s.season.label} ($span)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(listOfNotNull(rain, temp).joinToString(" · ").ifEmpty { "Not enough data" }, style = MaterialTheme.typography.bodySmall)
    }
}
