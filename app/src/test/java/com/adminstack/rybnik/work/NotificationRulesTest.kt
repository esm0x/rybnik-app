package com.adminstack.rybnik.work

import com.adminstack.rybnik.data.news.NewsItemDto
import com.adminstack.rybnik.data.news.toDomainOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The scenarios are the ones that lost notifications in practice, reconstructed from the
 * scraper's git history between 20 September and 4 October 2026.
 */
class NotificationRulesTest {

    private val now = LocalDateTime.parse("2026-10-04T18:00")

    private fun alert(id: String, published: String, priority: String = "ALERT") = NewsItemDto(
        id = id,
        title = "Komunikat $id",
        summary = null,
        link = "https://example.test/$id",
        published = published,
        source = "rybnik.com.pl",
        category = "Wiadomości",
        priority = priority,
    ).toDomainOrNull()!!

    private fun pick(
        vararg items: com.adminstack.rybnik.data.news.NewsItem,
        hidden: Set<String> = emptySet(),
        seen: Set<String> = emptySet(),
        ledger: Set<String> = emptySet(),
    ) = NotificationRules.alertsToNotify(items.toList(), hidden, seen, ledger, now).map { it.id }

    /** The old rule took only the newest; on a day with three, two were lost. */
    @Test
    fun `every new alert gets its turn, not just the newest`() {
        val picked = pick(
            alert("rano", "2026-10-04T08:00"),
            alert("poludnie", "2026-10-04T12:00"),
            alert("wieczor", "2026-10-04T17:00"),
        )
        assertEquals(listOf("wieczor", "poludnie", "rano"), picked)
    }

    /**
     * Published at 21:00, scraped after midnight: the old "is it from today" test failed
     * it on every run that followed, so it was never announced at all.
     */
    @Test
    fun `an alert that reached the feed the next day is still announced`() {
        assertEquals(listOf("wczoraj"), pick(alert("wczoraj", "2026-10-03T21:00")))
    }

    @Test
    fun `an alert past the age cap is not dragged out of the backlog`() {
        assertTrue(pick(alert("stary", "2026-09-30T10:00")).isEmpty())
    }

    /** The tester's complaint: the one notification that did arrive was already read. */
    @Test
    fun `an alert already shown in the app is not announced`() {
        val picked = pick(
            alert("przeczytany", "2026-10-04T10:00"),
            alert("nowy", "2026-10-04T11:00"),
            seen = setOf("przeczytany"),
        )
        assertEquals(listOf("nowy"), picked)
    }

    @Test
    fun `an alert announced on an earlier run is not announced again`() {
        val once = alert("raz", "2026-10-04T10:00")
        assertTrue(pick(once, ledger = setOf(NotificationRules.alertKey(once))).isEmpty())
    }

    @Test
    fun `a hidden alert and ordinary news stay quiet`() {
        val picked = pick(
            alert("ukryty", "2026-10-04T10:00"),
            alert("zwykly", "2026-10-04T10:00", priority = "NORMAL"),
            hidden = setOf("ukryty"),
        )
        assertTrue(picked.isEmpty())
    }

    /** The key is built from the publication day, so it is identical on every run. */
    @Test
    fun `an alert key does not drift between runs`() {
        val a = alert("staly", "2026-10-03T21:00")
        assertEquals("2026-10-03|alert|staly", NotificationRules.alertKey(a))
    }

    @Test
    fun `the ledger forgets old entries and anything malformed`() {
        val today = LocalDate.parse("2026-10-04")
        val kept = NotificationRules.prune(
            setOf(
                "2026-10-04|waste|maroko-nowiny-3",
                "2026-09-20|alert|na-granicy",
                "2026-09-19|alert|za-stary",
                "smieci-bez-daty",
            ),
            today,
        )
        assertEquals(setOf("2026-10-04|waste|maroko-nowiny-3", "2026-09-20|alert|na-granicy"), kept)
    }

    @Test
    fun `the headline agrees with its number in Polish`() {
        val expected = mapOf(
            1 to "Nowy komunikat",
            2 to "2 nowe komunikaty",
            4 to "4 nowe komunikaty",
            5 to "5 nowych komunikatów",
            12 to "12 nowych komunikatów",
            14 to "14 nowych komunikatów",
            22 to "22 nowe komunikaty",
        )
        expected.forEach { (n, text) -> assertEquals(text, NotificationRules.newAlertsHeadline(n)) }
    }
}
