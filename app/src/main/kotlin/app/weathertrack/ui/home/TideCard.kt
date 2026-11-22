package app.weathertrack.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.weathertrack.core.tides.TideSeries
import app.weathertrack.core.tides.Tides
import app.weathertrack.ui.Format
import java.time.ZoneId
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val Sea = Color(0xFF1C8A9E)

private fun m(v: Double) = String.format(Locale.US, "%+.1f m", v)

/** Distance in km between two points. */
private fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(a))
}

/** Next high and low tides, and the sea level over the next 24 hours. */
@Composable
fun TideCard(t: TideSeries, zone: ZoneId, placeLat: Double, placeLon: Double) {
    val now = System.currentTimeMillis() / 1000
    val state = Tides.now(t, now) ?: return
    val window = t.points.filter { it.time >= now - 3 * 3600 && it.time <= now + 24 * 3600 }
    if (window.size < 4) return
    val events = t.events.filter { it.time in (now - 3 * 3600)..(now + 24 * 3600) }
    val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val nowColor = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tide", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val next = state.next
            val after = state.after
            Text(
                (if (state.rising) "Coming in" else "Going out") +
                    (next?.let { ". ${if (it.high) "High" else "Low"} at ${Format.time(it.time, zone)} (${m(it.height)})" } ?: "") +
                    (after?.let { ", then ${if (it.high) "high" else "low"} at ${Format.time(it.time, zone)}" } ?: ""),
            )
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = label; textSize = 10.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
                }
                val t0 = window.first().time
                val t1 = window.last().time
                val lo = window.minOf { it.height } - 0.2
                val hi = window.maxOf { it.height } + 0.2
                val top = 14.dp.toPx()
                val bottom = size.height - 4.dp.toPx()
                fun x(t: Long) = ((t - t0).toFloat() / (t1 - t0)) * size.width
                fun y(h: Double) = (bottom - (h - lo) / (hi - lo) * (bottom - top)).toFloat()
                val line = Path()
                val fill = Path()
                window.forEachIndexed { i, p ->
                    if (i == 0) { line.moveTo(x(p.time), y(p.height)); fill.moveTo(x(p.time), bottom) }
                    else line.lineTo(x(p.time), y(p.height))
                    fill.lineTo(x(p.time), y(p.height))
                }
                fill.lineTo(x(window.last().time), bottom); fill.close()
                drawPath(fill, Sea.copy(alpha = 0.18f))
                drawPath(line, Sea, style = Stroke(width = 2.dp.toPx()))
                val xn = x(now)
                drawLine(nowColor, Offset(xn, top - 6.dp.toPx()), Offset(xn, bottom), strokeWidth = 1.5.dp.toPx())
                drawCircle(nowColor, 4.dp.toPx(), Offset(xn, y(state.height)))
                events.forEach { e ->
                    val ex = x(e.time).coerceIn(16.dp.toPx(), size.width - 16.dp.toPx())
                    val ey = y(e.height)
                    drawCircle(Sea, 3.dp.toPx(), Offset(x(e.time), ey))
                    val tLabel = Format.time(e.time, zone).replace(" ", "").lowercase()
                    drawContext.canvas.nativeCanvas.drawText(tLabel, ex, if (e.high) ey - 6.dp.toPx() else ey + 14.dp.toPx(), text)
                }
            }
            val dist = km(placeLat, placeLon, t.latitude, t.longitude)
            Text(
                "Heights against mean sea level, from the nearest sea point (about ${dist.roundToInt()} km away). " +
                    "A guide only, not for boating.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
