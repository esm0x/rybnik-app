package com.adminstack.rybnik.ui.news

import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adminstack.rybnik.Graph
import com.adminstack.rybnik.data.news.NewsItem
import com.adminstack.rybnik.ui.common.EmptyState
import com.adminstack.rybnik.ui.common.ErrorBanner
import com.adminstack.rybnik.ui.common.SHORT_DAY_FMT
import com.adminstack.rybnik.ui.common.openExternalLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewsUi(
    val items: List<NewsItem> = emptyList(),
    val categories: List<String> = emptyList(),
    val selected: String? = null,
    val alertsOnly: Boolean = false,
    /** Browsing the dismissed ones instead of the live list. */
    val showHidden: Boolean = false,
    val hiddenCount: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

class NewsViewModel : ViewModel() {
    private val _ui = MutableStateFlow(NewsUi())
    val ui: StateFlow<NewsUi> = _ui.asStateFlow()

    private var hidden: Set<String> = emptySet()

    init {
        reload()
        viewModelScope.launch {
            Graph.prefs.settings.map { it.hiddenNewsIds }.distinctUntilChanged().collect {
                hidden = it
                reload()
            }
        }
        viewModelScope.launch {
            Graph.newsRepo.state.collect { st ->
                _ui.update { it.copy(loading = st.loading, error = st.error) }
                Graph.prefs.pruneHiddenNews(Graph.newsRepo.allIds())
                reload()
            }
        }
    }

    private fun reload() {
        _ui.update {
            // Restoring the last dismissed item hides the chip that got us here, so drop
            // back to the normal list instead of stranding the user on an empty screen.
            val showHidden = it.showHidden && hidden.isNotEmpty()
            it.copy(
                showHidden = showHidden,
                items = if (showHidden) Graph.newsRepo.hiddenItems(hidden)
                else Graph.newsRepo.items(hidden),
                categories = Graph.newsRepo.categories(hidden),
                hiddenCount = hidden.size,
            )
        }
    }

    fun refresh() {
        viewModelScope.launch { Graph.newsRepo.refresh(); reload() }
    }

    fun selectCategory(c: String?) = _ui.update { it.copy(selected = c) }
    fun toggleAlerts() = _ui.update { it.copy(alertsOnly = !it.alertsOnly) }

    fun hide(id: String) = viewModelScope.launch { Graph.prefs.hideNews(id) }

    fun restore(id: String) = viewModelScope.launch { Graph.prefs.unhideNews(id) }

    fun restoreAll() = viewModelScope.launch { Graph.prefs.clearHiddenNews() }

    /** Leaving the hidden view when the last item is restored saves a dead-end screen. */
    fun toggleShowHidden() {
        _ui.update { it.copy(showHidden = !it.showHidden) }
        reload()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen() {
    val vm: NewsViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    val visible = remember(ui.items, ui.selected, ui.alertsOnly) {
        ui.items
            .filter { !ui.alertsOnly || it.isAlert }
            .filter { ui.selected == null || it.category == ui.selected }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wiadomości") },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Odśwież")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ui.error?.let { ErrorBanner(it, onRetry = vm::refresh) }

            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.hiddenCount > 0) {
                    FilterChip(
                        selected = ui.showHidden,
                        onClick = vm::toggleShowHidden,
                        label = { Text("Ukryte (${ui.hiddenCount})") },
                    )
                }
                FilterChip(
                    selected = ui.alertsOnly,
                    onClick = vm::toggleAlerts,
                    label = { Text("Tylko komunikaty") },
                )
                FilterChip(
                    selected = ui.selected == null,
                    onClick = { vm.selectCategory(null) },
                    label = { Text("Wszystko") },
                )
                ui.categories.forEach { c ->
                    FilterChip(
                        selected = ui.selected == c,
                        onClick = { vm.selectCategory(if (ui.selected == c) null else c) },
                        label = { Text(c) },
                    )
                }
            }

            if (visible.isEmpty()) {
                EmptyState(
                    Icons.Outlined.Newspaper,
                    if (ui.showHidden) "Nie masz ukrytych wiadomości" else "Brak wiadomości",
                )
                return@Column
            }

            LazyColumn {
                items(visible, key = { it.id }) { item ->
                    if (item.isAlert) MarkSeenWhenShown(item.id)
                    NewsCard(
                        item = item,
                        hidden = ui.showHidden,
                        onClick = { openExternalLink(context, item.link) },
                        onToggleHidden = {
                            if (ui.showHidden) vm.restore(item.id) else vm.hide(item.id)
                        },
                    )
                }
                if (ui.showHidden) {
                    item {
                        TextButton(
                            onClick = vm::restoreAll,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) { Text("Przywróć wszystkie") }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun NewsCard(
    item: NewsItem,
    hidden: Boolean,
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = if (item.isAlert) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleHidden, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (hidden) Icons.Outlined.Undo else Icons.Outlined.Close,
                        contentDescription = if (hidden) "Przywróć" else "Ukryj",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            item.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(
                    item.source,
                    item.category,
                    item.published.format(SHORT_DAY_FMT),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (item.isAlert) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Counts an alert as read once it has stayed on screen for a moment.
 *
 * The delay is what separates reading from scrolling past: items composed for an instant
 * during a fling are disposed before it runs out, and the effect is cancelled with them.
 *
 * "On screen" means resumed, and that gate is not optional. A composition outlives its
 * activity going to the background, so the first version kept counting with the launcher
 * on top: the dashboard carousel cycled on unseen and marked all nine current alerts as
 * read within a minute, leaving the notification worker nothing to announce.
 */
@Composable
internal fun MarkSeenWhenShown(alertId: String) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(alertId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(SEEN_AFTER_MS)
            Graph.prefs.markAlertsSeen(listOf(alertId))
        }
    }
}

private const val SEEN_AFTER_MS = 1_500L

