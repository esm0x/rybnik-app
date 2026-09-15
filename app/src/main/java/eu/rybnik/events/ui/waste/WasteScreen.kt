package eu.rybnik.events.ui.waste

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.rybnik.events.Graph
import eu.rybnik.events.core.prefs.WasteAddress
import eu.rybnik.events.data.waste.Collection
import eu.rybnik.events.data.waste.WasteType
import eu.rybnik.events.ui.common.EmptyState
import eu.rybnik.events.ui.common.ErrorBanner
import eu.rybnik.events.ui.common.PL
import eu.rybnik.events.ui.common.SectionHeader
import eu.rybnik.events.ui.common.humanLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

data class WasteUi(
    val address: WasteAddress? = null,
    val collections: List<Collection> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class WasteViewModel : ViewModel() {
    private val _ui = MutableStateFlow(WasteUi())
    val ui: StateFlow<WasteUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            Graph.prefs.settings.collect { settings ->
                val address = settings.wasteAddress
                _ui.update { it.copy(address = address, loading = false) }
                if (address != null) load(address)
            }
        }
        viewModelScope.launch { Graph.wasteRepo.refresh() }
    }

    private fun load(address: WasteAddress) {
        val today = LocalDate.now()
        val list = Graph.wasteRepo.schedule()
            .collections(address.rejonId, today, today.plusMonths(12))
        _ui.update {
            it.copy(
                collections = list,
                error = if (list.isEmpty()) {
                    "Brak terminów dla tego adresu. Sprawdź, czy harmonogram na ten rok jest już opublikowany."
                } else null,
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            Graph.wasteRepo.refresh()
            _ui.value.address?.let { load(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WasteScreen(onPickAddress: () -> Unit) {
    val vm: WasteViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    var monthView by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Odpady") },
                actions = {
                    if (ui.address != null) {
                        IconButton(onClick = onPickAddress) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Zmień adres")
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val address = ui.address
            if (address == null) {
                EmptyState(
                    icon = Icons.Outlined.Delete,
                    title = "Nie znamy jeszcze Twojego adresu",
                    subtitle = "Harmonogram zależy od ulicy i numeru domu — w Rybniku sąsiednie " +
                        "numery potrafią trafić do innych rejonów.",
                    action = { Button(onClick = onPickAddress) { Text("Wybierz adres") } },
                )
                return@Column
            }

            Text(
                address.pretty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SegmentedButton(
                    selected = !monthView,
                    onClick = { monthView = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Lista") }
                SegmentedButton(
                    selected = monthView,
                    onClick = { monthView = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Kalendarz") }
            }

            ui.error?.let { ErrorBanner(it, onRetry = vm::refresh) }

            if (monthView) {
                MonthView(ui.collections)
            } else {
                ListView(ui.collections)
            }
        }
    }
}

@Composable
private fun ListView(collections: List<Collection>) {
    if (collections.isEmpty()) {
        EmptyState(Icons.Outlined.Delete, "Brak nadchodzących terminów")
        return
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        items(collections, key = { it.date.toString() }) { c ->
            CollectionRow(c)
        }
    }
}

@Composable
private fun CollectionRow(c: Collection) {
    val isNext = c.date == LocalDate.now() || c.date == LocalDate.now().plusDays(1)
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    c.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isNext) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    c.date.month.getDisplayName(
                        java.time.format.TextStyle.SHORT, PL
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.date.humanLabel(), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    c.types.forEach { TypeChip(it) }
                }
            }
        }
    }
}

@Composable
private fun TypeChip(type: WasteType) {
    Box(
        Modifier
            .background(Color(type.color).copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(type.label, style = MaterialTheme.typography.labelSmall, color = Color(type.color))
    }
}

@Composable
private fun MonthView(collections: List<Collection>) {
    var offset by remember { mutableIntStateOf(0) }
    val month = YearMonth.now().plusMonths(offset.toLong())
    val byDate = collections.associateBy { it.date }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { offset-- }, enabled = offset > 0) { Text("‹") }
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", PL))
                    .replaceFirstChar { it.uppercase(PL) },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { offset++ }, enabled = offset < 11) { Text("›") }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            listOf("pn", "wt", "śr", "cz", "pt", "sb", "nd").forEach { d ->
                Text(
                    d,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = buildList {
            repeat(lead) { add(null) }
            (1..month.lengthOfMonth()).forEach { add(month.atDay(it)) }
        }

        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                week.forEach { date ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (date != null) DayCell(date, byDate[date])
                    }
                }
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }

        val monthItems = collections.filter { YearMonth.from(it.date) == month }
        if (monthItems.isNotEmpty()) {
            SectionHeader("Terminy w tym miesiącu")
            LazyColumn { items(monthItems, key = { it.date.toString() }) { CollectionRow(it) } }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, collection: Collection?) {
    val isToday = date == LocalDate.now()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            collection?.types?.take(3)?.forEach { type ->
                Box(Modifier.size(5.dp).background(Color(type.color), CircleShape))
            }
        }
    }
}
