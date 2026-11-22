package app.weathertrack.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.weathertrack.WeatherApp
import app.weathertrack.core.about.NoteSection
import app.weathertrack.core.about.ReleaseNotes

/** What's new in each version, and what's planned next. [startTab] 0 = What's new, 1 = Planned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(startTab: Int = 0, onBack: () -> Unit) {
    val context = LocalContext.current
    val updates = (context.applicationContext as WeatherApp).container.updates
    val changelog = remember { updates.changelog() }
    val roadmap = remember { updates.roadmap() }
    val current = remember { ReleaseNotes.current(changelog, updates.versionName) }
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (tab == 0) "What's new" else "Planned") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("What's new") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Planned") })
        }
        val sections = if (tab == 0) changelog else roadmap
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == 0) item {
                Text("Installed: version ${updates.versionName}", style = MaterialTheme.typography.bodyMedium)
            }
            if (sections.isEmpty()) item { Text("Nothing listed.") }
            items(sections) { s -> SectionCard(s, highlight = tab == 0 && s == current) }
        }
    }
}

@Composable
private fun SectionCard(s: NoteSection, highlight: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (highlight) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.title + if (highlight) " (installed)" else "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            s.lines.forEach { line ->
                Row {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Opens a link (a release page or APK) in the browser. */
fun openLink(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
