package com.adminstack.rybnik.data.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Rows here are copied from a real sport.json run: the 2026/27 III liga season for
 * ROW 1964, and the 2026 speedway season that ended on 13 August.
 */
class SportTest {

    private val football = SportTeam(
        id = "row-1964",
        name = "ROW 1964 Rybnik",
        kind = SportKind.FOOTBALL,
        league = "III liga, gr. III",
        url = null,
    )
    private val speedway = SportTeam(
        id = "row-zuzel",
        name = "INNPRO ROW Rybnik",
        kind = SportKind.SPEEDWAY,
        league = "Metalkas 2. Ekstraliga",
        url = null,
    )
    private val teams = listOf(football, speedway)

    private fun dto(
        id: String,
        team: String = "row-1964",
        date: String,
        time: String? = "18:00",
        home: String = "ROW 1964 Rybnik",
        away: String = "Stal Brzeg",
        isHome: Boolean = true,
        homeScore: Int? = null,
        awayScore: Int? = null,
        note: String? = null,
        status: String = "SCHEDULED",
    ) = MatchDto(
        id = id, teamId = team, competition = "III liga", date = date, time = time,
        home = home, away = away, isHome = isHome, homeScore = homeScore,
        awayScore = awayScore, scoreNote = note, status = status,
    )

    @Test
    fun `away match flips the score onto our side`() {
        val m = dto(
            id = "a", date = "2026-09-19", time = "15:00",
            home = "KS Stilon Gorzów Wielkopolski", away = "ROW 1964 Rybnik",
            isHome = false, homeScore = 1, awayScore = 3, status = "FINISHED",
        ).toDomainOrNull()!!

        assertEquals(3, m.ourScore)
        assertEquals(1, m.theirScore)
        assertEquals("KS Stilon Gorzów Wielkopolski", m.opponent)
        assertEquals(Outcome.WIN, m.outcome)
        assertEquals("3:1", m.scoreLabel)
    }

    @Test
    fun `walkover and penalties keep their note`() {
        val walkover = dto(
            id = "wo", date = "2026-05-02", time = null,
            home = "Znicz Kłobuck", away = "ROW 1964 Rybnik", isHome = false,
            homeScore = 0, awayScore = 3, note = "wo", status = "FINISHED",
        ).toDomainOrNull()!!
        assertEquals("3:0 wo", walkover.scoreLabel)

        val shootout = dto(
            id = "k", date = "2026-04-15", homeScore = 0, awayScore = 0,
            note = "k. 6-7", status = "FINISHED",
        ).toDomainOrNull()!!
        assertEquals("0:0 k. 6-7", shootout.scoreLabel)
        assertEquals(Outcome.DRAW, shootout.outcome)
    }

    /** 90minut marks a row played before the score is entered; "0:0" would be a lie. */
    @Test
    fun `finished without a score is not treated as played`() {
        val m = dto(id = "x", date = "2026-09-26", status = "FINISHED").toDomainOrNull()!!
        assertFalse(m.finished)
        assertNull(m.scoreLabel)
        assertNull(m.outcome)
    }

    @Test
    fun `fixture without a kick-off sorts to the end of its day`() {
        val noTime = dto(id = "n", date = "2026-10-31", time = null).toDomainOrNull()!!
        val withTime = dto(id = "t", date = "2026-10-31", time = "17:00").toDomainOrNull()!!
        assertTrue(withTime.startsAt < noTime.startsAt)
    }

    @Test
    fun `a fresh result beats a fixture still days away`() {
        val played = dto(
            id = "p", date = "2026-09-19", homeScore = 2, awayScore = 1, status = "FINISHED",
        ).toDomainOrNull()!!
        val ahead = dto(id = "f", date = "2026-09-26").toDomainOrNull()!!

        val pick = selectHighlight(listOf(played, ahead), teams, LocalDate.parse("2026-09-21"))!!

        assertTrue(pick.isResult)
        assertEquals("p", pick.match.id)
    }

    @Test
    fun `once the result goes stale the next kick-off takes over`() {
        val played = dto(
            id = "p", date = "2026-09-19", homeScore = 2, awayScore = 1, status = "FINISHED",
        ).toDomainOrNull()!!
        val ahead = dto(id = "f", date = "2026-09-26").toDomainOrNull()!!

        val pick = selectHighlight(listOf(played, ahead), teams, LocalDate.parse("2026-09-24"))!!

        assertFalse(pick.isResult)
        assertEquals("f", pick.match.id)
    }

    /** Speedway between October and April: nothing ahead, so the last match stands. */
    @Test
    fun `off season falls back to the last played match`() {
        val last = dto(
            id = "z", team = "row-zuzel", date = "2026-08-13", time = "20:30",
            home = "CELLFAST WILKI Krosno", away = "INNPRO ROW Rybnik", isHome = false,
            homeScore = 42, awayScore = 48, status = "FINISHED",
        ).toDomainOrNull()!!

        val pick = selectHighlight(listOf(last), teams, LocalDate.parse("2026-11-20"))!!

        assertTrue(pick.isResult)
        assertEquals(speedway, pick.team)
        assertEquals("48:42", pick.match.scoreLabel)
    }

    @Test
    fun `a match whose team is missing from the payload is ignored`() {
        val orphan = dto(id = "o", team = "nie-ma-takiej", date = "2026-09-26").toDomainOrNull()!!
        assertNull(selectHighlight(listOf(orphan), teams, LocalDate.parse("2026-09-25")))
    }

    @Test
    fun `unknown sport does not blow up the team mapping`() {
        val team = SportTeamDto("x", "Klub", "KRYKIET", "liga", null).toDomain()
        assertEquals(SportKind.OTHER, team.kind)
    }

    /** The 2026 semi-final second leg, exactly as the scraper writes it from ekstraliga.pl. */
    private val semiFinalReturnLeg = MatchDto(
        id = "row-zuzel-2026-09-06-hunters-psz-poznan",
        teamId = "row-zuzel",
        competition = "Półfinał, rewanż",
        date = "2026-09-06",
        time = "13:00",
        home = "HUNTERS PSŻ Poznań",
        away = "INNPRO ROW Rybnik",
        isHome = false,
        homeScore = 51,
        awayScore = 39,
        scoreNote = "dwumecz 87:93",
        status = "FINISHED",
        stage = "PLAYOFF",
        url = "https://ekstraliga.pl/se/mecz/7724",
    )

    @Test
    fun `a play-off match keeps its stage and its match page`() {
        val m = semiFinalReturnLeg.toDomainOrNull()!!
        assertEquals(Stage.PLAYOFF, m.stage)
        assertEquals("Play-off", m.stage.badge)
        assertEquals("https://ekstraliga.pl/se/mecz/7724", m.url)
    }

    /** The big score and the aggregate are drawn on separate lines, so they are separate. */
    @Test
    fun `the bare score and the aggregate are kept apart`() {
        val m = semiFinalReturnLeg.toDomainOrNull()!!
        assertEquals("39:51", m.scoreText)
        assertEquals("39:51 dwumecz 87:93", m.scoreLabel)
        assertEquals(Outcome.LOSS, m.outcome)
    }

    @Test
    fun `the regular season carries no badge`() {
        val m = dto(id = "r", date = "2026-04-05").toDomainOrNull()!!
        assertEquals(Stage.REGULAR, m.stage)
        assertNull(m.stage.badge)
    }

    @Test
    fun `an unknown stage falls back to the regular season instead of failing`() {
        val m = semiFinalReturnLeg.copy(stage = "MISTRZOSTWA_GALAKTYKI").toDomainOrNull()!!
        assertEquals(Stage.REGULAR, m.stage)
    }

    /**
     * The link comes from a remote file and goes straight into ACTION_VIEW. Anything
     * that is not a web address is dropped rather than handed to the system.
     */
    @Test
    fun `only web links are allowed out of the app`() {
        fun urlOf(raw: String?) = semiFinalReturnLeg.copy(url = raw).toDomainOrNull()!!.url

        assertEquals("https://ekstraliga.pl/se/mecz/7724", urlOf("https://ekstraliga.pl/se/mecz/7724"))
        // 90minut answers on plain http only; the browser opens it, not the app's own client.
        assertEquals(
            "http://www.90minut.pl/mecz.php?id_mecz=2114936",
            urlOf("http://www.90minut.pl/mecz.php?id_mecz=2114936"),
        )
        assertNull(urlOf("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(urlOf("javascript:alert(1)"))
        assertNull(urlOf("file:///data/data/com.adminstack.rybnik/files/sport.json"))
        assertNull(urlOf(null))
    }

    /**
     * A phone that updates the app before the scraper publishes the new fields still has
     * the old sport.json cached. It must load, with every match treated as regular season.
     */
    @Test
    fun `a sport json from before stage and url still loads`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val old = """
            {"teams": [], "matches": [{
              "id": "a", "teamId": "row-1964", "competition": "III liga, Kolejka 1",
              "date": "2026-08-01", "time": "18:00",
              "home": "ROW 1964 Rybnik", "away": "Warta Gorzów Wielkopolski", "isHome": true,
              "homeScore": 5, "awayScore": 1, "scoreNote": null, "status": "FINISHED"
            }]}
        """.trimIndent()

        val m = json.decodeFromString<SportPayload>(old).matches.single().toDomainOrNull()!!

        assertEquals(Stage.REGULAR, m.stage)
        assertNull(m.url)
        assertEquals("5:1", m.scoreText)
    }
}
