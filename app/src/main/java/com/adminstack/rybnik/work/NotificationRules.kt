package com.adminstack.rybnik.work

import com.adminstack.rybnik.data.news.NewsItem
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * What deserves a notification, decided without touching Android so it can be tested.
 *
 * The first version asked one question — "is the newest alert from today?" — and that
 * one question lost most of them. Measured against the scraper's own history from
 * 20 September: of 17 new alerts, 4 reached the feed only the day after publication and
 * so failed "today" forever, and 6 more lost to a sibling on a day with several, because
 * only the newest was ever considered. One tester received a single notification in a
 * week, and had already read it in the app.
 *
 * So the rules now are:
 *  - every alert gets its own chance, not just the newest;
 *  - what matters is that we have not told the user yet, not the day it was written;
 *  - an alert the user has already seen on screen is not news to them;
 *  - each thing is announced once, which the ledger enforces across runs.
 */
object NotificationRules {

    /**
     * An alert older than this is not worth interrupting anyone for, however new it is to
     * us. Without the cap, the first run after an update would dump a week of backlog.
     */
    const val ALERT_MAX_AGE_HOURS = 72L

    /** Above this many in one run, the rest are announced only inside the summary. */
    const val MAX_ALERT_NOTIFICATIONS = 5

    /** Ledger entries are forgotten after this long, which keeps the set small. */
    const val LEDGER_KEEP_DAYS = 14L

    /**
     * New alerts to announce, newest first.
     *
     * @param seen alerts that have been on screen in the app
     * @param ledger keys of everything already announced, from [alertKey] and friends
     */
    fun alertsToNotify(
        items: List<NewsItem>,
        hidden: Set<String>,
        seen: Set<String>,
        ledger: Set<String>,
        now: LocalDateTime,
    ): List<NewsItem> {
        val cutoff = now.minusHours(ALERT_MAX_AGE_HOURS)
        return items
            .filter { it.isAlert }
            .filter { it.id !in hidden && it.id !in seen }
            .filter { it.published.isAfter(cutoff) }
            .filter { alertKey(it) !in ledger }
            .sortedByDescending { it.published }
    }

    /**
     * Ledger keys start with a date so that pruning needs nothing but the key itself.
     * The date is whatever the reminder is *about* — the collection day, the event day —
     * and for an alert its publication day, which never changes between runs.
     */
    fun key(date: LocalDate, kind: String, id: String): String = "$date|$kind|$id"

    fun alertKey(item: NewsItem): String = key(item.published.toLocalDate(), "alert", item.id)

    /** Drops entries older than [LEDGER_KEEP_DAYS], and anything that does not parse. */
    fun prune(ledger: Set<String>, today: LocalDate): Set<String> {
        val oldest = today.minusDays(LEDGER_KEEP_DAYS)
        return ledger.filterTo(mutableSetOf()) { entry ->
            val date = runCatching { LocalDate.parse(entry.substringBefore('|')) }.getOrNull()
            date != null && !date.isBefore(oldest)
        }
    }

    /**
     * "Nowy komunikat", "3 nowe komunikaty", "5 nowych komunikatów".
     *
     * Polish needs three forms, and the middle one stops at 12-14 ("12 nowych"),
     * which is exactly the kind of slip a notification headline puts on display.
     */
    fun newAlertsHeadline(count: Int): String {
        val lastDigit = count % 10
        val lastTwo = count % 100
        return when {
            count == 1 -> "Nowy komunikat"
            lastDigit in 2..4 && lastTwo !in 12..14 -> "$count nowe komunikaty"
            else -> "$count nowych komunikatów"
        }
    }
}
