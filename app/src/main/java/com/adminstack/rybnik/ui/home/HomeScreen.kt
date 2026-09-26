package com.adminstack.rybnik.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adminstack.rybnik.Graph
import com.adminstack.rybnik.data.Event
import com.adminstack.rybnik.data.air.AirState
import com.adminstack.rybnik.data.news.NewsItem
import com.adminstack.rybnik.data.outages.Match
import com.adminstack.rybnik.data.outages.Outage
import com.adminstack.rybnik.data.outages.OutageKind
import com.adminstack.rybnik.data.sport.Highlight
import com.adminstack.rybnik.data.sport.Outcome
import com.adminstack.rybnik.data.transit.Departure
import com.adminstack.rybnik.data.waste.Collection
import com.adminstack.rybnik.ui.common.DAY_FMT
import com.adminstack.rybnik.ui.common.PL
import com.adminstack.rybnik.ui.common.SHORT_DAY_FMT
import com.adminstack.rybnik.ui.common.TIME_FMT
import com.adminstack.rybnik.ui.common.humanLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeUi(
    val air: AirState = AirState(),
    val nextWaste: Collection? = null,
    val wasteAddress: String? = null,
    val departures: List<Departure> = emptyList(),
    val favouriteStop: String? = null,
    val nextEvents: List<Event> = emptyList(),
    val alerts: List<NewsItem> = emptyList(),
    val outages: List<Outage> = emptyList(),
    val sport: Highlight? = null,
    val refreshing: Boolean = false,
)

class HomeViewModel : ViewModel() {
    private val _ui = MutableStateFlow(HomeUi())
    val ui: StateFlow<HomeUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            Graph.airRepo.state.collect { air -> _ui.update { it.copy(air = air) } }
        }
        // The dashboard is kept alive in the back stack, so without watching settings it
        // would keep showing "pick an address" after the user has just picked one.
        viewModelScope.launch {
            Graph.prefs.settings
                .map { it.wasteAddress to it.favouriteStopIds }
                .distinctUntilChanged()
                .collect { refresh() }
        }
        // Events, alerts and the sport card come from cached sources that fill in whenever
        // their fetch lands. Computing them only inside refresh() meant a cold start could
        // render the dashboard a moment too early and leave those cards blank until the
        // user hit refresh by hand — which is exactly what a first launch looks like.
        viewModelScope.launch {
            combine(Graph.eventRepo.data, Graph.newsRepo.data, Graph.sportRepo.data) { _, _, _ -> }
                .collect { recomputeDerived() }
        }
    }

    private suspend fun recomputeDerived() {
        val hidden = Graph.prefs.settings.first().hiddenNewsIds
        _ui.update {
            it.copy(
                nextEvents = upcomingEvents(),
                alerts = Graph.newsRepo.currentAlerts(hidden),
                sport = Graph.sportRepo.highlight(),
            )
        }
    }

    private fun upcomingEvents(): List<Event> = Graph.eventRepo.snapshot()
        .filter { !it.start.toLocalDate().isBefore(LocalDate.now()) }
        .take(3)

    /** Departures only — cheap enough to tick on a timer, unlike the full network refresh. */
    fun tickDepartures() {
        viewModelScope.launch {
            val stop = _ui.value.favouriteStop ?: return@launch
            val fresh = runCatching { Graph.transitRepo.nextDepartures(stop, limit = 4) }
                .getOrDefault(emptyList())
            _ui.update { it.copy(departures = fresh) }
        }
    }

    /** Dismissing from the dashboard also takes the item out of the news list. */
    fun hideAlert(id: String) {
        viewModelScope.launch {
            Graph.prefs.hideNews(id)
            _ui.update { ui -> ui.copy(alerts = ui.alerts.filterNot { it.id == id }) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(refreshing = true) }
            val settings = Graph.prefs.settings.first()

            Graph.airRepo.refresh()

            val waste = settings.wasteAddress?.let { addr ->
                Graph.wasteRepo.schedule().nextCollection(addr.rejonId)
            }

            val stopName = settings.favouriteStopIds.firstOrNull()
            val departures = stopName?.let { name ->
                runCatching { Graph.transitRepo.nextDepartures(name, limit = 4) }
                    .getOrDefault(emptyList())
            } ?: emptyList()

            Graph.outageRepo.refresh(settings.wasteAddress)
            val outages = Graph.outageRepo.state.value.outages

            // These three only need kicking; the collector above owns their UI state.
            // Writing them from here too would let a value captured at the top of this
            // function land after the collector already stored a fresher one, which is
            // how the alert card came back empty on a cold start.
            Graph.eventRepo.refresh()
            Graph.newsRepo.refresh()
            Graph.sportRepo.refresh()

            _ui.update {
                it.copy(
                    nextWaste = waste,
                    wasteAddress = settings.wasteAddress?.pretty,
                    departures = departures,
                    favouriteStop = stopName,
                    outages = outages,
                    refreshing = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenWaste: () -> Unit,
    onOpenTransit: () -> Unit,
    onOpenEvents: () -> Unit,
    onOpenNews: () -> Unit,
    onOpenAir: () -> Unit,
    onOpenSport: () -> Unit,
    onEventClick: (String) -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    LaunchedEffect(ui.favouriteStop) {
        if (ui.favouriteStop == null) return@LaunchedEffect
        while (true) {
            delay(20_000)
            vm.tickDepartures()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Rybnik", style = MaterialTheme.typography.titleLarge)
                        Text(
                            LocalDate.now().format(DAY_FMT).replaceFirstChar { it.uppercase(PL) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Odśwież")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { AirCard(ui.air, onOpenAir) }

            items(ui.outages.size) { i -> OutageCard(ui.outages[i]) }

            if (ui.alerts.isNotEmpty()) {
                item {
                    AlertCarousel(ui.alerts, onOpenNews, vm::hideAlert)
                }
            }

            item { WasteCard(ui.nextWaste, ui.wasteAddress, onOpenWaste) }

            item { TransitCard(ui.favouriteStop, ui.departures, onOpenTransit) }

            item { EventsCard(ui.nextEvents, onEventClick, onOpenEvents) }

            ui.sport?.let { item { SportCard(it, onOpenSport) } }
        }
    }
}

/** Power cuts affecting the saved address. Absent entirely when there are none. */
@Composable
private fun OutageCard(outage: Outage) {
    val planned = outage.kind == OutageKind.PLANNED
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bolt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (planned) "Planowane wyłączenie prądu" else "Awaria zasilania",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(outage.from.toLocalDate().humanLabel())
                    append(", ")
                    append(outage.from.toLocalTime().format(TIME_FMT))
                    outage.to?.let { append("-${it.toLocalTime().format(TIME_FMT)}") }
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (outage.match == Match.STREET) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wymieniono Twoją ulicę, ale nie udało się odczytać numerów. " +
                        "Sprawdź opis.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(outage.message, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        }
    }
}

/**
 * One line about the local clubs. A result stays up for a few days after the match,
 * then the next kick-off takes over; between seasons it says so instead of passing a
 * months-old score off as news.
 */
@Composable
private fun SportCard(highlight: Highlight, onClick: () -> Unit) {
    val match = highlight.match
    val stale = highlight.isResult && match.date.isBefore(LocalDate.now().minusDays(7))

    HomeCard(Icons.Outlined.SportsSoccer, highlight.team.kind.label, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // Club names cannot be declined, so "ROW Rybnik u Płomień" would be broken
                // Polish. Naming the opponent and tagging home or away sidesteps grammar
                // and matches how the Sport screen lists the same match.
                Text(
                    match.opponent,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        stale -> listOfNotNull(
                            if (match.isHome) "u siebie" else "wyjazd",
                            "ostatni mecz sezonu",
                            match.date.format(SHORT_DAY_FMT),
                        ).joinToString(" · ")
                        highlight.isResult -> listOfNotNull(
                            if (match.isHome) "u siebie" else "wyjazd",
                            match.date.humanLabel(),
                            match.competition,
                        ).joinToString(" · ")
                        else -> listOfNotNull(
                            if (match.isHome) "u siebie" else "wyjazd",
                            match.date.humanLabel(),
                            match.time?.format(TIME_FMT),
                            match.competition,
                        ).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            match.scoreLabel?.let {
                Spacer(Modifier.width(10.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.titleLarge,
                    color = when (match.outcome) {
                        Outcome.WIN -> MaterialTheme.colorScheme.primary
                        Outcome.LOSS -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    body: @Composable () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            body()
        }
    }
}

@Composable
private fun AirCard(air: AirState, onClick: () -> Unit) {
    val severity = air.severity
    HomeCard(Icons.Outlined.Air, "Jakość powietrza", onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).background(Color(severity.color), CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    air.pm10?.let { "PM10 ${it.value.toInt()} µg/m³" } ?: "Brak danych",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(severity.label, air.indexLabel?.let { "indeks: $it" })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Several disruptions can be live at once, and pinning one of them to the dashboard for
 * days buries the rest. This cycles through them instead.
 */
@Composable
private fun AlertCarousel(
    alerts: List<NewsItem>,
    onClick: () -> Unit,
    onHide: (String) -> Unit,
) {
    var index by remember(alerts) { mutableIntStateOf(0) }

    LaunchedEffect(alerts) {
        if (alerts.size < 2) return@LaunchedEffect
        while (true) {
            delay(6_000)
            index = (index + 1) % alerts.size
        }
    }

    val item = alerts[index.coerceIn(alerts.indices)]
    AlertCard(
        item = item,
        position = if (alerts.size > 1) "${index + 1}/${alerts.size}" else null,
        onClick = onClick,
        onHide = { onHide(item.id) },
    )
}

@Composable
private fun AlertCard(
    item: NewsItem,
    position: String?,
    onClick: () -> Unit,
    onHide: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Komunikat",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (position != null) {
                    Text(position, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onHide, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Ukryj komunikat",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "${item.source} · ${item.published.format(SHORT_DAY_FMT)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun WasteCard(collection: Collection?, address: String?, onClick: () -> Unit) {
    HomeCard(Icons.Outlined.Delete, "Najbliższy wywóz", onClick) {
        when {
            address == null -> Text(
                "Wybierz swój adres, żeby zobaczyć harmonogram",
                style = MaterialTheme.typography.bodyMedium,
            )

            collection == null -> Text(
                "Brak nadchodzących terminów",
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> Column {
                Text(
                    collection.date.humanLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    collection.types.forEach { type ->
                        Box(
                            Modifier
                                .background(
                                    Color(type.color).copy(alpha = 0.18f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                type.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(type.color),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransitCard(stop: String?, departures: List<Departure>, onClick: () -> Unit) {
    HomeCard(Icons.Outlined.DirectionsBus, stop ?: "Najbliższy odjazd", onClick) {
        when {
            stop == null -> Text(
                "Dodaj ulubiony przystanek",
                style = MaterialTheme.typography.bodyMedium,
            )

            departures.isEmpty() -> Text(
                "Brak najbliższych odjazdów",
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> Column {
                departures.forEach { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            d.line,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(44.dp),
                        )
                        Text(
                            d.headsign,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text(
                            if (d.inMinutes <= 0L) "teraz" else "${d.inMinutes} min",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsCard(
    events: List<Event>,
    onEventClick: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    HomeCard(Icons.Outlined.CalendarMonth, "Nadchodzące wydarzenia", onSeeAll) {
        if (events.isEmpty()) {
            Text("Brak nadchodzących wydarzeń", style = MaterialTheme.typography.bodyMedium)
        } else {
            Column {
                events.forEach { e ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEventClick(e.id) }
                            .padding(vertical = 5.dp)
                    ) {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(
                            "${e.start.toLocalDate().humanLabel()}, " +
                                "${e.start.toLocalTime().format(TIME_FMT)} · ${e.venue}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
