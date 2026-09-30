package com.adminstack.rybnik.ui.common

import androidx.core.net.toUri
import android.widget.Toast
import android.content.Intent
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val PL: Locale = Locale.forLanguageTag("pl-PL")
val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", PL)
val SHORT_DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", PL)
val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun LocalDate.humanLabel(): String = when (this) {
    LocalDate.now() -> "Dziś"
    LocalDate.now().plusDays(1) -> "Jutro"
    else -> format(DAY_FMT).replaceFirstChar { it.uppercase(PL) }
}

@Composable
fun ErrorBanner(message: String, onRetry: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Ponów") }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon, null,
                Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

@Composable
fun LoadingBox(label: String = "Wczytywanie…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SectionHeader(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * Opens a link from scraped data, and survives doing so.
 *
 * Two problems it solves. First, `startActivity` throws ActivityNotFoundException when
 * nothing can handle the intent, and a phone with no browser is a real thing: Android
 * lets you disable Chrome. Tapping "Źródło" on an event used to take the whole app down
 * with it, while the news list wrapped the identical call and survived.
 *
 * Second, these URLs come from third-party RSS feeds we do not own. Handing an arbitrary
 * string to ACTION_VIEW means a scheme like `intent:` can be redirected into components
 * that were never meant to be reachable from a news item, so only http and https are
 * followed and anything else is treated as broken data.
 */
fun openExternalLink(context: Context, url: String?) {
    val uri = url?.trim()?.takeIf { it.isNotEmpty() }?.toUri()
    val scheme = uri?.scheme?.lowercase()
    if (uri == null || (scheme != "http" && scheme != "https")) {
        Toast.makeText(context, "Ten odnośnik jest nieprawidłowy", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure {
            runCatching {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("link", uri.toString()))
            }
            Toast.makeText(
                context,
                "Brak przeglądarki. Link skopiowany do schowka.",
                Toast.LENGTH_LONG,
            ).show()
        }
}
