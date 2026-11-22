package app.weathertrack.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private val Sun = Color(0xFFF5B301)
private val Moon = Color(0xFFB9C3D6)
private val CloudLight = Color(0xFFE3E8EF)
private val CloudDark = Color(0xFF9AA5B4)
private val CloudEdge = Color(0xFF7E8896)
private val Drop = Color(0xFF2F7DD1)
private val Bolt = Color(0xFFF3C623)
private val Snow = Color(0xFF8FB8E8)

/** A small drawn picture for a WMO weather code (Open-Meteo), with a moon instead of the sun at night. */
@Composable
fun WeatherIcon(code: Int?, isDay: Boolean?, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Canvas(modifier.size(size)) {
        val day = isDay != false
        when (code) {
            0 -> if (day) sun(Offset(0.5f, 0.5f), 0.22f) else moon(Offset(0.5f, 0.5f), 0.26f)
            1, 2 -> {
                if (day) sun(Offset(0.36f, 0.36f), 0.17f) else moon(Offset(0.36f, 0.36f), 0.2f)
                cloud(Offset(0.58f, 0.66f), 0.36f, CloudLight)
            }
            3 -> cloud(Offset(0.5f, 0.56f), 0.44f, CloudDark)
            45, 48 -> fog()
            51, 53, 55, 56, 57 -> { cloud(Offset(0.5f, 0.42f), 0.42f, CloudDark); drops(if (code >= 55) 3 else 2, short = true) }
            61, 63, 65, 66, 67 -> { cloud(Offset(0.5f, 0.42f), 0.42f, CloudDark); drops(if (code == 61) 2 else 3) }
            80, 81, 82 -> {
                if (day) sun(Offset(0.3f, 0.28f), 0.14f) else moon(Offset(0.3f, 0.28f), 0.16f)
                cloud(Offset(0.55f, 0.46f), 0.4f, CloudDark); drops(if (code == 80) 2 else 3)
            }
            71, 73, 75, 77, 85, 86 -> { cloud(Offset(0.5f, 0.42f), 0.42f, CloudLight); flakes() }
            95, 96, 99 -> { cloud(Offset(0.5f, 0.4f), 0.44f, CloudEdge); bolt() }
            else -> cloud(Offset(0.5f, 0.56f), 0.4f, CloudLight)
        }
    }
}

private fun DrawScope.p(o: Offset) = Offset(o.x * size.width, o.y * size.height)

private fun DrawScope.sun(c: Offset, r: Float) {
    val center = p(c)
    val radius = r * size.minDimension
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val inner = radius * 1.35f
        val outer = radius * 1.8f
        drawLine(
            Sun, Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat()),
            Offset(center.x + outer * cos(a).toFloat(), center.y + outer * sin(a).toFloat()),
            strokeWidth = size.minDimension * 0.06f, cap = StrokeCap.Round,
        )
    }
    drawCircle(Sun, radius, center)
}

private fun DrawScope.moon(c: Offset, r: Float) {
    val center = p(c)
    val radius = r * size.minDimension
    val path = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(center, radius))
    }
    val cut = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(Offset(center.x + radius * 0.55f, center.y - radius * 0.35f), radius * 0.85f))
    }
    path.op(path, cut, androidx.compose.ui.graphics.PathOperation.Difference)
    drawPath(path, Moon)
}

/** A cloud [w] wide (fraction of the icon) centred on [c]. */
private fun DrawScope.cloud(c: Offset, w: Float, color: Color) {
    val center = p(c)
    val u = w * size.minDimension
    drawCircle(color, u * 0.28f, Offset(center.x - u * 0.24f, center.y + u * 0.04f))
    drawCircle(color, u * 0.36f, Offset(center.x + u * 0.06f, center.y - u * 0.1f))
    drawCircle(color, u * 0.26f, Offset(center.x + u * 0.34f, center.y + u * 0.06f))
    drawRoundRect(
        color, Offset(center.x - u * 0.5f, center.y), Size(u * 1.1f, u * 0.32f),
        androidx.compose.ui.geometry.CornerRadius(u * 0.16f),
    )
}

private fun DrawScope.drops(n: Int, short: Boolean = false) {
    val len = size.height * (if (short) 0.1f else 0.18f)
    val xs = when (n) { 2 -> listOf(0.38f, 0.6f); else -> listOf(0.3f, 0.5f, 0.7f) }
    xs.forEachIndexed { i, x ->
        val top = size.height * (0.68f + (i % 2) * 0.06f)
        drawLine(Drop, Offset(size.width * x, top), Offset(size.width * x - len * 0.35f, top + len),
            strokeWidth = size.minDimension * 0.07f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.flakes() {
    listOf(0.32f to 0.74f, 0.52f to 0.84f, 0.7f to 0.72f).forEach { (x, y) ->
        val c = Offset(size.width * x, size.height * y)
        val r = size.minDimension * 0.07f
        for (k in 0 until 3) rotate(k * 60f, c) {
            drawLine(Snow, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = size.minDimension * 0.04f, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.bolt() {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.52f, h * 0.55f); lineTo(w * 0.38f, h * 0.78f); lineTo(w * 0.5f, h * 0.78f)
        lineTo(w * 0.42f, h * 0.97f); lineTo(w * 0.66f, h * 0.7f); lineTo(w * 0.54f, h * 0.7f); lineTo(w * 0.62f, h * 0.55f); close()
    }
    drawPath(path, Bolt)
}

private fun DrawScope.fog() {
    listOf(0.32f, 0.48f, 0.64f).forEachIndexed { i, y ->
        val inset = if (i == 1) 0.12f else 0.2f
        drawLine(CloudDark, Offset(size.width * inset, size.height * y), Offset(size.width * (1 - inset), size.height * y),
            strokeWidth = size.minDimension * 0.08f, cap = StrokeCap.Round)
    }
}

/** Background tint for a temperature: blue when cold, green when mild, amber and red when hot. */
fun tempColor(t: Double?, dark: Boolean): Color {
    if (t == null) return Color.Transparent
    val stops = listOf(
        0.0 to Color(0xFF8DB7F0), 8.0 to Color(0xFFB9D7F7), 14.0 to Color(0xFFCFEBD5),
        20.0 to Color(0xFFFCE9A9), 25.0 to Color(0xFFFFCC8A), 30.0 to Color(0xFFFFA48A),
    )
    val c = when {
        t <= stops.first().first -> stops.first().second
        t >= stops.last().first -> stops.last().second
        else -> {
            val i = stops.indexOfLast { it.first <= t }
            val (t0, c0) = stops[i]
            val (t1, c1) = stops[i + 1]
            androidx.compose.ui.graphics.lerp(c0, c1, ((t - t0) / (t1 - t0)).toFloat())
        }
    }
    return if (dark) c.copy(alpha = 0.28f) else c.copy(alpha = 0.75f)
}
