package app.weathertrack.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.weathertrack.WeatherApp
import app.weathertrack.notify.Notifier
import app.weathertrack.settings.AppSettings
import app.weathertrack.work.AlertScheduler
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as WeatherApp).container
    val repo = container.settings
    val s by repo.settings.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }

    // Apply every change to background work and widgets.
    LaunchedEffect(s) {
        AlertScheduler.apply(context, s)
        container.updateWidgets()
    }

    var pending by remember { mutableStateOf<((AppSettings) -> AppSettings)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) pending?.let { repo.update(it) }
        pending = null
    }

    /** Turning a notification on asks for permission first when needed. */
    fun enableNotification(on: Boolean, change: (AppSettings) -> AppSettings) {
        if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(context)) {
            pending = change
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            repo.update(change)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Section("Jacket call")
            Note("The call is YES at these levels and MAYBE a little below them.")
            Stepper("Rain chance", "${s.jacketRainChance}%", { repo.update { it.copy(jacketRainChance = (it.jacketRainChance - 5).coerceAtLeast(10)) } },
                { repo.update { it.copy(jacketRainChance = (it.jacketRainChance + 5).coerceAtMost(95)) } })
            Stepper("Rain amount", String.format(Locale.US, "%.1f mm", s.jacketRainMm),
                { repo.update { it.copy(jacketRainMm = (Math.round((it.jacketRainMm - 0.1) * 10) / 10.0).coerceAtLeast(0.1)) } },
                { repo.update { it.copy(jacketRainMm = Math.round((it.jacketRainMm + 0.1) * 10) / 10.0) } })
            Stepper("Gusts", "${s.jacketGustKmh} km/h", { repo.update { it.copy(jacketGustKmh = (it.jacketGustKmh - 5).coerceAtLeast(15)) } },
                { repo.update { it.copy(jacketGustKmh = (it.jacketGustKmh + 5).coerceAtMost(120)) } })
            Stepper("Feels like at or below", "${s.jacketFeelsLike}°", { repo.update { it.copy(jacketFeelsLike = it.jacketFeelsLike - 1) } },
                { repo.update { it.copy(jacketFeelsLike = it.jacketFeelsLike + 1) } })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Section("Triggers")
            Stepper("Too hot: feels like at or above", "${s.hotFeelsLike}°", { repo.update { it.copy(hotFeelsLike = it.hotFeelsLike - 1) } },
                { repo.update { it.copy(hotFeelsLike = it.hotFeelsLike + 1) } })
            Stepper("Too cold: feels like at or below", "${s.coldFeelsLike}°", { repo.update { it.copy(coldFeelsLike = it.coldFeelsLike - 1) } },
                { repo.update { it.copy(coldFeelsLike = it.coldFeelsLike + 1) } })
            Stepper("Rain soon: within", "${s.rainSoonMinutes} min", { repo.update { it.copy(rainSoonMinutes = (it.rainSoonMinutes - 15).coerceAtLeast(15)) } },
                { repo.update { it.copy(rainSoonMinutes = (it.rainSoonMinutes + 15).coerceAtMost(120)) } })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Section("Walks")
            Note("Your two regular walks. Today shows how each looks; the widget shows the next one.")
            Stepper("Morning walk from", s.walk1Start.format(timeFmt), { repo.update { it.copy(walk1Start = it.walk1Start.minusMinutes(30)) } },
                { repo.update { it.copy(walk1Start = it.walk1Start.plusMinutes(30)) } })
            Stepper("Morning walk until", s.walk1End.format(timeFmt), { repo.update { it.copy(walk1End = it.walk1End.minusMinutes(30)) } },
                { repo.update { it.copy(walk1End = it.walk1End.plusMinutes(30)) } })
            Stepper("Evening walk from", s.walk2Start.format(timeFmt), { repo.update { it.copy(walk2Start = it.walk2Start.minusMinutes(30)) } },
                { repo.update { it.copy(walk2Start = it.walk2Start.plusMinutes(30)) } })
            Stepper("Evening walk until", s.walk2End.format(timeFmt), { repo.update { it.copy(walk2End = it.walk2End.minusMinutes(30)) } },
                { repo.update { it.copy(walk2End = it.walk2End.plusMinutes(30)) } })
            Stepper("Too hot for the dog: feels like", "${s.petHotFeelsLike}°", { repo.update { it.copy(petHotFeelsLike = it.petHotFeelsLike - 1) } },
                { repo.update { it.copy(petHotFeelsLike = it.petHotFeelsLike + 1) } })
            Toggle("Walk heads-up notification", s.notifyWalks) { on -> enableNotification(on) { it.copy(notifyWalks = on) } }
            Stepper("Heads-up before the walk", "${s.walkLeadMinutes} min", { repo.update { it.copy(walkLeadMinutes = (it.walkLeadMinutes - 15).coerceAtLeast(15)) } },
                { repo.update { it.copy(walkLeadMinutes = (it.walkLeadMinutes + 15).coerceAtMost(120)) } })
            Toggle("Only when the walk isn't good", s.walkNotifyOnlyIfNotGood) { on -> repo.update { it.copy(walkNotifyOnlyIfNotGood = on) } }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Section("Home heating and cooling")
            Note("Days with a mean temperature below the heating base count as heating degree days; above the cooling base, as cooling degree days.")
            Stepper("Heating base", "${s.heatingBase}°", { repo.update { it.copy(heatingBase = (it.heatingBase - 1).coerceAtLeast(10)) } },
                { repo.update { it.copy(heatingBase = (it.heatingBase + 1).coerceAtMost(it.coolingBase)) } })
            Stepper("Cooling base", "${s.coolingBase}°", { repo.update { it.copy(coolingBase = (it.coolingBase - 1).coerceAtLeast(it.heatingBase)) } },
                { repo.update { it.copy(coolingBase = (it.coolingBase + 1).coerceAtMost(30)) } })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Section("Notifications")
            Note("For your selected place. Rain-soon checks run every 15 minutes while it is on.")
            Toggle("Rain soon", s.notifyRainSoon) { on -> enableNotification(on) { it.copy(notifyRainSoon = on) } }
            Toggle("Too hot", s.notifyHot) { on -> enableNotification(on) { it.copy(notifyHot = on) } }
            Toggle("Too cold", s.notifyCold) { on -> enableNotification(on) { it.copy(notifyCold = on) } }
            Toggle("Morning jacket call", s.notifyMorning) { on -> enableNotification(on) { it.copy(notifyMorning = on) } }
            Stepper("Morning time", s.morningTime.format(timeFmt), { repo.update { it.copy(morningTime = it.morningTime.minusMinutes(15)) } },
                { repo.update { it.copy(morningTime = it.morningTime.plusMinutes(15)) } })
            Stepper("Quiet from", s.quietStart.format(timeFmt), { repo.update { it.copy(quietStart = it.quietStart.minusMinutes(30)) } },
                { repo.update { it.copy(quietStart = it.quietStart.plusMinutes(30)) } })
            Stepper("Quiet until", s.quietEnd.format(timeFmt), { repo.update { it.copy(quietEnd = it.quietEnd.minusMinutes(30)) } },
                { repo.update { it.copy(quietEnd = it.quietEnd.plusMinutes(30)) } })
            if (permissionDenied) {
                Text("Notifications are blocked. Allow them for WeatherTrack in Android settings.",
                    color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Section("Outside")
            val skinNames = listOf("very fair", "fair", "medium", "olive", "brown", "dark")
            Stepper("Skin type (UV burn time)", "${s.skinType} · ${skinNames[(s.skinType - 1).coerceIn(0, 5)]}",
                { repo.update { it.copy(skinType = (it.skinType - 1).coerceAtLeast(1)) } },
                { repo.update { it.copy(skinType = (it.skinType + 1).coerceAtMost(6)) } })
            Toggle("Cold night / frost alert (evening)", s.notifyColdNight) { on -> enableNotification(on) { it.copy(notifyColdNight = on) } }
            Stepper("Cold night: low at or below", "${s.coldNightTemp}°", { repo.update { it.copy(coldNightTemp = it.coldNightTemp - 1) } },
                { repo.update { it.copy(coldNightTemp = it.coldNightTemp + 1) } })
            Toggle("MetService warnings (NZ)", s.notifyWarnings) { on -> enableNotification(on) { it.copy(notifyWarnings = on) } }
            Note("Red and orange warnings can come through quiet hours.")
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Section("Backup to Dropbox")
            BackupSection(s.backupWeekly) { on -> repo.update { it.copy(backupWeekly = on) } }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Section("Background refresh")
            Note("How often every saved place is pulled and stored.")
            val options = listOf(30 to "30 min", 60 to "1 hour", 180 to "3 hours")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (minutes, label) ->
                    SegmentedButton(
                        selected = s.refreshMinutes == minutes,
                        onClick = { repo.update { it.copy(refreshMinutes = minutes) } },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButtonReset { repo.update { AppSettings() } }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        FilledTonalIconButton(onClick = onMinus) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(value, Modifier.width(88.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
        FilledTonalIconButton(onClick = onPlus) { Icon(Icons.Filled.Add, "Increase $label") }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TextButtonReset(onReset: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onReset) { Text("Reset to defaults") }
}
