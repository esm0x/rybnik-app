package com.adminstack.rybnik.ui.sport

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

@Composable
private fun MatchRow(match: Match, team: SportTeam?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
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
            }
            match.scoreLabel?.let {
                Spacer(Modifier.width(10.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
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
