package com.adminstack.rybnik.ui.transit

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
import androidx.compose.material.icons.outlined.SwapVert
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adminstack.rybnik.Graph
import com.adminstack.rybnik.data.transit.Departure
import com.adminstack.rybnik.data.transit.Journey
import com.adminstack.rybnik.data.transit.RouteEntity
import com.adminstack.rybnik.data.transit.StopSuggestion
import com.adminstack.rybnik.ui.common.EmptyState
import com.adminstack.rybnik.ui.common.ErrorBanner
import com.adminstack.rybnik.ui.common.TIME_FMT
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which end of the journey the suggestion list belongs to. */
enum class JourneyField { FROM, TO }

enum class TransitMode(val label: String) {
    STOPS("Przystanki"),
    ROUTES("Linie"),
    JOURNEYS("Połączenia"),
}

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
    val expired: Boolean = false,
    val fromQuery: String = "",
    val toQuery: String = "",
    val suggestions: List<String> = emptyList(),
    val activeField: JourneyField = JourneyField.FROM,
    val journeys: List<Journey> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
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
                        expired = st.expired,
                    )
                }
                if (st.ready && _ui.value.stops.isEmpty()) loadInitial()
            }
        }
        viewModelScope.launch { Graph.transitRepo.syncTimetable() }
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

    fun setFrom(q: String) {
        _ui.update { it.copy(fromQuery = q, searched = false, activeField = JourneyField.FROM) }
        suggest(q)
    }

    fun setTo(q: String) {
        _ui.update { it.copy(toQuery = q, searched = false, activeField = JourneyField.TO) }
        suggest(q)
    }

    /**
     * Tapping into a field makes it the target for the suggestion list, and re-runs the
     * lookup for whatever that field already contains — otherwise the list would still be
     * showing matches for the other end of the journey.
     */
    fun focusField(field: JourneyField) {
        if (_ui.value.activeField == field) return
        _ui.update { it.copy(activeField = field) }
        suggest(if (field == JourneyField.FROM) _ui.value.fromQuery else _ui.value.toQuery)
    }

    fun pickSuggestion(name: String) {
        if (_ui.value.activeField == JourneyField.FROM) {
            _ui.update { it.copy(fromQuery = name, searched = false) }
        } else {
            _ui.update { it.copy(toQuery = name, searched = false) }
        }
        clearSuggestions()
    }

    fun clearSuggestions() = _ui.update { it.copy(suggestions = emptyList()) }

    /** Suggestions are stop names, not platforms: the same name covers both directions. */
    private fun suggest(q: String) {
        if (q.length < 3) {
            clearSuggestions()
            return
        }
        viewModelScope.launch {
            val names = Graph.transitRepo.searchStops(q).map { it.name }.distinct().take(8)
            _ui.update { it.copy(suggestions = names) }
        }
    }

    fun swapEnds() = _ui.update {
        it.copy(fromQuery = it.toQuery, toQuery = it.fromQuery, searched = false, journeys = emptyList())
    }

    fun findJourneys() {
        val from = _ui.value.fromQuery.trim()
        val to = _ui.value.toQuery.trim()
        if (from.isBlank() || to.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(searching = true, journeys = emptyList(), suggestions = emptyList()) }
            val found = runCatching { Graph.transitRepo.planJourneys(from, to) }.getOrDefault(emptyList())
            _ui.update { it.copy(journeys = found, searching = false, searched = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitScreen() {
    val vm: TransitViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    var mode by remember { mutableStateOf(TransitMode.STOPS) }

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
                    val stop = ui.selectedStop
                    if (stop == null && ui.ready && !ui.importing) {
                        // The only way back from a stale timetable used to be clearing
                        // app data, because the download button hid itself once there
                        // was anything in the database.
                        IconButton(onClick = { vm.ensureTimetable(force = true) }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Pobierz rozkład na nowo")
                        }
                    }
                    if (stop != null) {
                        IconButton(onClick = { vm.toggleFavourite(stop) }) {
                            val fav = stop in ui.favourites
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

            if (ui.expired && ui.error == null) {
                ErrorBanner(
                    "Rozkład stracił ważność i nie ma jeszcze nowszego. Godziny mogą być " +
                        "nieaktualne, sprawdź je u przewoźnika.",
                ) { vm.ensureTimetable(force = true) }
            }

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
                    expired = ui.expired,
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
                        TransitMode.entries.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = mode == entry,
                                onClick = { mode = entry },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index, TransitMode.entries.size
                                ),
                            ) { Text(entry.label) }
                        }
                    }

                    if (mode == TransitMode.JOURNEYS) {
                        JourneySearch(
                            ui = ui,
                            onFrom = vm::setFrom,
                            onTo = vm::setTo,
                            onSwap = vm::swapEnds,
                            onSearch = vm::findJourneys,
                            onFocusField = vm::focusField,
                            onPickSuggestion = vm::pickSuggestion,
                        )
                    } else if (mode == TransitMode.ROUTES) {
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

/**
 * Skąd/dokąd over the imported timetable. The fields match loosely on purpose: typing a
 * district like "Boguszowice Stare" searches from all of its platforms at once, which is
 * how people phrase the question, while picking a suggestion narrows it to one stop.
 */
@Composable
private fun JourneySearch(
    ui: TransitUi,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
    onSwap: () -> Unit,
    onSearch: () -> Unit,
    onFocusField: (JourneyField) -> Unit,
    onPickSuggestion: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = ui.fromQuery,
                    onValueChange = onFrom,
                    label = { Text("Skąd") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) onFocusField(JourneyField.FROM) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.toQuery,
                    onValueChange = onTo,
                    label = { Text("Dokąd") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) onFocusField(JourneyField.TO) },
                )
            }
            IconButton(onClick = onSwap, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Outlined.SwapVert, contentDescription = "Zamień miejscami")
            }
        }

        // The button sits above the suggestions on purpose: with the list under the fields
        // it was pushed off screen as soon as anyone started typing.
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSearch,
            enabled = ui.fromQuery.isNotBlank() && ui.toQuery.isNotBlank() && !ui.searching,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text(if (ui.searching) "Szukam…" else "Szukaj połączeń") }

        if (ui.suggestions.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                // Saying where a tap will land removes the guesswork the old version had.
                Text(
                    if (ui.activeField == JourneyField.FROM) "Wstaw do: Skąd" else "Wstaw do: Dokąd",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ui.suggestions.take(4).forEach { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickSuggestion(name) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            ui.searching -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.searched && ui.journeys.isEmpty() -> EmptyState(
                icon = Icons.Outlined.DirectionsBus,
                title = "Brak połączeń",
                subtitle = "W ciągu najbliższych trzech godzin nic tędy nie jedzie, także " +
                    "z jedną przesiadką. Sprawdź pisownię przystanku albo spróbuj później.",
            )
            else -> LazyColumn {
                items(ui.journeys, key = { it.departure.toString() + it.arrival }) { journey ->
                    JourneyCard(journey)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun JourneyCard(journey: Journey) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${journey.departureTime.format(TIME_FMT)} " +
                        "→ ${journey.arrivalTime.format(TIME_FMT)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${journey.totalMinutes} min",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            journey.legs.forEach { leg ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        leg.line,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(44.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${leg.departureTime.format(TIME_FMT)} ${leg.fromStop}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${leg.arrivalTime.format(TIME_FMT)} ${leg.toStop}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            journey.transferWaitMinutes?.let { wait ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "Przesiadka: ${journey.transferStop}, $wait min oczekiwania",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    expired: Boolean,
    onRouteClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (departures.isEmpty()) {
        EmptyState(
            Icons.Outlined.DirectionsBus,
            "Brak odjazdów",
            // An expired timetable produces exactly the same empty result as a stop that
            // is done for the day, and saying "nothing departs today" at nine in the
            // morning is worse than saying nothing: it sounds authoritative and is wrong.
            if (expired) {
                "Zapisany rozkład stracił ważność, więc nie znamy dzisiejszych godzin. " +
                    "Spróbuj pobrać go na nowo."
            } else {
                "Dziś z tego przystanku nic już nie odjeżdża."
            },
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
