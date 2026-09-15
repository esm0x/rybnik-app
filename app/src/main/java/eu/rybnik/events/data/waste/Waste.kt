package eu.rybnik.events.data.waste

import eu.rybnik.events.core.prefs.HouseType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields

enum class WasteType(val label: String, val color: Long) {
    ZMIESZANE("Zmieszane", 0xFF6B7280),
    SEGREGOWANE("Segregowane", 0xFFF59E0B),
    PLASTIK("Plastik i metal", 0xFFF59E0B),
    PAPIER("Papier", 0xFF3B82F6),
    SZKLO("Szkło", 0xFF10B981),
    BIO("Bio", 0xFF84CC16),
    POPIOLY("Popiół / żużel", 0xFF78716C),
    GABARYTY("Gabaryty i elektro", 0xFF8B5CF6);

    companion object {
        fun from(raw: String) = entries.firstOrNull { it.name == raw } ?: ZMIESZANE
    }
}

enum class Parity { ODD, EVEN }

@Serializable
data class WastePayload(
    val generated_at: String? = null,
    val year: Int? = null,
    val rejony: List<RejonDto> = emptyList(),
)

@Serializable
data class RejonDto(
    val id: String,
    val name: String,
    val district: String,
    val houseType: String,
    val streets: List<StreetDto> = emptyList(),
    val pickups: List<PickupDto> = emptyList(),
    val weekdayRules: List<WeekdayRuleDto> = emptyList(),
)

@Serializable
data class StreetDto(val name: String, val rules: List<NumberRuleDto> = emptyList())

@Serializable
data class NumberRuleDto(
    val from: Int? = null,
    val to: Int? = null,
    val parity: String? = null,
)

@Serializable
data class PickupDto(val type: String, val dates: List<String> = emptyList())

@Serializable
data class WeekdayRuleDto(
    val type: String,
    val weekdays: List<String> = emptyList(),
    @SerialName("weekParity") val weekParity: String? = null,
)

/** One collection on one day — what the UI actually renders. */
data class Collection(val date: LocalDate, val types: List<WasteType>)

/**
 * Street entries carry number constraints like "od 27 niep. i od 22 parz.", so a street
 * name alone is not enough to place an address in a rejon.
 */
fun StreetDto.matches(houseNumber: String): Boolean {
    if (rules.isEmpty()) return true
    val n = houseNumber.takeWhile { it.isDigit() }.toIntOrNull() ?: return true
    return rules.any { r ->
        val lowOk = r.from == null || n >= r.from
        val highOk = r.to == null || n <= r.to
        val parityOk = when (r.parity) {
            "ODD" -> n % 2 == 1
            "EVEN" -> n % 2 == 0
            else -> true
        }
        lowOk && highOk && parityOk
    }
}

private val STREET_PREFIX = Regex("""^(ul\.|ulica|al\.|aleja|pl\.|plac|os\.|osiedle)\s*""")
private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]""")

private fun normalise(s: String) = s.lowercase()
    .replace(STREET_PREFIX, "")
    .replace(NON_ALPHANUMERIC, "")

/** Street names in the PDFs are abbreviated inconsistently (`Ks.H.Jośki`, `Św.Józefa`). */
fun streetMatches(entry: String, query: String): Boolean {
    val a = normalise(entry)
    val b = normalise(query)
    return a == b || a.endsWith(b) || b.endsWith(a)
}

class WasteSchedule(private val rejony: List<RejonDto>) {

    val districts: List<String> = rejony.map { it.district }.distinct().sorted()

    fun streetsIn(district: String, houseType: HouseType): List<String> =
        rejony.filter { it.district == district && it.houseType == houseType.name }
            .flatMap { it.streets }
            .map { it.name }
            .distinct()
            .sortedBy { normalise(it) }

    fun resolveRejon(
        district: String,
        street: String,
        houseNumber: String,
        houseType: HouseType,
    ): RejonDto? = rejony
        .filter { it.district == district && it.houseType == houseType.name }
        .firstOrNull { rejon ->
            rejon.streets.any { streetMatches(it.name, street) && it.matches(houseNumber) }
        }

    fun byId(id: String): RejonDto? = rejony.firstOrNull { it.id == id }

    /**
     * Single-family rejony ship explicit dates; multi-family ship weekday rules that we
     * expand here, because the PDFs express them as "piątek tydzień nieparzysty".
     */
    fun collections(rejonId: String, from: LocalDate, until: LocalDate): List<Collection> {
        val rejon = byId(rejonId) ?: return emptyList()
        val byDate = sortedMapOf<LocalDate, MutableSet<WasteType>>()

        rejon.pickups.forEach { pickup ->
            val type = WasteType.from(pickup.type)
            pickup.dates.forEach { raw ->
                val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return@forEach
                if (date in from..until) byDate.getOrPut(date) { mutableSetOf() } += type
            }
        }

        if (rejon.weekdayRules.isNotEmpty()) {
            val weekField = WeekFields.ISO.weekOfWeekBasedYear()
            var day = from
            while (!day.isAfter(until)) {
                val weekNo = day.get(weekField)
                rejon.weekdayRules.forEach { rule ->
                    val days = rule.weekdays.mapNotNull {
                        runCatching { DayOfWeek.valueOf(it) }.getOrNull()
                    }
                    if (day.dayOfWeek !in days) return@forEach
                    val parityOk = when (rule.weekParity) {
                        "ODD" -> weekNo % 2 == 1
                        "EVEN" -> weekNo % 2 == 0
                        else -> true
                    }
                    if (parityOk) {
                        byDate.getOrPut(day) { mutableSetOf() } += WasteType.from(rule.type)
                    }
                }
                day = day.plusDays(1)
            }
        }

        return byDate.map { (date, types) -> Collection(date, types.sortedBy { it.ordinal }) }
    }

    fun nextCollection(rejonId: String, from: LocalDate = LocalDate.now()): Collection? =
        collections(rejonId, from, from.plusDays(60)).firstOrNull()
}
