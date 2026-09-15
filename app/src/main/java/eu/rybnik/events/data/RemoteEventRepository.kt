package eu.rybnik.events.data

import android.content.Context
import eu.rybnik.events.core.net.CachedRemoteSource
import eu.rybnik.events.core.net.RemoteConfig
import eu.rybnik.events.core.net.sharedJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

class RemoteEventRepository(context: Context) : EventRepository, CachedRemoteSource<EventsPayload>(
    context,
    RemoteConfig.dataUrl("events.json"),
    "events.json",
) {
    override fun parse(body: String): EventsPayload = sharedJson.decodeFromString(body)

    override val events: Flow<List<Event>> =
        data.map { payload -> payload?.events?.mapNotNull { it.toDomainOrNull() } ?: emptyList() }

    override fun getById(id: String): Event? =
        data.value?.events?.firstOrNull { it.id == id }?.toDomainOrNull()

    fun snapshot(): List<Event> =
        data.value?.events?.mapNotNull { it.toDomainOrNull() }?.sortedBy { it.start } ?: emptyList()
}

@Serializable
data class EventsPayload(
    val generated_at: String? = null,
    val sources: List<String> = emptyList(),
    val count: Int? = null,
    val events: List<EventDto> = emptyList(),
)

@Serializable
data class EventDto(
    val id: String,
    val title: String,
    val category: String,
    val start: String,
    val end: String? = null,
    val venue: String,
    val description: String? = null,
    val sourceName: String,
    val sourceUrl: String,
) {
    fun toDomainOrNull(): Event? = runCatching {
        Event(
            id = id,
            title = title,
            category = runCatching { EventCategory.valueOf(category) }
                .getOrDefault(EventCategory.Inne),
            start = LocalDateTime.parse(start),
            end = end?.let { LocalDateTime.parse(it) },
            venue = venue,
            description = description,
            sourceName = sourceName,
            sourceUrl = sourceUrl,
        )
    }.getOrNull()
}
