package com.adminstack.rybnik.data.transit

import android.content.Context
import com.adminstack.rybnik.core.net.CachedRemoteSource
import com.adminstack.rybnik.core.net.friendlyNetworkError
import com.adminstack.rybnik.core.net.RemoteConfig
import com.adminstack.rybnik.core.net.sharedJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Serializable
data class TransitMeta(
    val generated_at: String? = null,
    val gtfs_url: String? = null,
    val attachment_id: Int? = null,
    val valid_from: String? = null,
    val valid_to: String? = null,
    val stop_count: Int? = null,
    val route_count: Int? = null,
    val sha256: String? = null,
)

data class Departure(
    val time: LocalTime,
    val line: String,
    val headsign: String,
    /** Minutes from now; negative values never surface because past rows are filtered out. */
    val inMinutes: Long,
    val afterMidnight: Boolean,
    val routeId: String,
)

data class TransitStatus(
    val importing: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val stats: ImportStats? = null,
    val ready: Boolean = false,
)

class TransitRepository(
    private val context: Context,
    private val db: TransitDb,
) : CachedRemoteSource<TransitMeta>(
    context,
    RemoteConfig.dataUrl("transit_meta.json"),
    "transit_meta.json",
) {
    override fun parse(body: String): TransitMeta = sharedJson.decodeFromString(body)

    private val dao get() = db.dao()

    private val _status = MutableStateFlow(TransitStatus())
    val status: StateFlow<TransitStatus> = _status.asStateFlow()

    suspend fun checkReady() {
        val ready = withContext(Dispatchers.IO) { dao.stopTimeCount() > 0 }
        _status.value = _status.value.copy(ready = ready)
    }

    /** Downloads and ingests the timetable. Safe to call again — it replaces everything. */
    suspend fun importTimetable(force: Boolean = false): Result<ImportStats> {
        if (!force && dao.stopTimeCount() > 0) {
            _status.value = _status.value.copy(ready = true)
            return Result.success(ImportStats(0, 0, 0, 0, 0))
        }
        _status.value = TransitStatus(importing = true, progress = "Przygotowanie…")

        val url = data.value?.gtfs_url ?: refresh().getOrNull()?.gtfs_url
        if (url == null) {
            val msg = "Nie znam adresu rozkładu. Sprawdź transit_meta.json w repo."
            _status.value = TransitStatus(importing = false, error = msg)
            return Result.failure(IllegalStateException(msg))
        }

        return GtfsImporter(dao).import(url) { step ->
            _status.value = _status.value.copy(progress = step)
        }.onSuccess { stats ->
            _status.value = TransitStatus(importing = false, stats = stats, ready = true)
        }.onFailure { e ->
            _status.value = TransitStatus(
                importing = false,
                error = friendlyNetworkError(e, "Nie udało się wczytać rozkładu"),
            )
        }
    }

    suspend fun searchStops(query: String): List<StopSuggestion> = withContext(Dispatchers.IO) {
        val raw = if (query.isBlank()) dao.allStops() else dao.searchStops(query.trim())
        // Physical stops are split into one row per platform; collapse them for the picker.
        raw.distinctBy { it.name }
    }

    suspend fun stopsNamed(name: String) = withContext(Dispatchers.IO) { dao.stopsNamed(name) }

    suspend fun routes() = withContext(Dispatchers.IO) { dao.allRoutes() }

    suspend fun routeStops(routeId: String) = withContext(Dispatchers.IO) { dao.routeStops(routeId) }

    /**
     * Next departures from every platform of a stop. Trips that run past midnight are stored
     * as >24h on the previous service day, so yesterday is queried too.
     */
    suspend fun nextDepartures(
        stopName: String,
        now: LocalDateTime = LocalDateTime.now(),
        limit: Int = 25,
    ): List<Departure> = withContext(Dispatchers.IO) {
        val ids = dao.stopsNamed(stopName).map { it.id }
        if (ids.isEmpty()) return@withContext emptyList()

        val today = now.toLocalDate()
        val secondsNow = now.toLocalTime().toSecondOfDay()

        val todayRows = dao.departures(ids, today.toString(), secondsNow, limit)
            .map { it to false }
        val yesterdayRows = dao
            .departures(ids, today.minusDays(1).toString(), secondsNow + DAY, limit)
            .map { it to true }

        (yesterdayRows + todayRows)
            .map { (row, fromYesterday) -> row.toDeparture(now, fromYesterday) }
            .sortedBy { it.inMinutes }
            .filter { it.inMinutes >= 0 }
            .take(limit)
    }

    /**
     * Point-to-point search. Trips running past midnight belong to the previous service day
     * and are stored as >24 h, so a search late in the evening has to look at yesterday's
     * calendar too, exactly like [nextDepartures].
     */
    suspend fun planJourneys(
        fromName: String,
        toName: String,
        now: LocalDateTime = LocalDateTime.now(),
        limit: Int = 6,
    ): List<Journey> = withContext(Dispatchers.IO) {
        val originIds = dao.stopIdsMatching(fromName.trim())
        val destinationIds = dao.stopIdsMatching(toName.trim())
        if (originIds.isEmpty() || destinationIds.isEmpty()) return@withContext emptyList()
        if (originIds.toSet() == destinationIds.toSet()) return@withContext emptyList()

        val today = now.toLocalDate()
        val seconds = now.toLocalTime().toSecondOfDay()

        val plans = listOf(today.toString() to seconds, today.minusDays(1).toString() to seconds + DAY)
            .flatMap { (date, from) -> planOnDay(originIds, destinationIds, date, from, limit) }

        plans.sortedWith(compareBy({ it.departure }, { it.arrival })).take(limit)
    }

    private suspend fun planOnDay(
        originIds: List<String>,
        destinationIds: List<String>,
        date: String,
        fromSeconds: Int,
        limit: Int,
    ): List<Journey> {
        val boardings = dao.boardings(
            stopIds = originIds,
            date = date,
            afterSeconds = fromSeconds,
            untilSeconds = fromSeconds + SEARCH_WINDOW,
        )
        if (boardings.isEmpty()) return emptyList()

        val boardable = dao.stopTimesOfTrips(boardings.map { it.tripId }.distinct()).toPlanTrips()
        val destinationTrips = dao.stopTimesOfTrips(dao.tripsCalling(destinationIds, date)).toPlanTrips()

        return JourneyPlanner.plan(
            boardable = boardable,
            originIds = originIds.toSet(),
            destinationIds = destinationIds.toSet(),
            toDestination = destinationTrips,
            earliest = fromSeconds,
            limit = limit,
        )
    }

    private fun List<TripStopRow>.toPlanTrips(): List<PlanTrip> = groupBy { it.tripId }
        .map { (tripId, rows) ->
            val ordered = rows.sortedBy { it.seq }
            PlanTrip(
                tripId = tripId,
                line = ordered.first().shortName,
                headsign = ordered.first().headsign,
                stops = ordered.map {
                    PlanStopTime(
                        stopId = it.stopId,
                        stopName = it.stopName,
                        time = it.departure,
                        seq = it.seq,
                    )
                },
            )
        }

    private fun DepartureRow.toDeparture(now: LocalDateTime, fromYesterday: Boolean): Departure {
        val base = if (fromYesterday) now.toLocalDate().minusDays(1) else now.toLocalDate()
        val at = base.atStartOfDay().plusSeconds(departure.toLong())
        return Departure(
            time = at.toLocalTime(),
            line = shortName,
            headsign = headsign,
            inMinutes = java.time.Duration.between(now, at).toMinutes(),
            afterMidnight = departure >= DAY,
            routeId = routeId,
        )
    }

    private companion object {
        const val DAY = 24 * 3600

        /** How far ahead to look for a first bus. Longer just means slower and noisier. */
        const val SEARCH_WINDOW = 3 * 3600
    }
}
