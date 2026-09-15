package eu.rybnik.events.ui.events

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.rybnik.events.Graph
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(eventId: String, onBack: () -> Unit) {
    val event = remember(eventId) { Graph.eventRepo.getById(eventId) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (event == null) {
            Box(
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Nie znaleziono wydarzenia.")
            }
            return@Scaffold
        }

        val cat = Color(event.category.color)
        val df = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy • HH:mm", Locale("pl", "PL"))

        Column(
            Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Surface(
                color = cat.copy(alpha = 0.15f),
                contentColor = cat,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.wrapContentSize(),
            ) {
                Text(
                    text = event.category.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(event.title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            InfoRow(
                icon = Icons.Filled.Schedule,
                text = event.start.format(df)
                    .replaceFirstChar { it.titlecase(Locale("pl", "PL")) },
            )
            event.end?.let { end ->
                InfoRow(
                    icon = Icons.Filled.CalendarMonth,
                    text = "do " + end.format(df)
                        .replaceFirstChar { it.titlecase(Locale("pl", "PL")) },
                )
            }
            InfoRow(icon = Icons.Filled.Place, text = event.venue)

            Spacer(Modifier.height(20.dp))
            if (!event.description.isNullOrBlank()) {
                Text(event.description, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val zone = ZoneId.systemDefault()
                        val startMs = event.start.atZone(zone).toInstant().toEpochMilli()
                        val endMs = (event.end ?: event.start.plusHours(2))
                            .atZone(zone).toInstant().toEpochMilli()
                        val intent = Intent(Intent.ACTION_INSERT)
                            .setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, event.title)
                            .putExtra(CalendarContract.Events.EVENT_LOCATION, event.venue)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.wrapContentSize(),
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Do kalendarza")
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(event.sourceUrl))
                        )
                    },
                    modifier = Modifier.wrapContentSize(),
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Źródło")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Źródło: ${event.sourceName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
