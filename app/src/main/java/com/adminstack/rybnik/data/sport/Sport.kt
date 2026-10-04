package com.adminstack.rybnik.data.sport

import android.content.Context
import com.adminstack.rybnik.core.net.CachedRemoteSource
import com.adminstack.rybnik.core.net.RemoteConfig
import com.adminstack.rybnik.core.net.sharedJson
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Serializable
data class SportPayload(
    val generated_at: String? = null,
    val teams: List<SportTeamDto> = emptyList(),
    val matches: List<MatchDto> = emptyList(),
)

@Serializable
data class SportTeamDto(
    val id: String,
    val name: String,
    val sport: String,
    val league: String,
    val url: String? = null,
)

@Serializable
data class MatchDto(
    val id: String,
    val teamId: String,
    val competition: String,
    val date: String,
    /** Null for spring fixtures that have a date but no kick-off time yet. */
    val time: String? = null,
    val home: String,
    val away: String,
    val isHome: Boolean,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val scoreNote: String? = null,
    val status: String = "SCHEDULED",
    /**
     * Defaults matter here: a phone updated before the scraper ships the new fields
     * still has the old sport.json in its cache, and must read it as before.
     */
    val stage: String = "REGULAR",
    val url: String? = null,
)

/**
 * Where in the season a match sits. [badge] is null for the regular season on purpose:
 * marking the ordinary rounds would bury the few matches that actually need it.
 */
enum class Stage(val badge: String?) {
    REGULAR(null),
    PLAYOFF("Play-off"),
    PLAYDOWN("Play-down"),
    BARRAGE("Baraż"),
    CUP("Puchar"),
}

enum class SportKind(val label: String) {
    FOOTBALL("Piłka nożna"),
    FOOTBALL_W("Piłka nożna kobiet"),
    SPEEDWAY("Żużel"),
    OTHER("Sport"),
}

data class SportTeam(
    val id: String,
    val name: String,
    val kind: SportKind,
    val league: String,
    val url: String?,
)

enum class Outcome { WIN, DRAW, LOSS }

data class Match(
    val id: String,
    val teamId: String,
    val competition: String,
    val date: LocalDate,
    val time: LocalTime?,
    val home: String,
    val away: String,
    val isHome: Boolean,
    val ourScore: Int?,
    val theirScore: Int?,
    val scoreNote: String?,
    val finished: Boolean,
    val stage: Stage = Stage.REGULAR,
    /** The match's own page: scorers and line-ups, or heat-by-heat results. */
    val url: String? = null,
) {
    val opponent: String get() = if (isHome) away else home

    /** Sorting key. A fixture without a kick-off sorts to the end of its day. */
    val startsAt: LocalDateTime get() = date.atTime(time ?: LocalTime.MAX)

    val outcome: Outcome?
        get() {
            val ours = ourScore ?: return null
            val theirs = theirScore ?: return null
            return when {
                ours > theirs -> Outcome.WIN
                ours < theirs -> Outcome.LOSS
                else -> Outcome.DRAW
            }
        }

    /**
     * The bare score, for where it is set large. The note goes on its own line there:
     * "39:51 dwumecz 87:93" in a headline style does not fit beside a team name.
     */
    val scoreText: String?
        get() {
            val ours = ourScore ?: return null
            val theirs = theirScore ?: return null
            return "$ours:$theirs"
        }

    /** "3:1", plus the walkover or penalty note the source carried. */
    val scoreLabel: String?
        get() {
            val ours = ourScore ?: return null
            val theirs = theirScore ?: return null
            return listOfNotNull("$ours:$theirs", scoreNote).joinToString(" ")
        }
}

fun MatchDto.toDomainOrNull(): Match? = runCatching {
    val ourGoals = if (isHome) homeScore else awayScore
    val theirGoals = if (isHome) awayScore else homeScore
    Match(
        id = id,
        teamId = teamId,
        competition = competition,
        date = LocalDate.parse(date),
        time = time?.let { LocalTime.parse(it) },
        home = home,
        away = away,
        isHome = isHome,
        ourScore = ourGoals,
        theirScore = theirGoals,
        scoreNote = scoreNote,
        // Trusting `status` alone would let a half-filled row render as "0:0".
        finished = status.equals("FINISHED", ignoreCase = true) &&
            ourGoals != null && theirGoals != null,
        stage = runCatching { Stage.valueOf(stage) }.getOrDefault(Stage.REGULAR),
        // Only web links leave the app; anything else in the feed is ignored, not opened.
        url = url?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
    )
}.getOrNull()

fun SportTeamDto.toDomain(): SportTeam = SportTeam(
    id = id,
    name = name,
    kind = runCatching { SportKind.valueOf(sport) }.getOrDefault(SportKind.OTHER),
    league = league,
    url = url,
)

/** What the dashboard shows: one match, and why it was picked. */
data class Highlight(
    val team: SportTeam,
    val match: Match,
    val isResult: Boolean,
)

class SportRepository(context: Context) :
    CachedRemoteSource<SportPayload>(context, RemoteConfig.dataUrl("sport.json"), "sport.json") {

    override fun parse(body: String): SportPayload = sharedJson.decodeFromString(body)

    fun teams(): List<SportTeam> = (data.value?.teams ?: emptyList()).map { it.toDomain() }

    fun matches(teamId: String? = null): List<Match> = (data.value?.matches ?: emptyList())
        .mapNotNull { it.toDomainOrNull() }
        .filter { teamId == null || it.teamId == teamId }
        .sortedBy { it.startsAt }

    fun team(id: String): SportTeam? = teams().firstOrNull { it.id == id }

    fun upcoming(teamId: String? = null, from: LocalDate = LocalDate.now()): List<Match> =
        matches(teamId).filter { !it.finished && !it.date.isBefore(from) }

    fun results(teamId: String? = null): List<Match> =
        matches(teamId).filter { it.finished }.sortedByDescending { it.startsAt }

    fun highlight(today: LocalDate = LocalDate.now()): Highlight? =
        selectHighlight(matches(), teams(), today)
}

/**
 * The single match worth a line on the home screen.
 *
 * A result stays interesting for a few days after the whistle, so it beats a fixture that
 * is still a week away. Outside that window the next kick-off wins. Speedway runs April to
 * September, so for half the year the only honest answer is the last match of the season,
 * and the card has to say so rather than imply it just happened.
 *
 * Kept free of the repository so it can be tested without an Android context.
 */
fun selectHighlight(
    matches: List<Match>,
    teams: List<SportTeam>,
    today: LocalDate,
    freshDays: Long = 3,
): Highlight? {
    val byId = teams.associateBy { it.id }
    val all = matches.filter { it.teamId in byId }
    if (all.isEmpty()) return null

    val fresh = all
        .filter { it.finished && !it.date.isBefore(today.minusDays(freshDays)) }
        .maxByOrNull { it.startsAt }
    if (fresh != null) return Highlight(byId.getValue(fresh.teamId), fresh, isResult = true)

    val next = all.filter { !it.finished && !it.date.isBefore(today) }.minByOrNull { it.startsAt }
    if (next != null) return Highlight(byId.getValue(next.teamId), next, isResult = false)

    val last = all.filter { it.finished }.maxByOrNull { it.startsAt } ?: return null
    return Highlight(byId.getValue(last.teamId), last, isResult = true)
}
