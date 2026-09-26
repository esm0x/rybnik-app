package com.adminstack.rybnik.data.transit

import java.time.LocalTime

/**
 * Point-to-point search over the imported timetable.
 *
 * One transfer is not a nice-to-have here, it is the whole point. KM Rybnik's network runs
 * through the centre: checked against the live feed, there is not a single direct trip from
 * Boguszowice Stare to Kamień, and the two districts only meet at five stops, all of them in
 * Śródmieście or at Północ Karolinka. A direct-only search would answer "no connection" to
 * exactly the question people ask.
 *
 * Two transfers are deliberately not supported. From Boguszowice Stare alone 104 stops are
 * reachable directly and the rest open up after one change, so a second change would mostly
 * add noise and search time.
 *
 * Times are seconds after midnight of the service day and may exceed 24 h, because trips that
 * run past midnight belong to the previous day's calendar. Arrival is read from the same
 * column as departure: in this feed `arrival_time` equals `departure_time` in all 71 116 rows,
 * so nothing is lost by storing one.
 */

/** One stop of one trip, as the planner needs it. */
data class PlanStopTime(
    val stopId: String,
    val stopName: String,
    val time: Int,
    val seq: Int,
)

/** A trip with its stops in order. */
data class PlanTrip(
    val tripId: String,
    val line: String,
    val headsign: String,
    val stops: List<PlanStopTime>,
)

data class JourneyLeg(
    val line: String,
    val headsign: String,
    val fromStop: String,
    val toStop: String,
    val departure: Int,
    val arrival: Int,
) {
    val departureTime: LocalTime get() = secondsToTime(departure)
    val arrivalTime: LocalTime get() = secondsToTime(arrival)
    val rideMinutes: Int get() = (arrival - departure) / 60
}

data class Journey(val legs: List<JourneyLeg>) {
    val departure: Int get() = legs.first().departure
    val arrival: Int get() = legs.last().arrival
    val departureTime: LocalTime get() = secondsToTime(departure)
    val arrivalTime: LocalTime get() = secondsToTime(arrival)
    val totalMinutes: Int get() = (arrival - departure) / 60
    val transferStop: String? get() = if (legs.size > 1) legs.first().toStop else null

    /** Minutes spent waiting at the transfer stop, null for a direct ride. */
    val transferWaitMinutes: Int?
        get() = if (legs.size > 1) (legs[1].departure - legs[0].arrival) / 60 else null
}

private fun secondsToTime(seconds: Int): LocalTime =
    LocalTime.ofSecondOfDay((seconds % 86_400).toLong())

object JourneyPlanner {

    /** Enough to walk between platforms of the same stop without sprinting. */
    const val MIN_TRANSFER_SECONDS = 180

    /** Beyond an hour of waiting it is not a connection, it is two separate trips. */
    const val MAX_WAIT_SECONDS = 3600

    /**
     * @param boardable trips that call at the origin, already limited to the service day
     * @param toDestination trips that call at the destination on the same service day
     */
    fun plan(
        boardable: List<PlanTrip>,
        originIds: Set<String>,
        destinationIds: Set<String>,
        toDestination: List<PlanTrip>,
        earliest: Int,
        limit: Int = 6,
    ): List<Journey> {
        if (originIds.isEmpty() || destinationIds.isEmpty()) return emptyList()

        val direct = mutableListOf<Journey>()
        val viaTransfer = mutableListOf<Journey>()

        // Where can a second leg be caught, and when does it get to the destination?
        val secondLegs = indexSecondLegs(toDestination, destinationIds)

        for (trip in boardable) {
            val board = trip.stops.firstOrNull {
                it.stopId in originIds && it.time >= earliest
            } ?: continue
            val onward = trip.stops.filter { it.seq > board.seq }

            val alight = onward.firstOrNull { it.stopId in destinationIds }
            if (alight != null) {
                direct += Journey(listOf(trip.leg(board, alight)))
                continue
            }

            // No through service: try to change onto something that does finish the job.
            var best: Journey? = null
            for (change in onward) {
                if (change.stopId in originIds) continue
                val options = secondLegs[change.stopName] ?: continue
                val candidate = options.firstOrNull {
                    it.departure >= change.time + MIN_TRANSFER_SECONDS &&
                        it.departure <= change.time + MAX_WAIT_SECONDS
                } ?: continue
                val journey = Journey(
                    listOf(
                        trip.leg(board, change),
                        JourneyLeg(
                            line = candidate.line,
                            headsign = candidate.headsign,
                            fromStop = change.stopName,
                            toStop = candidate.arrivalStop,
                            departure = candidate.departure,
                            arrival = candidate.arrival,
                        ),
                    )
                )
                if (best == null || journey.arrival < best.arrival) best = journey
            }
            best?.let { viaTransfer += it }
        }

        // A change that lands no earlier than simply staying on a direct bus is just worse.
        val bestDirect = direct.minOfOrNull { it.arrival }
        val useful = viaTransfer.filter { bestDirect == null || it.arrival < bestDirect }

        return dropDominated(direct + useful)
            .distinctBy { listOf(it.departure, it.arrival, it.legs.first().line) }
            .take(limit)
    }

    /**
     * Leaving earlier only counts if it also gets you there earlier.
     *
     * Everything from Boguszowice Stare feeds the same bus out of the centre, so a raw list
     * offered four ways to arrive at 10:02, the earliest of them leaving 08:26. Anything a
     * later departure matches or beats on arrival is dropped.
     */
    private fun dropDominated(journeys: List<Journey>): List<Journey> {
        val byDeparture = journeys.sortedWith(compareBy({ it.departure }, { it.arrival }))
        val kept = ArrayDeque<Journey>()
        var bestArrival = Int.MAX_VALUE
        for (journey in byDeparture.asReversed()) {
            if (journey.arrival < bestArrival) {
                kept.addFirst(journey)
                bestArrival = journey.arrival
            }
        }
        return kept.toList()
    }

    private fun PlanTrip.leg(from: PlanStopTime, to: PlanStopTime) = JourneyLeg(
        line = line,
        headsign = headsign,
        fromStop = from.stopName,
        toStop = to.stopName,
        departure = from.time,
        arrival = to.time,
    )

    private data class SecondLeg(
        val departure: Int,
        val arrival: Int,
        val line: String,
        val headsign: String,
        val arrivalStop: String,
    )

    /**
     * For every stop a destination-bound trip passes, when it leaves there and when it gets
     * to the destination. Sorted by departure so the first match is the earliest one.
     */
    private fun indexSecondLegs(
        trips: List<PlanTrip>,
        destinationIds: Set<String>,
    ): Map<String, List<SecondLeg>> {
        val index = mutableMapOf<String, MutableList<SecondLeg>>()
        for (trip in trips) {
            val arrival = trip.stops.firstOrNull { it.stopId in destinationIds } ?: continue
            for (stop in trip.stops) {
                if (stop.seq >= arrival.seq) break
                index.getOrPut(stop.stopName) { mutableListOf() } += SecondLeg(
                    departure = stop.time,
                    arrival = arrival.time,
                    line = trip.line,
                    headsign = trip.headsign,
                    arrivalStop = arrival.stopName,
                )
            }
        }
        return index.mapValues { (_, legs) -> legs.sortedBy { it.departure } }
    }
}
