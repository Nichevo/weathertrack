package app.weathertrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.weathertrack.ui.history.HistoryScreen
import app.weathertrack.ui.map.RadarIcon
import app.weathertrack.ui.map.RainMapScreen
import app.weathertrack.ui.home.HomeScreen
import app.weathertrack.ui.places.PlacesScreen
import app.weathertrack.ui.settings.SettingsScreen
import app.weathertrack.ui.theme.WeatherTrackTheme

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Today", Icons.Filled.Star),
    Tab("map", "Rain map", RadarIcon),
    Tab("history", "History", Icons.Filled.DateRange),
    Tab("sources", "Sources", Icons.Filled.List),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeatherTrackTheme {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination?.route
                // Bumped on every tap of History in the bottom bar, so it always opens on Past weather.
                var historyReset by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    // Each screen's top app bar handles the status bar inset itself.
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        NavigationBar {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = current == tab.route,
                                    onClick = {
                                        if (tab.route == "history") historyReset++
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = null) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                        composable("home") { HomeScreen(onOpenSettings = { nav.navigate("settings") }) }
                        composable("settings") {
                            SettingsScreen(
                                onOpenPlaces = { nav.navigate("places") }, onOpenAbout = { tab -> nav.navigate("about/$tab") },
                                onOpenReport = { nav.navigate("report") },
                            )
                        }
                        composable("report") { app.weathertrack.ui.about.ReportScreen(onBack = { nav.popBackStack() }) }
                        composable("about/{tab}") { entry ->
                            app.weathertrack.ui.about.AboutScreen(
                                startTab = entry.arguments?.getString("tab")?.toIntOrNull() ?: 0, onBack = { nav.popBackStack() },
                            )
                        }
                        composable("sources") { app.weathertrack.ui.sources.SourcesScreen() }
                        composable("map") { RainMapScreen() }
                        // Forecast check, Home energy and Data are tabs inside History.
                        composable("history") { HistoryScreen(resetKey = historyReset) }
                        composable("places") { PlacesScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
