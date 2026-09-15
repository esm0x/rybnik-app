package eu.rybnik.events.ui.news

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.rybnik.events.Graph
import eu.rybnik.events.data.news.NewsItem
import eu.rybnik.events.ui.common.EmptyState
import eu.rybnik.events.ui.common.ErrorBanner
import eu.rybnik.events.ui.common.SHORT_DAY_FMT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewsUi(
    val items: List<NewsItem> = emptyList(),
    val categories: List<String> = emptyList(),
    val selected: String? = null,
    val alertsOnly: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class NewsViewModel : ViewModel() {
    private val _ui = MutableStateFlow(NewsUi())
    val ui: StateFlow<NewsUi> = _ui.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            Graph.newsRepo.state.collect { st ->
                _ui.update { it.copy(loading = st.loading, error = st.error) }
                reload()
            }
        }
    }

    private fun reload() {
        _ui.update {
            it.copy(items = Graph.newsRepo.items(), categories = Graph.newsRepo.categories())
        }
    }

    fun refresh() {
        viewModelScope.launch { Graph.newsRepo.refresh(); reload() }
    }

    fun selectCategory(c: String?) = _ui.update { it.copy(selected = c) }
    fun toggleAlerts() = _ui.update { it.copy(alertsOnly = !it.alertsOnly) }
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
                EmptyState(Icons.Outlined.Newspaper, "Brak wiadomości")
                return@Column
            }

            LazyColumn {
                items(visible, key = { it.id }) { item ->
                    NewsCard(item) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, item.link.toUri()))
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun NewsCard(item: NewsItem, onClick: () -> Unit) {
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
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
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
