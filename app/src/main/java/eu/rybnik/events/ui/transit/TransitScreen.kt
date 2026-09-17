package eu.rybnik.events.ui.transit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.rybnik.events.Graph
import eu.rybnik.events.data.transit.Departure
import eu.rybnik.events.data.transit.RouteEntity
import eu.rybnik.events.data.transit.StopSuggestion
import eu.rybnik.events.ui.common.EmptyState
import eu.rybnik.events.ui.common.ErrorBanner
import eu.rybnik.events.ui.common.TIME_FMT
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransitUi(
    val query: String = "",
    val stops: List<StopSuggestion> = emptyList(),
    val routes: List<RouteEntity> = emptyList(),
    val selectedStop: String? = null,
    val departures: List<Departure> = emptyList(),
    val routeStops: List<StopSuggestion> = emptyList(),
    val selectedRoute: RouteEntity? = null,
    val favourites: Set<String> = emptySet(),
    val importing: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val ready: Boolean = false,
)

class TransitViewModel : ViewModel() {
    private val _ui = MutableStateFlow(TransitUi())
    val ui: StateFlow<TransitUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            Graph.prefs.settings.collect { s ->
                _ui.update { it.copy(favourites = s.favouriteStopIds) }
            }
        }
        viewModelScope.launch {
            Graph.transitRepo.status.collect { st ->
                _ui.update {
                    it.copy(
                        importing = st.importing,
                        progress = st.progress,
                        error = st.error,
                        ready = st.ready,
                    )
                }
                if (st.ready && _ui.value.stops.isEmpty()) loadInitial()
            }
        }
        viewModelScope.launch {
            Graph.transitRepo.checkReady()
            if (!Graph.transitRepo.status.value.ready) ensureTimetable()
        }
    }

    fun ensureTimetable(force: Boolean = false) {
        viewModelScope.launch { Graph.transitRepo.importTimetable(force) }
    }

    private fun loadInitial() {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    stops = Graph.transitRepo.searchStops(""),
                    routes = Graph.transitRepo.routes(),
                )
            }
        }
    }

    fun search(q: String) {
        _ui.update { it.copy(query = q) }
        viewModelScope.launch {
            _ui.update { it.copy(stops = Graph.transitRepo.searchStops(q)) }
        }
    }

    fun selectStop(name: String) {
        _ui.update { it.copy(selectedStop = name, selectedRoute = null) }
        refreshDepartures()
    }

    fun refreshDepartures() {
        val stop = _ui.value.selectedStop ?: return
        viewModelScope.launch {
            _ui.update { it.copy(departures = Graph.transitRepo.nextDepartures(stop)) }
        }
    }

    fun selectRouteById(routeId: String) {
        val route = _ui.value.routes.firstOrNull { it.id == routeId } ?: return
        selectRoute(route)
    }

    fun selectRoute(route: RouteEntity) {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    selectedRoute = route,
                    selectedStop = null,
                    routeStops = Graph.transitRepo.routeStops(route.id),
                )
            }
        }
    }

    fun clearSelection() = _ui.update {
        it.copy(selectedStop = null, selectedRoute = null, departures = emptyList())
    }

    fun toggleFavourite(stopName: String) {
        viewModelScope.launch { Graph.prefs.toggleFavouriteStop(stopName) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitScreen() {
    val vm: TransitViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    var showRoutes by remember { mutableStateOf(false) }

    // Re-query while a stop is open so the countdown ticks down and departed buses drop
    // off without the user reaching for refresh. Keyed on the stop, so it stops by itself
    // when they navigate away.
    LaunchedEffect(ui.selectedStop) {
        if (ui.selectedStop == null) return@LaunchedEffect
        while (true) {
            delay(20_000)
            vm.refreshDepartures()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.selectedStop ?: ui.selectedRoute?.let { "Linia ${it.shortName}" } ?: "Komunikacja") },
                actions = {
                    if (ui.selectedStop != null) {
                        IconButton(onClick = { vm.toggleFavourite(ui.selectedStop!!) }) {
                            val fav = ui.selectedStop in ui.favourites
                            Icon(
                                if (fav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Ulubiony przystanek",
                            )
                        }
                        IconButton(onClick = vm::refreshDepartures) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Odśwież")
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            if (ui.importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    ui.progress ?: "Wczytywanie rozkładu…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }

            ui.error?.let { ErrorBanner(it) { vm.ensureTimetable(force = true) } }

            if (!ui.ready) {
                EmptyState(
                    icon = Icons.Outlined.DirectionsBus,
                    title = "Rozkład nie jest jeszcze wczytany",
                    subtitle = "Pobierzemy aktualny rozkład KM Rybnik (ok. 0,6 MB) i zapiszemy " +
                        "go na telefonie, żeby działał offline.",
                    action = {
                        Button(onClick = { vm.ensureTimetable(force = true) }) {
                            Text("Pobierz rozkład")
                        }
                    },
                )
                return@Column
            }

            when {
                ui.selectedStop != null -> DeparturesList(
                    departures = ui.departures,
                    onRouteClick = vm::selectRouteById,
                    onBack = { vm.clearSelection() },
                )
                ui.selectedRoute != null -> RouteStopList(ui.routeStops, vm::selectStop) {
                    vm.clearSelection()
                }

                else -> {
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        SegmentedButton(
                            selected = !showRoutes,
                            onClick = { showRoutes = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Przystanki") }
                        SegmentedButton(
                            selected = showRoutes,
                            onClick = { showRoutes = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Linie") }
                    }

                    if (showRoutes) {
                        LazyColumn {
                            items(ui.routes, key = { it.id }) { route ->
                                Card(
                                    onClick = { vm.selectRoute(route) },
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 3.dp),
                                ) {
                                    Row(
                                        Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            route.shortName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(56.dp),
                                        )
                                        Text(
                                            route.longName,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = ui.query,
                            onValueChange = vm::search,
                            label = { Text("Szukaj przystanku") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(8.dp))

                        val favourites = ui.favourites.toList().sorted()
                        LazyColumn {
                            if (favourites.isNotEmpty() && ui.query.isBlank()) {
                                items(favourites, key = { "fav-$it" }) { name ->
                                    StopRow(name, favourite = true) { vm.selectStop(name) }
                                }
                                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                            }
                            items(ui.stops, key = { it.id }) { stop ->
                                StopRow(stop.name, favourite = stop.name in ui.favourites) {
                                    vm.selectStop(stop.name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StopRow(name: String, favourite: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (favourite) Icons.Filled.Star else Icons.Outlined.DirectionsBus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DeparturesList(
    departures: List<Departure>,
    onRouteClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (departures.isEmpty()) {
        EmptyState(
            Icons.Outlined.DirectionsBus,
            "Brak odjazdów",
            "Dziś z tego przystanku nic już nie odjeżdża.",
            action = { Button(onClick = onBack) { Text("Wróć do listy") } },
        )
        return
    }
    LazyColumn {
        items(departures) { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onRouteClick(d.routeId) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    d.line,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(48.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(d.headsign, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        d.time.format(TIME_FMT) + if (d.afterMidnight) " (nocny)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when {
                        d.inMinutes <= 0L -> "teraz"
                        d.inMinutes < 60L -> "${d.inMinutes} min"
                        else -> d.time.format(TIME_FMT)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (d.inMinutes < 15L) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun RouteStopList(
    stops: List<StopSuggestion>,
    onStopClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (stops.isEmpty()) {
        EmptyState(
            Icons.Outlined.DirectionsBus,
            "Brak przystanków",
            action = { Button(onClick = onBack) { Text("Wróć") } },
        )
        return
    }
    LazyColumn {
        items(stops, key = { it.id }) { stop ->
            StopRow(stop.name, favourite = false) { onStopClick(stop.name) }
        }
    }
}
