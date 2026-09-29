package com.adminstack.rybnik.ui.more

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.adminstack.rybnik.Graph
import com.adminstack.rybnik.core.prefs.Settings
import com.adminstack.rybnik.core.prefs.ThemeMode
import com.adminstack.rybnik.core.prefs.UserPrefs
import com.adminstack.rybnik.data.air.AirQualityRepository
import com.adminstack.rybnik.data.air.AirState
import com.adminstack.rybnik.ui.common.SectionHeader
import com.adminstack.rybnik.ui.common.TIME_FMT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenNews: () -> Unit,
    onOpenAir: () -> Unit,
    onOpenSport: () -> Unit,
    onOpenPoints: () -> Unit,
    onOpenFavourites: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSupport: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Więcej") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MoreRow(Icons.Outlined.Newspaper, "Wiadomości", "Lokalne newsy i komunikaty", onOpenNews)
            MoreRow(Icons.Outlined.Air, "Jakość powietrza", "Dane GIOŚ, stacja Rybnik-Borki", onOpenAir)
            MoreRow(Icons.Outlined.SportsSoccer, "Sport", "ROW 1964, żużel, piłka kobiet", onOpenSport)
            MoreRow(
                Icons.Outlined.Delete,
                "Gdzie wyrzucić",
                "Słownik odpadów, PSZOK i GPZON",
                onOpenPoints,
            )
            MoreRow(Icons.Outlined.Star, "Ulubione wydarzenia", "Zapisane wydarzenia", onOpenFavourites)
            MoreRow(Icons.Outlined.Settings, "Ustawienia", "Adres, powiadomienia", onOpenSettings)
            MoreRow(Icons.Outlined.Coffee, "Wesprzyj projekt", "Postaw kawę albo zgłoś błąd", onOpenSupport)
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

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { Graph.prefs.setThemeMode(mode) }
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
            item { BatterySection() }

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
            item { SectionHeader("Motyw") }
            item {
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = s.themeMode == mode,
                            onClick = { vm.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index, ThemeMode.entries.size
                            ),
                        ) { Text(mode.label) }
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
                    "Wyłączenia prądu",
                    "Planowane i awaryjne, pod Twoim adresem",
                    s.notifyOutages,
                ) { vm.setNotify(UserPrefs.NotifyChannel.Outages, it) }
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


/**
 * Reminders that stop arriving after a few days are almost always the battery manager,
 * not the app. Android's own Doze is handled in code (the daily alarm uses
 * setAndAllowWhileIdle), but a force-stop from an OEM battery manager cancels every
 * scheduled alarm and job, and nothing inside the app can undo that: only the user can,
 * from system settings.
 *
 * This opens the system list rather than asking for the exemption directly. The direct
 * request needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Google Play grants only to
 * apps whose core function genuinely depends on it, and a bin reminder is not that.
 */
@Composable
private fun BatterySection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exempt by remember { mutableStateOf(isExemptFromBatteryOptimisation(context)) }

    // Re-checked on resume, so coming back from system settings shows the new state
    // instead of the one from before the trip.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = isExemptFromBatteryOptimisation(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column {
        SectionHeader("Działanie w tle")
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            colors = if (exempt) CardDefaults.cardColors()
            else CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (exempt) "Powiadomienia mogą działać w tle"
                    else "System może wstrzymywać powiadomienia",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (exempt) {
                        "Aplikacja jest wyłączona z oszczędzania baterii, więc przypomnienia " +
                            "o wywozie i alerty powinny przychodzić na czas."
                    } else {
                        "Jeśli przypomnienia przestają przychodzić po kilku dniach, to " +
                            "zwykle oszczędzanie baterii zatrzymuje aplikację w tle. " +
                            "Wyłącz je dla Mój Rybnik, a przypomnienia wrócą."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!exempt) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { openBatterySettings(context) }) {
                        Text("Otwórz ustawienia baterii")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Xiaomi, Samsung, Huawei i OPPO mają do tego własne menedżery. " +
                        "Tam warto dodatkowo włączyć autostart i zdjąć ograniczenia dla " +
                        "aplikacji, bo zamknięcie jej z listy ostatnich aplikacji kasuje " +
                        "zaplanowane przypomnienia.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun isExemptFromBatteryOptimisation(context: Context): Boolean =
    runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

/** Falls back to the app's own settings page on devices without the optimisation list. */
private fun openBatterySettings(context: Context) {
    val list = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val details = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    runCatching { context.startActivity(list) }
        .recoverCatching { context.startActivity(details) }
}
