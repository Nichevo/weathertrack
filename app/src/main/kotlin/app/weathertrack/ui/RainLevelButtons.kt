package app.weathertrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.weathertrack.core.accuracy.RainLevel

/** The weather-code icon drawn on each level's button. */
fun RainLevel.iconCode(): Int = when (this) {
    RainLevel.DRY -> 0
    RainLevel.SPITTING -> 51
    RainLevel.RAIN -> 63
    RainLevel.HEAVY -> 82
}

/** Dry / Spitting / Rain / Heavy in one row, shared by Today and the widget pop-up. */
@Composable
fun RainLevelButtons(onPick: (RainLevel) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RainLevel.entries.forEach { level ->
            OutlinedButton(
                onClick = { onPick(level) }, enabled = enabled, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WeatherIcon(level.iconCode(), true, size = 22.dp)
                    Text(level.label, maxLines = 1)
                }
            }
        }
    }
}
