package com.adminstack.rybnik.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The dates here mirror what the live feed looked like on 26 September 2026: a handful
 * of August alerts that the scraper never drops, sitting alongside this week's news.
 */
class NewsTest {

    private val now: LocalDateTime = LocalDateTime.parse("2026-09-26T07:00")

    private fun item(id: String, published: String, alert: Boolean) = NewsItemDto(
        id = id,
        title = id,
        summary = null,
        link = "https://example.test/$id",
        published = published,
        source = "test",
        category = "Wiadomości",
        priority = if (alert) "ALERT" else "NORMAL",
    ).toDomainOrNull()!!

    private fun sorted(items: List<NewsItem>): List<String> =
        items.sortedWith(
            compareByDescending<NewsItem> {
                it.isAlert && it.published.isAfter(now.minusDays(NewsRepository.ALERT_PIN_DAYS))
            }.thenByDescending { it.published }
        ).map { it.id }

    @Test
    fun `a current alert outranks newer ordinary news`() {
        val order = sorted(
            listOf(
                item("zwykla-dzisiaj", "2026-09-26T06:00", alert = false),
                item("alert-wczoraj", "2026-09-25T09:00", alert = true),
            )
        )
        assertEquals(listOf("alert-wczoraj", "zwykla-dzisiaj"), order)
    }

    /** The August roadworks notice that used to open the news tab. */
    @Test
    fun `an alert past the pin window falls back into the timeline`() {
        val order = sorted(
            listOf(
                item("alert-sierpniowy", "2026-08-28T10:00", alert = true),
                item("zwykla-wczoraj", "2026-09-25T09:00", alert = false),
                item("alert-wczoraj", "2026-09-25T10:00", alert = true),
            )
        )
        assertEquals(listOf("alert-wczoraj", "zwykla-wczoraj", "alert-sierpniowy"), order)
    }

    @Test
    fun `an alert exactly on the boundary is no longer pinned`() {
        val boundary = now.minusDays(NewsRepository.ALERT_PIN_DAYS)
        val stale = item("alert-na-granicy", boundary.toString(), alert = true)
        assertTrue(!stale.published.isAfter(boundary))
    }

    @Test
    fun `hidden ids never reach the list`() {
        val all = listOf(
            item("zostaje", "2026-09-25T09:00", alert = false),
            item("ukryta", "2026-09-25T10:00", alert = true),
        )
        val visible = all.filter { it.id !in setOf("ukryta") }
        assertEquals(listOf("zostaje"), visible.map { it.id })
    }

    @Test
    fun `an unparseable date drops the item instead of crashing the list`() {
        val broken = NewsItemDto(
            id = "zepsuta",
            title = "zepsuta",
            summary = null,
            link = "https://example.test/x",
            published = "wczoraj wieczorem",
            source = "test",
            category = null,
        ).toDomainOrNull()
        assertEquals(null, broken)
    }
}
