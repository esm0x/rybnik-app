package com.adminstack.rybnik.data.news

import android.content.Context
import com.adminstack.rybnik.core.net.CachedRemoteSource
import com.adminstack.rybnik.core.net.RemoteConfig
import com.adminstack.rybnik.core.net.sharedJson
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

    /**
     * Alerts float to the top, but only while they are still current.
     *
     * The scraper never drops an ALERT by age or by the 120-item cap, so pinning every
     * one of them put "Utrudnienia potrwają do 11 września" from August above everything
     * from this week — the first thing a new user saw was a roadworks notice about
     * something that had already ended. Past that window an alert keeps its styling and
     * simply takes its place in the timeline.
     */
    fun items(
        hidden: Set<String> = emptySet(),
        now: LocalDateTime = LocalDateTime.now(),
    ): List<NewsItem> {
        val cutoff = now.minusDays(ALERT_PIN_DAYS)
        return all()
            .filter { it.id !in hidden }
            .sortedWith(
                compareByDescending<NewsItem> { it.isAlert && it.published.isAfter(cutoff) }
                    .thenByDescending { it.published }
            )
    }

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
    fun currentAlerts(
        hidden: Set<String> = emptySet(),
        maxAgeDays: Long = ALERT_PIN_DAYS,
    ): List<NewsItem> {
        val cutoff = LocalDateTime.now().minusDays(maxAgeDays)
        return items(hidden)
            .filter { it.isAlert && it.published.isAfter(cutoff) }
            .sortedByDescending { it.published }
    }

    companion object {
        /** How long an alert stays pinned, on the dashboard and at the top of the list. */
        const val ALERT_PIN_DAYS = 14L
    }
}
