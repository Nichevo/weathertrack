package app.weathertrack.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Radar-style icon (two rings and a sweep) for the Rain map tab. */
val RadarIcon: ImageVector = ImageVector.Builder("Radar", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        // Outer ring, r = 9.
        moveTo(21f, 12f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 3f, y1 = 12f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 21f, y1 = 12f)
        // Inner ring, r = 4.
        moveTo(16f, 12f)
        arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 8f, y1 = 12f)
        arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 16f, y1 = 12f)
        // Sweep.
        moveTo(12f, 12f)
        lineTo(18f, 8f)
    }
}.build()
