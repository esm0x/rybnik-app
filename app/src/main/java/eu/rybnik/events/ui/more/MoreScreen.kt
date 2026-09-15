package eu.rybnik.events.ui.more

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.rybnik.events.Graph
import eu.rybnik.events.core.prefs.Settings
import eu.rybnik.events.core.prefs.UserPrefs
import eu.rybnik.events.data.air.AirQualityRepository
import eu.rybnik.events.data.air.AirState
import eu.rybnik.events.ui.common.SectionHeader
import eu.rybnik.events.ui.common.TIME_FMT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenNews: () -> Unit,
    onOpenAir: () -> Unit,
    onOpenFavourites: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Więcej") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MoreRow(Icons.Outlined.Newspaper, "Wiadomości", "Lokalne newsy i komunikaty", onOpenNews)
            MoreRow(Icons.Outlined.Air, "Jakość powietrza", "Dane GIOŚ, stacja Rybnik-Borki", onOpenAir)
            MoreRow(Icons.Outlined.Star, "Ulubione wydarzenia", "Zapisane wydarzenia", onOpenFavourites)
            MoreRow(Icons.Outlined.Settings, "Ustawienia", "Adres, powiadomienia", onOpenSettings)
        }
    }
}

@Composable
private fun MoreRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
}

class SettingsViewModel : ViewModel() {
    val settings: StateFlow<Settings> = MutableStateFlow(Settings()).also { flow ->
        viewModelScope.launch { Graph.prefs.settings.collect { flow.value = it } }
    }.asStateFlow()

    fun setNotify(channel: UserPrefs.NotifyChannel, enabled: Boolean) {
        viewModelScope.launch { Graph.prefs.setNotify(channel, enabled) }
    }

    fun setThreshold(v: Int) {
        viewModelScope.launch { Graph.prefs.setSmogThreshold(v) }
    }

    fun setWasteHour(h: Int) {
        viewModelScope.launch { Graph.prefs.setWasteReminderHour(h) }
    }

    fun clearAddress() {
        viewModelScope.launch { Graph.prefs.clearWasteAddress() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onPickAddress: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val s by vm.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SectionHeader("Adres do harmonogramu odpadów") }
            item {
                ListItem(
                    headlineContent = { Text(s.wasteAddress?.pretty ?: "Nie wybrano") },
                    supportingContent = {
                        Text(
                            s.wasteAddress?.let {
                                if (it.houseType.name == "MULTI_FAMILY") "Zabudowa wielorodzinna"
                                else "Dom jednorodzinny"
                            } ?: "Bez adresu nie pokażemy terminów ani nie wyślemy przypomnień"
                        )
                    },
                    trailingContent = {
                        Text(
                            if (s.wasteAddress == null) "Wybierz" else "Zmień",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Button(onClick = onPickAddress) {
                        Text(if (s.wasteAddress == null) "Wybierz adres" else "Zmień adres")
                    }
                    if (s.wasteAddress != null) {
                        androidx.compose.material3.OutlinedButton(onClick = vm::clearAddress) {
                            Text("Usuń")
                        }
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
            item { SectionHeader("Powiadomienia") }

            item {
                SwitchRow(
                    "Wywóz odpadów",
                    "Wieczorem dnia poprzedniego",
                    s.notifyWaste,
                ) { vm.setNotify(UserPrefs.NotifyChannel.Waste, it) }
            }
            item {
                if (s.notifyWaste) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "Godzina przypomnienia: ${s.wasteReminderHour}:00",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Slider(
                            value = s.wasteReminderHour.toFloat(),
                            onValueChange = { vm.setWasteHour(it.toInt()) },
                            valueRange = 12f..22f,
                            steps = 9,
                        )
                    }
                }
            }
            item {
                SwitchRow(
                    "Ulubione wydarzenia",
                    "Dzień przed wydarzeniem",
                    s.notifyEvents,
                ) { vm.setNotify(UserPrefs.NotifyChannel.Events, it) }
            }
            item {
                SwitchRow(
                    "Alert smogowy",
                    "Gdy PM10 przekroczy próg",
                    s.notifySmog,
                ) { vm.setNotify(UserPrefs.NotifyChannel.Smog, it) }
            }
            item {
                if (s.notifySmog) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "Próg alertu: ${s.smogThreshold} µg/m³ " +
                                "(poziom informowania: ${AirQualityRepository.PM10_INFO.toInt()})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Slider(
                            value = s.smogThreshold.toFloat(),
                            onValueChange = { vm.setThreshold(it.toInt()) },
                            valueRange = 40f..200f,
                            steps = 15,
                        )
                    }
                }
            }
            item {
                SwitchRow(
                    "Komunikaty miejskie",
                    "Awarie i utrudnienia",
                    s.notifyCityAlerts,
                ) { vm.setNotify(UserPrefs.NotifyChannel.CityAlerts, it) }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
            item {
                Text(
                    "Dane: KM Rybnik (GTFS), EKO Sp. z o.o. i Miasto Rybnik (odpady), " +
                        "GIOŚ (powietrze), TZR / iRybnik / biletyna i inne (wydarzenia).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirScreen(onBack: () -> Unit) {
    val state by Graph.airRepo.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jakość powietrza") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).background(Color(state.severity.color), CircleShape))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(state.severity.label, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        AirQualityRepository.STATION_NAME,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            state.readings.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "pomiar ${r.at.toLocalTime().format(TIME_FMT)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${r.value} ${r.unit}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                HorizontalDivider()
            }

            if (state.readings.isEmpty()) {
                Text(
                    state.error ?: "Brak danych pomiarowych.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Poziom informowania dla PM10 to ${AirQualityRepository.PM10_INFO.toInt()} µg/m³, " +
                    "poziom alarmowy ${AirQualityRepository.PM10_ALARM.toInt()} µg/m³. " +
                    "Norma dobowa wynosi ${AirQualityRepository.PM10_LIMIT.toInt()} µg/m³ " +
                    "i wolno ją przekroczyć najwyżej 35 dni w roku.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
