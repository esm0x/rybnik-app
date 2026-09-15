package eu.rybnik.events.ui.waste

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.rybnik.events.Graph
import eu.rybnik.events.core.prefs.HouseType
import eu.rybnik.events.core.prefs.WasteAddress
import eu.rybnik.events.ui.common.ErrorBanner
import eu.rybnik.events.ui.common.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Step { DISTRICT, HOUSE_TYPE, STREET, NUMBER }

data class PickerUi(
    val step: Step = Step.DISTRICT,
    val districts: List<String> = emptyList(),
    val streets: List<String> = emptyList(),
    val district: String? = null,
    val houseType: HouseType = HouseType.SINGLE_FAMILY,
    val street: String? = null,
    val query: String = "",
    val number: String = "",
    val error: String? = null,
    val saved: Boolean = false,
)

class AddressPickerViewModel : ViewModel() {
    private val _ui = MutableStateFlow(PickerUi())
    val ui: StateFlow<PickerUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            if (Graph.wasteRepo.data.value == null) Graph.wasteRepo.refresh()
            val districts = Graph.wasteRepo.schedule().districts
            _ui.update {
                it.copy(
                    districts = districts,
                    error = if (districts.isEmpty()) {
                        "Brak danych o rejonach. Sprawdź połączenie i czy waste.json jest już w repo."
                    } else null,
                )
            }
        }
    }

    fun pickDistrict(d: String) = _ui.update {
        it.copy(district = d, step = Step.HOUSE_TYPE, error = null)
    }

    fun pickHouseType(t: HouseType) {
        val district = _ui.value.district ?: return
        val streets = Graph.wasteRepo.schedule().streetsIn(district, t)
        _ui.update {
            it.copy(
                houseType = t,
                streets = streets,
                step = Step.STREET,
                query = "",
                error = if (streets.isEmpty()) {
                    "Dla tej dzielnicy nie ma harmonogramu w wybranym typie zabudowy."
                } else null,
            )
        }
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q) }

    fun pickStreet(s: String) = _ui.update { it.copy(street = s, step = Step.NUMBER, error = null) }

    fun setNumber(n: String) = _ui.update { it.copy(number = n, error = null) }

    fun back() = _ui.update {
        when (it.step) {
            Step.DISTRICT -> it
            Step.HOUSE_TYPE -> it.copy(step = Step.DISTRICT)
            Step.STREET -> it.copy(step = Step.HOUSE_TYPE)
            Step.NUMBER -> it.copy(step = Step.STREET)
        }
    }

    fun save() {
        val s = _ui.value
        val district = s.district ?: return
        val street = s.street ?: return
        if (s.number.isBlank()) {
            _ui.update { it.copy(error = "Podaj numer domu — od niego zależy rejon.") }
            return
        }
        val rejon = Graph.wasteRepo.schedule()
            .resolveRejon(district, street, s.number.trim(), s.houseType)
        if (rejon == null) {
            _ui.update {
                it.copy(
                    error = "Nie udało się dopasować numeru ${s.number} przy ulicy $street " +
                        "do żadnego rejonu. Sprawdź numer albo wybierz inny typ zabudowy."
                )
            }
            return
        }
        viewModelScope.launch {
            Graph.prefs.setWasteAddress(
                WasteAddress(
                    district = district,
                    street = street,
                    houseNumber = s.number.trim(),
                    houseType = s.houseType,
                    rejonId = rejon.id,
                )
            )
            _ui.update { it.copy(saved = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressPickerScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val vm: AddressPickerViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    if (ui.saved) onDone()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Twój adres") },
                navigationIcon = {
                    IconButton(onClick = { if (ui.step == Step.DISTRICT) onBack() else vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ui.error?.let { ErrorBanner(it) }

            when (ui.step) {
                Step.DISTRICT -> {
                    SectionHeader("Wybierz dzielnicę")
                    LazyColumn {
                        items(ui.districts) { d ->
                            PickRow(d) { vm.pickDistrict(d) }
                        }
                    }
                }

                Step.HOUSE_TYPE -> {
                    SectionHeader("Typ zabudowy w ${ui.district}")
                    Text(
                        "Domy jednorodzinne i bloki mają w Rybniku osobne harmonogramy — " +
                            "ten sam adres może występować w obu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(
                        Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { vm.pickHouseType(HouseType.SINGLE_FAMILY) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Dom jednorodzinny") }
                        Button(
                            onClick = { vm.pickHouseType(HouseType.MULTI_FAMILY) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Blok / zabudowa wielorodzinna") }
                    }
                }

                Step.STREET -> {
                    OutlinedTextField(
                        value = ui.query,
                        onValueChange = vm::setQuery,
                        label = { Text("Szukaj ulicy") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                    val filtered = remember(ui.query, ui.streets) {
                        val q = ui.query.trim().lowercase()
                        if (q.isEmpty()) ui.streets
                        else ui.streets.filter { it.lowercase().contains(q) }
                    }
                    LazyColumn {
                        items(filtered) { s ->
                            PickRow(s) { vm.pickStreet(s) }
                        }
                    }
                }

                Step.NUMBER -> {
                    SectionHeader("Numer domu")
                    Text(
                        "${ui.street}, ${ui.district}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    OutlinedTextField(
                        value = ui.number,
                        onValueChange = vm::setNumber,
                        label = { Text("np. 38 albo 128B") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                    Button(
                        onClick = vm::save,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { Text("Zapisz adres") }
                }
            }
        }
    }
}

@Composable
private fun PickRow(label: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
}
