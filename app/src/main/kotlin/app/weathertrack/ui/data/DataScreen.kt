package app.weathertrack.ui.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.weathertrack.BuildConfig
import app.weathertrack.WeatherApp
import app.weathertrack.export.DataExporter
import app.weathertrack.ui.Format
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen() {
    val app = LocalContext.current.applicationContext as WeatherApp
    val db = app.container.database
    val exporter = app.container.exporter
    val scope = rememberCoroutineScope()

    val pulls by db.rawPullDao().count().collectAsState(initial = 0)
    val bytes by db.rawPullDao().totalBodyBytes().collectAsState(initial = 0L)
    val first by db.rawPullDao().firstFetchedAt().collectAsState(initial = null)
    val hours by db.forecastDao().hourCount().collectAsState(initial = 0)
    val days by db.forecastDao().dayCount().collectAsState(initial = 0)
    var status by remember { mutableStateOf<String?>(null) }
    var pendingTable by remember { mutableStateOf<DataExporter.Table?>(null) }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val table = pendingTable
        if (uri != null && table != null) scope.launch {
            status = runCatching { exporter.exportCsv(table, uri) }
                .fold({ "Exported $it rows from ${table.sqlName}" }, { "Export failed: ${it.message}" })
        }
    }
    val dbLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.sqlite3"),
    ) { uri ->
        if (uri != null) scope.launch {
            status = runCatching { exporter.exportSqlite(uri) }
                .fold({ "Exported database (${Format.bytes(it)})" }, { "Export failed: ${it.message}" })
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Your data") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Stored on this phone", style = MaterialTheme.typography.titleMedium)
                    Text("$pulls raw API responses (${Format.bytes(bytes)} of JSON)")
                    Text("$hours hourly forecast values as issued")
                    Text("$days daily forecast values as issued")
                    first?.let { Text("Collecting since ${Format.ago(it)}") }
                }
            }
            Text(
                "Every forecast is saved exactly as it was issued, stamped with when it was fetched. " +
                    "A background job pulls a fresh forecast for each saved place about once an hour.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Export CSV", style = MaterialTheme.typography.titleMedium)
            DataExporter.Table.entries.forEach { t ->
                OutlinedButton(
                    onClick = { pendingTable = t; csvLauncher.launch("weathertrack-${t.sqlName}-${LocalDate.now()}.csv") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(t.label) }
            }
            Text("Export database", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { dbLauncher.launch("weathertrack-${LocalDate.now()}.sqlite") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Whole SQLite database") }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
