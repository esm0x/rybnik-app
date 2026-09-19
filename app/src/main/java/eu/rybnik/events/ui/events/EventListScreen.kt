package eu.rybnik.events.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.rybnik.events.Graph
import eu.rybnik.events.data.Event
import eu.rybnik.events.data.EventCategory
import eu.rybnik.events.data.EventTiming
import eu.rybnik.events.data.timing
import eu.rybnik.events.data.RemoteEventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class EventListUiState(
    val events: List<Event> = emptyList(),
    val selectedCategories: Set<EventCategory> = emptySet(),
    val favourites: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
)

class EventListViewModel : ViewModel() {
    private val repo: RemoteEventRepository = Graph.eventRepo
    private val selected = MutableStateFlow<Set<EventCategory>>(emptySet())
    private val favourites = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<EventListUiState> = combine(
        repo.events, repo.state, selected, favourites,
    ) { events, load, sel, favs ->
        val now = LocalDateTime.now()
        val filtered = events
            .filter { (it.end ?: it.start).isAfter(now.minusHours(3)) }
            .filter { sel.isEmpty() || it.category in sel }
            .sortedBy { it.start }
        EventListUiState(
            events = filtered,
            selectedCategories = sel,
            favourites = favs,
            loading = load.loading,
            error = load.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventListUiState())

    init {
        viewModelScope.launch {
            Graph.prefs.settings.collect { favourites.value = it.favouriteEventIds }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repo.refresh() }
    }

    fun toggleFavourite(id: String) {
        viewModelScope.launch { Graph.prefs.toggleFavouriteEvent(id) }
    }

    fun toggleCategory(cat: EventCategory) {
        selected.update { current -> if (cat in current) current - cat else current + cat }
    }

    fun clearFilters() {
        selected.value = emptySet()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventListScreen(
    onEventClick: (String) -> Unit,
    favouritesOnly: Boolean = false,
    viewModel: EventListViewModel = viewModel(),
) {
    val full by viewModel.state.collectAsState()
    val state = if (favouritesOnly) {
        full.copy(events = full.events.filter { it.id in full.favourites })
    } else full

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (favouritesOnly) "Ulubione" else "Wydarzenia") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Odśwież")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.error?.let { ErrorBanner(it, onRetry = viewModel::refresh) }

            CategoryFilterBar(
                selected = state.selectedCategories,
                onToggle = viewModel::toggleCategory,
                onClear = viewModel::clearFilters,
            )
            if (state.events.isEmpty() && !state.loading) {
                EmptyState(hasError = state.error != null)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val grouped = state.events.groupBy { it.start.toLocalDate() }
                    grouped.forEach { (date, evs) ->
                        item(key = "hdr-$date") { DateHeader(date) }
                        items(evs, key = { it.id }) { ev ->
                            EventCard(
                                event = ev,
                                onClick = { onEventClick(ev.id) },
                                isFavourite = ev.id in state.favourites,
                                onToggleFavourite = { viewModel.toggleFavourite(ev.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Nie udało się pobrać danych: $message",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Ponów") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterBar(
    selected: Set<EventCategory>,
    onToggle: (EventCategory) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = onClear,
            label = { Text("Wszystkie") },
        )
        EventCategory.entries.forEach { cat ->
            val isOn = cat in selected
            val tint = Color(cat.color)
            FilterChip(
                selected = isOn,
                onClick = { onToggle(cat) },
                label = { Text(cat.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = tint.copy(alpha = 0.16f),
                    selectedLabelColor = tint,
                ),
            )
        }
    }
}

private val dateHeaderFormatter =
    DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("pl", "PL"))

@Composable
private fun DateHeader(date: LocalDate) {
    Text(
        text = date.format(dateHeaderFormatter)
            .replaceFirstChar { it.titlecase(Locale("pl", "PL")) },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventCard(
    event: Event,
    onClick: () -> Unit,
    isFavourite: Boolean = false,
    onToggleFavourite: (() -> Unit)? = null,
) {
    val timing = event.timing()
    val finished = timing == EventTiming.FINISHED
    // Finished-but-still-listed events stay readable, just visibly demoted.
    val catColor = Color(event.category.color)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = if (finished) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .height(IntrinsicSize.Min)
                .alpha(if (finished) 0.45f else 1f)
        ) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(catColor)
            )
            DateBlock(event.start, tint = catColor)
            Column(
                Modifier
                    .padding(vertical = 14.dp, horizontal = 14.dp)
                    .fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.category.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = catColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (timing == EventTiming.ONGOING) {
                        Spacer(Modifier.width(8.dp))
                        LiveBadge()
                    }
                    Spacer(Modifier.weight(1f))
                    if (onToggleFavourite != null) {
                        Icon(
                            imageVector = if (isFavourite) Icons.Filled.Star
                            else Icons.Outlined.StarBorder,
                            contentDescription = if (isFavourite) "Usuń z ulubionych"
                            else "Dodaj do ulubionych",
                            tint = if (isFavourite) catColor
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onToggleFavourite() },
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = event.venue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "Happening now" marker — the one piece of state worth spotting at a glance. */
@Composable
private fun LiveBadge() {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "W trakcie",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DateBlock(dt: LocalDateTime, tint: Color) {
    Column(
        modifier = Modifier
            .padding(vertical = 14.dp)
            .padding(start = 12.dp, end = 4.dp)
            .width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = dt.dayOfMonth.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = tint,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = dt.month
                .getDisplayName(java.time.format.TextStyle.SHORT, Locale("pl", "PL"))
                .uppercase()
                .trimEnd('.'),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = dt.format(timeFormatter),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(hasError: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (hasError) "Brak danych" else "Brak wydarzeń",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (hasError) "Sprawdź połączenie i odśwież"
                       else "Spróbuj usunąć filtry",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
