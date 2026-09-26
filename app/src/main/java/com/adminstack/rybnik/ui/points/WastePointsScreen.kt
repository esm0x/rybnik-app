package com.adminstack.rybnik.ui.points

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.adminstack.rybnik.Graph
import com.adminstack.rybnik.data.points.Destination
import com.adminstack.rybnik.data.points.GuideEntry
import com.adminstack.rybnik.data.points.WastePoint
import com.adminstack.rybnik.ui.common.EmptyState
import com.adminstack.rybnik.ui.common.ErrorBanner
import com.adminstack.rybnik.ui.common.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WastePointsScreen(onBack: () -> Unit) {
    val state by Graph.pointsRepo.state.collectAsState()
    val payload by Graph.pointsRepo.data.collectAsState()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }

    val matches = remember(payload, query) { Graph.pointsRepo.search(query) }
    val points = remember(payload) { Graph.pointsRepo.points() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gdzie wyrzucić") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { ErrorBanner(it) }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Czego szukasz?") },
                placeholder = { Text("np. baterie, styropian, choinka") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            if (points.isEmpty() && matches.isEmpty()) {
                EmptyState(Icons.Outlined.Delete, "Brak danych o punktach")
                return@Column
            }

            LazyColumn {
                if (query.isNotBlank() && matches.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Search,
                            title = "Nic nie znaleziono",
                            subtitle = "Spróbuj innego słowa. Jeśli czegoś brakuje, napisz " +
                                "do nas, a dopiszemy.",
                        )
                    }
                }

                if (matches.isNotEmpty()) {
                    item { SectionHeader(if (query.isBlank()) "Wszystko" else "Wyniki") }
                    items(matches, key = { it.item }) { GuideRow(it) }
                }

                if (query.isBlank()) {
                    item { SectionHeader("Punkty zbiórki") }
                    items(points, key = { it.id }) { point ->
                        PointCard(point) { number ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, "tel:$number".toUri())
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun GuideRow(entry: GuideEntry) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.item,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(entry.destination.hint, entry.note).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            DestinationBadge(entry.destination)
        }
    }
}

@Composable
private fun DestinationBadge(destination: Destination) {
    val container = when (destination) {
        Destination.GPZON -> MaterialTheme.colorScheme.errorContainer
        Destination.PSZOK, Destination.GABARYTY -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Text(
            destination.label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PointCard(point: WastePoint, onCall: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                point.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(point.address, point.district).joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (point.hours.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    point.hours,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            point.note?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (point.accepted.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Przyjmuje: " + point.accepted.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                )
            }
            point.phone?.takeIf { it.isNotBlank() }?.let { number ->
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onCall(number) }) {
                    Icon(Icons.Outlined.Call, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(number)
                }
            }
        }
    }
}
