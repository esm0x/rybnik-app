package com.adminstack.rybnik.ui.sport

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.SportsSoccer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adminstack.rybnik.Graph
import com.adminstack.rybnik.data.sport.Match
import com.adminstack.rybnik.data.sport.Outcome
import com.adminstack.rybnik.data.sport.SportTeam
import com.adminstack.rybnik.ui.common.EmptyState
import com.adminstack.rybnik.ui.common.ErrorBanner
import com.adminstack.rybnik.ui.common.SectionHeader
import com.adminstack.rybnik.ui.common.TIME_FMT
import com.adminstack.rybnik.ui.common.humanLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SportScreen(onBack: () -> Unit) {
    val state by Graph.sportRepo.state.collectAsState()
    val payload by Graph.sportRepo.data.collectAsState()

    var selected by remember { mutableStateOf<String?>(null) }

    val teams = remember(payload) { Graph.sportRepo.teams() }
    val upcoming = remember(payload, selected) { Graph.sportRepo.upcoming(selected) }
    val results = remember(payload, selected) { Graph.sportRepo.results(selected) }
    val byId = remember(teams) { teams.associateBy { it.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sport") },
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

            if (teams.isEmpty()) {
                EmptyState(Icons.Outlined.SportsSoccer, "Brak danych o meczach")
                return@Column
            }

            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selected == null,
                    onClick = { selected = null },
                    label = { Text("Wszystkie") },
                )
                teams.forEach { team ->
                    FilterChip(
                        selected = selected == team.id,
                        onClick = { selected = if (selected == team.id) null else team.id },
                        label = { Text(team.kind.label) },
                    )
                }
            }

            LazyColumn {
                if (upcoming.isEmpty() && results.isEmpty()) {
                    item { EmptyState(Icons.Outlined.SportsSoccer, "Brak meczów w tym sezonie") }
                }

                if (upcoming.isNotEmpty()) {
                    item { SectionHeader("Nadchodzące") }
                    items(upcoming, key = { it.id }) { MatchRow(it, byId[it.teamId]) }
                } else if (results.isNotEmpty()) {
                    // Speedway sits in this state from October to April, and a silent
                    // empty section would read as a scraper failure rather than a break.
                    item {
                        Text(
                            "Sezon zakończony, terminarz na kolejny jeszcze nie ogłoszony.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                if (results.isNotEmpty()) {
                    item { SectionHeader("Wyniki") }
                    items(results, key = { it.id }) { MatchRow(it, byId[it.teamId]) }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/**
 * One match. Clickable only when the source has a page for it: 90minut publishes one
 * for league games, ekstraliga.pl for every speedway match, and nobody for the women's
 * league or the regional cup. A card that looks tappable and does nothing would read as
 * a bug, so those simply stay plain.
 */
@Composable
private fun MatchRow(match: Match, team: SportTeam?) {
    val context = LocalContext.current
    val modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
    val body: @Composable () -> Unit = { MatchRowBody(match, team) }

    val url = match.url
    if (url != null) {
        Card(onClick = { openMatchPage(context, url) }, modifier = modifier) { body() }
    } else {
        Card(modifier = modifier) { body() }
    }
}

@Composable
private fun MatchRowBody(match: Match, team: SportTeam?) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            match.stage.badge?.let { label ->
                StageBadge(label)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                match.opponent,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    team?.kind?.label,
                    if (match.isHome) "u siebie" else "wyjazd",
                    match.competition,
                    match.date.humanLabel(),
                    match.time?.format(TIME_FMT),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (match.url != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (match.finished) "Relacja i szczegóły ›" else "Strona meczu ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        match.scoreText?.let { score ->
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    score,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (match.outcome) {
                        Outcome.WIN -> MaterialTheme.colorScheme.primary
                        Outcome.LOSS -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                match.scoreNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Small and quiet: it should be findable, not shout over the score. */
@Composable
private fun StageBadge(label: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Same fallback as the support screen: a phone with no browser gets the link copied. */
private fun openMatchPage(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("link", url))
            Toast.makeText(context, "Brak przeglądarki. Link skopiowany.", Toast.LENGTH_LONG)
                .show()
        }
}
