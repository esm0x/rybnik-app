package eu.rybnik.events.ui.more

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

private const val COFFEE_URL = "https://buycoffee.to/esm0x"
private const val REPO_URL = "https://github.com/esm0x/rybnik-app"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Handy when someone reports a bug — they can read the build straight off this screen.
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wesprzyj projekt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Coffee, null, Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Postaw kawę",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Ta aplikacja jest darmowa, bez reklam i nie zbiera żadnych danych " +
                            "o Tobie. Powstaje po godzinach. Jeśli się przydaje, możesz " +
                            "postawić kawę — ale naprawdę nie trzeba.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { open(COFFEE_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("buycoffee.to/esm0x") }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text("Inne sposoby", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Zgłoszenie błędu albo brakującego wydarzenia pomaga tak samo jak kawa. " +
                    "Kod jest otwarty — można też zajrzeć i poprawić samemu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { open(REPO_URL) }) {
                    Icon(Icons.Outlined.Code, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Kod na GitHubie")
                }
                OutlinedButton(onClick = { open("$REPO_URL/issues/new") }) {
                    Text("Zgłoś błąd")
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Rybnik $appVersion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Dane pochodzą od KM Rybnik, EKO Sp. z o.o., Miasta Rybnik, GIOŚ, Taurona " +
                    "oraz lokalnych redakcji i instytucji kultury. Aplikacja nie jest " +
                    "oficjalnym produktem miasta.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
