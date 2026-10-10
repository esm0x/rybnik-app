package com.adminstack.rybnik.data.announce

import com.adminstack.rybnik.core.net.sharedJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnnouncementTest {

    private val today = LocalDate.of(2026, 10, 10)
    private fun parse(json: String): AnnouncementDto = sharedJson.decodeFromString(json)

    @Test
    fun `an empty file means nothing to say`() {
        assertNull(parse("{}").activeFor(17, today))
        assertNull(parse("""{"id": "x"}""").activeFor(17, today))
        assertNull(parse("""{"id": " ", "title": "Hej"}""").activeFor(17, today))
    }

    @Test
    fun `a minimal message is shown and notifies by default`() {
        val a = parse("""{"id": "a1", "title": "Hej"}""").activeFor(17, today)
        assertNotNull(a)
        assertTrue(a!!.notify)
        assertNull(a.body)
        assertEquals("Otwórz", a.linkLabel)
    }

    @Test
    fun `version range targets old builds only`() {
        val dto = parse("""{"id": "upd", "title": "Zaktualizuj", "max_version": 17}""")
        assertNotNull(dto.activeFor(16, today))
        assertNotNull(dto.activeFor(17, today))
        assertNull(dto.activeFor(18, today))
        val fresh = parse("""{"id": "new", "title": "Nowość", "min_version": 18}""")
        assertNull(fresh.activeFor(17, today))
        assertNotNull(fresh.activeFor(18, today))
    }

    @Test
    fun `until is the last day it shows, and a typo hides it`() {
        val dto = parse("""{"id": "a", "title": "T", "until": "2026-10-10"}""")
        assertNotNull(dto.activeFor(17, today))
        assertNull(dto.activeFor(17, today.plusDays(1)))
        assertNull(parse("""{"id": "a", "title": "T", "until": "10.10.2026"}""").activeFor(17, today))
    }

    @Test
    fun `only web links survive`() {
        val ok = parse("""{"id": "a", "title": "T", "link": "https://example.com", "link_label": "Zobacz"}""")
            .activeFor(17, today)!!
        assertEquals("https://example.com", ok.link)
        assertEquals("Zobacz", ok.linkLabel)
        val bad = parse("""{"id": "a", "title": "T", "link": "intent://evil#Intent;end"}""")
            .activeFor(17, today)!!
        assertNull(bad.link)
    }

    @Test
    fun `announced once, and not at all if already read or closed`() {
        val a = parse("""{"id": "a1", "title": "Hej"}""").activeFor(17, today)!!
        assertTrue(a.shouldNotify(seenId = null, dismissedId = null))
        assertTrue(a.shouldNotify(seenId = "older", dismissedId = "older"))
        assertFalse(a.shouldNotify(seenId = "a1", dismissedId = null))
        assertFalse(a.shouldNotify(seenId = null, dismissedId = "a1"))
        val quiet = parse("""{"id": "q", "title": "Hej", "notify": false}""").activeFor(17, today)!!
        assertFalse(quiet.shouldNotify(null, null))
    }

    @Test
    fun `unknown fields from a newer format are ignored`() {
        assertNotNull(parse("""{"id": "a", "title": "T", "priority": "high"}""").activeFor(17, today))
    }
}
