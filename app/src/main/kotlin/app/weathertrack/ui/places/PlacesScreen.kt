package app.weathertrack.ui.places

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.weathertrack.WeatherApp
import app.weathertrack.core.model.GeoPlace
import app.weathertrack.data.DeviceLocation
import app.weathertrack.data.PlaceRepository
import app.weathertrack.data.WeatherRepository
import app.weathertrack.data.db.PlaceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlacesViewModel(
    private val app: WeatherApp,
    private val placeRepo: PlaceRepository,
    private val weather: WeatherRepository,
) : ViewModel() {
    val places: StateFlow<List<PlaceEntity>> =
        placeRepo.places.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selectedId: StateFlow<Long> = placeRepo.selectedId
    val results = MutableStateFlow<List<GeoPlace>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            message.value = null
            runCatching { weather.searchPlaces(query.trim()) }
                .onSuccess { results.value = it; if (it.isEmpty()) message.value = "No places found" }
                .onFailure { message.value = it.message ?: "Search failed" }
        }
    }

    fun add(geo: GeoPlace) {
        viewModelScope.launch {
            val id = placeRepo.add(geo)
            placeRepo.select(id)
            results.value = emptyList()
        }
    }

    fun select(id: Long) = placeRepo.select(id)

    fun delete(place: PlaceEntity) {
        viewModelScope.launch { placeRepo.delete(place) }
    }

    fun useDeviceLocation() {
        viewModelScope.launch {
            val loc = DeviceLocation.current(app)
            if (loc == null) {
                message.value = "Couldn't get your location. Is location turned on?"
            } else {
                placeRepo.select(placeRepo.setDeviceLocation(loc.latitude, loc.longitude))
                message.value = null
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WeatherApp
                PlacesViewModel(app, app.container.places, app.container.weather)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(vm: PlacesViewModel = viewModel(factory = PlacesViewModel.Factory)) {
    val places by vm.places.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.useDeviceLocation()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Places") })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search any city or town") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { vm.search(query) }) { Icon(Icons.Filled.Search, "Search") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                )
            }
            item {
                FilledTonalButton(onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                    Icon(Icons.Filled.LocationOn, null)
                    Text("  Use my location")
                }
            }
            message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.error) } }
            if (results.isNotEmpty()) {
                item { Text("Search results", style = MaterialTheme.typography.titleSmall) }
                items(results) { r ->
                    ListItem(
                        headlineContent = { Text(r.name) },
                        supportingContent = { Text(listOfNotNull(r.admin1, r.country).joinToString(", ")) },
                        trailingContent = {
                            IconButton(onClick = { vm.add(r) }) { Icon(Icons.Filled.Add, "Add ${r.name}") }
                        },
                        modifier = Modifier.clickable { vm.add(r) },
                    )
                }
                item { HorizontalDivider() }
            }
            item { Text("Saved places", style = MaterialTheme.typography.titleSmall) }
            items(places, key = { it.id }) { p ->
                ListItem(
                    leadingContent = {
                        if (p.id == selected) Icon(Icons.Filled.Check, "Selected")
                    },
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        val where = listOfNotNull(p.region, p.country).distinct().joinToString(", ").ifEmpty {
                            String.format(java.util.Locale.US, "%.2f, %.2f", p.latitude, p.longitude)
                        }
                        Text(if (p.isDeviceLocation && p.name != "My location") "My location · $where" else where)
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { vm.delete(p) }) { Icon(Icons.Filled.Delete, "Remove ${p.name}") }
                        }
                    },
                    modifier = Modifier.clickable { vm.select(p.id) }.padding(0.dp),
                )
            }
        }
    }
}
