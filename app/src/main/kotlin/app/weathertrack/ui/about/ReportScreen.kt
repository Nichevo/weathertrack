package app.weathertrack.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.weathertrack.WeatherApp
import app.weathertrack.data.Diagnostics

/** Describe a problem, see exactly what will be shared, then copy it or open a GitHub issue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val diagnostics = (context.applicationContext as WeatherApp).container.diagnostics
    var what by rememberSaveable { mutableStateOf("") }
    var details by remember { mutableStateOf("Collecting details…") }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { details = runCatching { diagnostics.report() }.getOrElse { "Couldn't collect details: ${it.message}" } }
    val full = "What happened:\n${what.ifBlank { "(not described)" }}\n\n```\n$details\n```"
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Report a problem") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = what, onValueChange = { what = it; copied = false },
                label = { Text("What happened, and what did you expect?") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
            Text(
                "These details go with it. Nothing personal is included: no place names, locations, accounts or keys. " +
                    "A GitHub issue on this repository is public, so check before posting.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val title = what.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Problem report"
                    openLink(context, Diagnostics.issueUrl(title, full))
                }, modifier = Modifier.weight(1f)) { Text("Open a GitHub issue") }
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("WeatherTrack problem report", full))
                    copied = true
                }, modifier = Modifier.weight(1f)) { Text(if (copied) "Copied" else "Copy") }
            }
            if (Diagnostics.lastCrash(context) != null) {
                OutlinedButton(onClick = { Diagnostics.clearCrash(context); details = details.substringBefore("\nLast crash:") }) {
                    Text("Forget the last crash")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Text(details, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
