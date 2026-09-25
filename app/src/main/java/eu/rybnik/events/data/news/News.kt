package eu.rybnik.events.data.news

import android.content.Context
import eu.rybnik.events.core.net.CachedRemoteSource
import eu.rybnik.events.core.net.RemoteConfig
import eu.rybnik.events.core.net.sharedJson
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

@Serializable
data class NewsPayload(
    val generated_at: String? = null,
    val items: List<NewsItemDto> = emptyList(),
)

@Serializable
data class NewsItemDto(
    val id: String,
    val title: String,
    val summary: String? = null,
    val link: String,
    val published: String,
    val source: String,
    val category: String? = null,
    val priority: String = "NORMAL",
)

data class NewsItem(
    val id: String,
    val title: String,
    val summary: String?,
    val link: String,
    val published: LocalDateTime,
    val source: String,
    val category: String?,
    val isAlert: Boolean,
)

fun NewsItemDto.toDomainOrNull(): NewsItem? = runCatching {
    NewsItem(
        id = id,
        title = title,
        summary = summary,
        link = link,
        published = LocalDateTime.parse(published),
        source = source,
        category = category,
        isAlert = priority.equals("ALERT", ignoreCase = true),
    )
}.getOrNull()

class NewsRepository(context: Context) :
    CachedRemoteSource<NewsPayload>(context, RemoteConfig.dataUrl("news.json"), "news.json") {

    override fun parse(body: String): NewsPayload = sharedJson.decodeFromString(body)

    /** Alerts float to the top regardless of age, that is the whole point of the flag. */
    fun items(hidden: Set<String> = emptySet()): List<NewsItem> = all()
        .filter { it.id !in hidden }
        .sortedWith(compareByDescending<NewsItem> { it.isAlert }.thenByDescending { it.published })

    /** The ones the user dismissed, newest first, so they can be restored. */
    fun hiddenItems(hidden: Set<String>): List<NewsItem> = all()
        .filter { it.id in hidden }
        .sortedByDescending { it.published }

    /** Every id in the feed, hidden included. Lets the prefs prune stale dismissals. */
    fun allIds(): Set<String> = (data.value?.items ?: emptyList()).map { it.id }.toSet()

    private fun all(): List<NewsItem> =
        (data.value?.items ?: emptyList()).mapNotNull { it.toDomainOrNull() }

    fun categories(hidden: Set<String> = emptySet()): List<String> =
        items(hidden).mapNotNull { it.category }.distinct().sorted()

    /**
     * Alerts worth putting on the home screen. Age matters here: a roadworks notice from
     * June is still technically an alert and would otherwise sit on the dashboard for
     * months, so anything older than [maxAgeDays] stays in the news list only.
     */
    fun currentAlerts(hidden: Set<String> = emptySet(), maxAgeDays: Long = 14): List<NewsItem> {
        val cutoff = LocalDateTime.now().minusDays(maxAgeDays)
        return items(hidden)
            .filter { it.isAlert && it.published.isAfter(cutoff) }
            .sortedByDescending { it.published }
    }
}
