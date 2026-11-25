package app.weathertrack.ui.report

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.weathertrack.MainActivity
import app.weathertrack.WeatherApp
import app.weathertrack.core.accuracy.RainLevel
import app.weathertrack.ui.Format
import app.weathertrack.ui.RainLevelButtons
import app.weathertrack.ui.theme.WeatherTrackTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The pop-up a widget opens: Dry / Spitting / Rain / Heavy for the selected place, saved the same
 * way as on Today, plus a button through to the app. It floats over the home screen and closes itself
 * a moment after a report is saved.
 */
class RainReportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as WeatherApp).container
        setContent {
            WeatherTrackTheme {
                val scope = rememberCoroutineScope()
                var saving by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf<String?>(null) }
                var placeName by remember { mutableStateOf<String?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    placeName = runCatching { container.places.selected()?.name }.getOrNull()
                }
                Dialog(onDismissRequest = { finish() }) {
                    Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Right now" + (placeName?.let { " at $it" } ?: ""),
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("How hard is it raining?", style = MaterialTheme.typography.bodyMedium)
                            RainLevelButtons(enabled = !saving && message == null, onPick = { level ->
                                saving = true
                                scope.launch {
                                    message = save(level)
                                    saving = false
                                    delay(1200)
                                    finish()
                                }
                            })
                            (message ?: if (saving) "Saving…" else null)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = {
                                    startActivity(Intent(this@RainReportActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                                    finish()
                                }) { Text("Open WeatherTrack") }
                                TextButton(onClick = { finish() }) { Text("Close") }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Saves [level] for the selected place with the stored forecast and radar for now. Returns what to show. */
    private suspend fun save(level: RainLevel): String {
        val c = (application as WeatherApp).container
        return runCatching {
            val place = c.places.selected() ?: return "Add a place in the app first."
            val forecast = runCatching { c.weather.cachedForecast(place)?.forecast }.getOrNull()
            val radar = runCatching { c.radarSpot.check(place.latitude, place.longitude) }.getOrNull()
            c.forecastCheck.reportNow(place, level, forecast, radar)
            "Thanks. Saved \"${level.label.lowercase()}\" at ${Format.time(System.currentTimeMillis() / 1000, Format.zone(place.timezone))}."
        }.getOrElse { "Couldn't save that: ${it.message}" }
    }
}
