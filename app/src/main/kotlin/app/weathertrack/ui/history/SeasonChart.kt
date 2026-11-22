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
import app.weathertrack.core.seasonal.EnsoEffect
import app.weathertrack.core.seasonal.EnsoPhase
import app.weathertrack.core.seasonal.Outlook
import app.weathertrack.core.seasonal.OutlookView
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

/** One slot on the 24-month chart: observed for the past, outlook for the future. */
private class Slot(
    val month: java.time.YearMonth,
    val season: Season,
    val rain: Double?,
    val normalRain: Double?,
    val temp: Double?,
    val normalTemp: Double?,
    val future: Boolean,
    /** Typical rain and temperature in years like the current El Niño or La Niña phase. */
    val phaseRain: Double? = null,
    val phaseTemp: Double? = null,
)

private fun phaseColor(p: EnsoPhase) = when (p) {
    EnsoPhase.EL_NINO -> Color(0xFFC0392B)
    EnsoPhase.LA_NINA -> Color(0xFF138A8A)
    EnsoPhase.NEUTRAL -> Color.Gray
}

/**
 * Rain bars and temperature lines for the last 12 months (observed) and the next 12 (the ECMWF
 * seasonal model where it reaches, then normals with what El Niño or La Niña years have done here).
 */
@Composable
fun SeasonCard(v: SeasonView, outlook: OutlookView? = null) {
    val first = v.months.first().month
    val last = v.months.last().month
    val phase = outlook?.enso?.phase ?: EnsoPhase.NEUTRAL
    val slots = v.months.map { Slot(it.month, it.season, it.rain, it.normalRain, it.temp, it.normalTemp, future = false) } +
        outlook?.months.orEmpty().map { m ->
            val e = m.effect(phase)
            val beyond = m.model == null
            Slot(
                m.month, m.season, m.rain, m.normalRain, m.temp, m.normalTemp, future = true,
                phaseRain = if (beyond) e?.rainRatio?.let { r -> m.normalRain?.times(r) } else null,
                phaseTemp = if (beyond) e?.tempShift?.let { d -> m.normalTemp?.plus(d) } else null,
            )
        }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (outlook != null) "Last 12 months and the year ahead" else "Last 12 months by season",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${monthName(first)} ${first.year} to ${monthName(last)} ${last.year}, against the ${v.normalLabel} average for each month." +
                    if (outlook != null) " Right of the line is the outlook." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Chart(slots, phase)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Key(RainColor, "Rain")
                Key(MaterialTheme.colorScheme.outlineVariant, "Avg rain")
                Key(TempColor, "Temp", line = true)
                Key(MaterialTheme.colorScheme.outline, "Avg temp", line = true)
            }
            if (outlook != null && phase != EnsoPhase.NEUTRAL && slots.any { it.phaseTemp != null || it.phaseRain != null }) {
                Key(phaseColor(phase), "Typical in ${phase.label} years (past the model)", line = true)
            }
            v.seasons.forEach { SeasonLine(it) }
            if (outlook != null) OutlookSection(outlook)
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
private fun Chart(months: List<Slot>, phase: EnsoPhase) {
    val avgBar = MaterialTheme.colorScheme.outlineVariant
    val avgLine = MaterialTheme.colorScheme.outline
    val nowLine = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val pColor = phaseColor(phase)
    val maxRain = (months.flatMap { listOfNotNull(it.rain, it.normalRain, it.phaseRain) }.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val temps = months.flatMap { listOfNotNull(it.temp, it.normalTemp, it.phaseTemp) }
    val tLo = (temps.minOrNull() ?: 0.0) - 1
    val tHi = (temps.maxOrNull() ?: 1.0) + 1
    Canvas(Modifier.fillMaxWidth().height(if (months.size > 12) 240.dp else 220.dp)) {
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

        // Season bands with their names along the top (short names when 24 months share the width).
        var i = 0
        while (i < months.size) {
            val s = months[i].season
            var j = i
            while (j + 1 < months.size && months[j + 1].season == s) j++
            drawRect(seasonTint(s), Offset(left + i * slot, top), Size((j - i + 1) * slot, bottom - top))
            val name = if (months.size > 12 && j - i < 2) s.label.take(3) else s.label
            drawContext.canvas.nativeCanvas.drawText(name, left + (i + j + 1) / 2f * slot, top - 4.dp.toPx(), textPaint)
            i = j + 1
        }

        // Rain: average bar behind, observed or forecast in front (forecast paler).
        months.forEachIndexed { k, m ->
            val x = left + k * slot
            m.normalRain?.let { drawRoundRect(avgBar, Offset(x + slot * 0.18f, ry(it)), Size(slot * 0.64f, bottom - ry(it)), CornerRadius(3f)) }
            val c = if (m.future) RainColor.copy(alpha = 0.5f) else RainColor
            m.rain?.let { drawRoundRect(c, Offset(x + slot * 0.3f, ry(it)), Size(slot * 0.4f, bottom - ry(it)), CornerRadius(3f)) }
            m.phaseRain?.let {
                drawRoundRect(pColor, Offset(x + slot * 0.3f, ry(it)), Size(slot * 0.4f, bottom - ry(it)), CornerRadius(3f), style = Stroke(1.5.dp.toPx()))
            }
            drawContext.canvas.nativeCanvas.drawText(monthName(m.month).take(1), x + slot / 2, size.height - 2.dp.toPx(), textPaint)
        }

        // Temperature lines.
        fun line(values: List<Double?>, color: Color, dashed: Boolean, dots: Boolean = !dashed) {
            val path = Path()
            var started = false
            values.forEachIndexed { k, t ->
                if (t == null) { started = false; return@forEachIndexed }
                val p = Offset(left + (k + 0.5f) * slot, ty(t))
                if (started) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                started = true
                if (dots) drawCircle(color, (if (months.size > 12) 2.5f else 3f) * density, p)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
        }
        line(months.map { it.normalTemp }, avgLine, dashed = true)
        line(months.map { if (it.future) null else it.temp }, TempColor, dashed = false)
        // Forecast temperature joins on from the last observed month.
        val lastPast = months.indexOfLast { !it.future }
        line(months.mapIndexed { k, m -> if (m.future || k == lastPast) m.temp else null }, TempColor.copy(alpha = 0.6f), dashed = true, dots = true)
        line(months.map { it.phaseTemp }, pColor, dashed = true, dots = true)

        // "Now" divider between the last observed month and the outlook.
        val firstFuture = months.indexOfFirst { it.future }
        if (firstFuture > 0) {
            val x = left + firstFuture * slot
            drawLine(nowLine, Offset(x, top), Offset(x, bottom), strokeWidth = 1.5.dp.toPx())
        }

        // Axes labels: rain on the left, temperature on the right.
        textPaint.textAlign = android.graphics.Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText("${maxRain.roundToInt()}mm", 0f, top + 10.dp.toPx(), textPaint)
        drawContext.canvas.nativeCanvas.drawText("0", 0f, bottom, textPaint)
        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText("${tHi.roundToInt()}°", size.width, top + 10.dp.toPx(), textPaint)
        drawContext.canvas.nativeCanvas.drawText("${tLo.roundToInt()}°", size.width, bottom, textPaint)
    }
}

private fun pct(r: Double) = "${(r * 100).roundToInt()}%"

private fun one(t: Double) = String.format(Locale.US, "%.1f°", t)

/** Where El Niño stands, then each coming month in words. */
@Composable
private fun OutlookSection(o: OutlookView) {
    val now = o.enso
    Text("The year ahead", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    if (now != null) {
        val direction = when {
            now.trend == null || kotlin.math.abs(now.trend!!) < 0.2 -> "holding steady"
            now.trend!! > 0 -> "warming (toward El Niño)"
            else -> "cooling (toward La Niña)"
        }
        val status = when {
            now.phase == EnsoPhase.NEUTRAL -> "Neutral: neither El Niño nor La Niña"
            now.official -> now.phase.label
            else -> "${now.phase.label} conditions (not yet a full episode)"
        }
        Text(
            "Pacific now: $status. Ocean index ${String.format(Locale.US, "%+.1f", now.anomaly)}° for the 3 months around " +
                "${monthName(now.month)} ${now.month.year}, $direction.",
            style = MaterialTheme.typography.bodySmall, color = if (now.phase == EnsoPhase.NEUTRAL) Color.Unspecified else phaseColor(now.phase),
            fontWeight = FontWeight.Medium,
        )
    } else {
        Text("Couldn't reach NOAA's El Niño record, so El Niño and La Niña effects are left out for now.", style = MaterialTheme.typography.bodySmall)
    }
    if (o.modelMonths == 0) {
        Text("Couldn't reach the ECMWF seasonal forecast, so the coming months show normals.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            "The first ${o.modelMonths} months are the ECMWF seasonal forecast (51 runs averaged), shown as a shift from normal. " +
                "After that no model reaches, so it shows normal plus what El Niño and La Niña years have done here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val phase = now?.phase ?: EnsoPhase.NEUTRAL
    o.months.forEach { m ->
        val head = "${monthName(m.month)} ${m.month.year}"
        val main = if (m.model != null) {
            listOfNotNull(
                m.temp?.let { t -> "${one(t)}${Outlook.shift(m.model!!.tempAnomaly)?.let { " ($it)" } ?: ""}" },
                m.rain?.let { r -> "${r.roundToInt()} mm (${pct(m.model!!.rainRatio!!)} of normal)" },
            ).joinToString(" · ") + " · ECMWF"
        } else {
            listOfNotNull(m.normalTemp?.let { "normal ${one(it)}" }, m.normalRain?.let { "${it.roundToInt()} mm" }).joinToString(" · ")
        }
        fun eff(e: EnsoEffect?): String? = e?.let {
            val bits = listOfNotNull(Outlook.shift(it.tempShift), it.rainRatio?.let { r -> "${pct(r)} rain" })
            if (bits.isEmpty()) null else "${it.phase.label} years ${bits.joinToString(", ")} (${it.years} yrs)"
        }
        Column {
            Text(head, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(main.ifEmpty { "Not enough history yet" }, style = MaterialTheme.typography.bodySmall)
            listOf(m.elNino, m.laNina).forEach { e ->
                eff(e)?.let { line ->
                    val current = e!!.phase == phase
                    Text(
                        line, style = MaterialTheme.typography.bodySmall,
                        color = if (current) phaseColor(e.phase) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
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
