package app.weathertrack.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.weathertrack.WeatherApp
import app.weathertrack.ui.Format
import kotlinx.coroutines.launch

/**
 * Dropbox backup with the user's own Dropbox app: paste its app key, approve in the browser,
 * paste the code Dropbox shows. Nothing about the account is kept in the app's code.
 */
@Composable
fun BackupSection(weekly: Boolean, onWeekly: (Boolean) -> Unit) {
    val context = LocalContext.current
    val backup = (context.applicationContext as WeatherApp).container.backup
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(backup.status()) }
    var key by remember { mutableStateOf(status.appKey) }
    var code by remember { mutableStateOf("") }
    var waitingForCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!status.connected) {
            Text(
                "Make a Dropbox app at dropbox.com/developers/apps: Scoped access, App folder, then tick " +
                    "files.content.write and files.content.read under Permissions. Paste its App key here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Dropbox app key") }, singleLine = true)
            Button(enabled = key.length >= 8, onClick = {
                val url = backup.authUrl(key)
                waitingForCode = true
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { message = "Couldn't open the browser" }
            }) { Text("Connect Dropbox") }
            if (waitingForCode) {
                OutlinedTextField(code, { code = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Code from Dropbox") }, singleLine = true)
                Button(enabled = code.isNotEmpty() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        val r = backup.finish(code)
                        busy = false
                        message = r.fold({ "Connected" }, { "Didn't connect: ${it.message}" })
                        if (r.isSuccess) { waitingForCode = false; code = "" }
                        status = backup.status()
                    }
                }) { Text("Finish connecting") }
            }
        } else {
            Text("Connected. Backups go to Apps › your app's folder in Dropbox; the newest 8 are kept.",
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Back up every week (on Wi-Fi)", Modifier.weight(1f))
                Switch(checked = weekly, onCheckedChange = onWeekly)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    message = "Backing up…"
                    scope.launch {
                        val r = backup.backupNow()
                        busy = false
                        message = r.getOrElse { "Backup failed: ${it.message}" }
                        status = backup.status()
                    }
                }) { Text("Back up now") }
                OutlinedButton(onClick = { backup.disconnect(); status = backup.status(); onWeekly(false) }) { Text("Disconnect") }
            }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                message = "Downloading the newest backup to check it…"
                scope.launch {
                    val r = backup.testLatest()
                    busy = false
                    message = r.getOrElse { "Restore test failed: ${it.message}" }
                }
            }) { Text("Test the newest backup restores") }
            Text("Downloads the newest backup, opens it and checks it's complete. Nothing on the phone changes. " +
                "Every backup is also checked before it's uploaded.", style = MaterialTheme.typography.bodySmall)
            backup.lastTest()?.let { Text("Last test: $it", style = MaterialTheme.typography.bodySmall) }
            if (status.lastAtMs > 0) Text("Last backup ${Format.ago(status.lastAtMs)}", style = MaterialTheme.typography.bodySmall)
            status.lastResult?.takeIf { it.startsWith("Failed") }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
