package eu.rybnik.events.data

import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

enum class EventCategory(val label: String, val color: Long) {
    Koncert("Koncert", 0xFF8B5CF6),
    Spektakl("Spektakl", 0xFFEC4899),
    Kabaret("Kabaret", 0xFFF59E0B),
    Film("Film", 0xFF3B82F6),
    Festiwal("Festiwal", 0xFFF97316),
    Wystawa("Wystawa", 0xFF10B981),
    Warsztaty("Warsztaty", 0xFF06B6D4),
    Impreza("Impreza", 0xFFD946EF),
    Sport("Sport", 0xFF22C55E),
    DlaDzieci("Dla dzieci", 0xFF14B8A6),
    Inne("Inne", 0xFF6B7280),
}

data class Event(
    val id: String,
    val title: String,
    val category: EventCategory,
    val start: LocalDateTime,
    val end: LocalDateTime? = null,
    val venue: String,
    val description: String? = null,
    val sourceName: String,
    val sourceUrl: String,
)

interface EventRepository {
    val events: Flow<List<Event>>
    fun getById(id: String): Event?
}

enum class EventTiming { UPCOMING, ONGOING, FINISHED }

/**
 * Only about half the sources give an end time, so when it is missing assume a typical
 * two-hour slot rather than treating the event as instantaneous — otherwise a concert
 * would flip to "finished" the minute it starts.
 */
fun Event.timing(now: LocalDateTime = LocalDateTime.now()): EventTiming {
    val finish = end ?: start.plusHours(2)
    return when {
        now.isBefore(start) -> EventTiming.UPCOMING
        now.isAfter(finish) -> EventTiming.FINISHED
        else -> EventTiming.ONGOING
    }
}
