package com.adminstack.rybnik.data.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of these fixtures comes from the live KM Rybnik feed: Boguszowice Stare and
 * Kamień have no trip in common, and every connection between them runs through the centre.
 */
class JourneyPlannerTest {

    private val boguszowice = "Rybnik Boguszowice Stare Błękitna"
    private val bazylika = "Rybnik Śródmieście Bazylika"
    private val kamien = "Rybnik Kamień Las"

    private fun h(hhmm: String): Int {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        return h * 3600 + m * 60
    }

    private fun trip(id: String, line: String, vararg stops: Triple<String, String, String>) =
        PlanTrip(
            tripId = id,
            line = line,
            headsign = "kierunek",
            stops = stops.mapIndexed { i, (stopId, name, time) ->
                PlanStopTime(stopId = stopId, stopName = name, time = h(time), seq = i + 1)
            },
        )

    private val line48 = trip(
        "t48", "48",
        Triple("bog1", boguszowice, "08:06"),
        Triple("baz1", bazylika, "08:30"),
    )
    private val line18 = trip(
        "t18", "18",
        Triple("baz2", bazylika, "08:36"),
        Triple("kam1", kamien, "08:52"),
    )

    @Test
    fun `changes buses when no through service exists`() {
        val found = JourneyPlanner.plan(
            boardable = listOf(line48),
            originIds = setOf("bog1"),
            destinationIds = setOf("kam1"),
            toDestination = listOf(line18),
            earliest = h("08:00"),
        )

        assertEquals(1, found.size)
        val journey = found.single()
        assertEquals(listOf("48", "18"), journey.legs.map { it.line })
        assertEquals(bazylika, journey.transferStop)
        assertEquals(h("08:06"), journey.departure)
        assertEquals(h("08:52"), journey.arrival)
        assertEquals(46, journey.totalMinutes)
        assertEquals(6, journey.transferWaitMinutes)
    }

    @Test
    fun `a through service is reported as one leg`() {
        val through = trip(
            "t9", "9",
            Triple("bog1", boguszowice, "09:00"),
            Triple("kam1", kamien, "09:30"),
        )
        val journey = JourneyPlanner.plan(
            boardable = listOf(through),
            originIds = setOf("bog1"),
            destinationIds = setOf("kam1"),
            toDestination = listOf(through),
            earliest = h("08:00"),
        ).single()

        assertEquals(1, journey.legs.size)
        assertNull(journey.transferStop)
        assertNull(journey.transferWaitMinutes)
    }

    /** Two minutes between buses is not a connection anyone can make. */
    @Test
    fun `a transfer shorter than the minimum is not offered`() {
        val tight = trip(
            "t18b", "18",
            Triple("baz2", bazylika, "08:32"),
            Triple("kam1", kamien, "08:50"),
        )
        val found = JourneyPlanner.plan(
            boardable = listOf(line48),
            originIds = setOf("bog1"),
            destinationIds = setOf("kam1"),
            toDestination = listOf(tight),
            earliest = h("08:00"),
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `waiting more than an hour is not a connection`() {
        val late = trip(
            "t18c", "18",
            Triple("baz2", bazylika, "09:45"),
            Triple("kam1", kamien, "10:02"),
        )
        val found = JourneyPlanner.plan(
            boardable = listOf(line48),
            originIds = setOf("bog1"),
            destinationIds = setOf("kam1"),
            toDestination = listOf(late),
            earliest = h("08:00"),
        )
        assertTrue(found.isEmpty())
    }

    /**
     * Four buses out of Boguszowice all feed the same departure from the centre, so only
     * the last one worth taking should survive.
     */
    @Test
    fun `an earlier departure arriving no sooner is dropped`() {
        val early = trip(
            "t46", "46",
            Triple("bog1", boguszowice, "08:26"),
            Triple("baz1", bazylika, "09:00"),
        )
        val later = trip(
            "t45", "45",
            Triple("bog1", boguszowice, "09:25"),
            Triple("baz1", bazylika, "09:40"),
        )
        val shared = trip(
            "t18d", "18",
            Triple("baz2", bazylika, "09:46"),
            Triple("kam1", kamien, "10:02"),
        )

        val found = JourneyPlanner.plan(
            boardable = listOf(early, later),
            originIds = setOf("bog1"),
            destinationIds = setOf("kam1"),
            toDestination = listOf(shared),
            earliest = h("08:00"),
        )

        assertEquals(1, found.size)
        assertEquals("45", found.single().legs.first().line)
    }

    @Test
    fun `a transfer that arrives later than a direct bus is not offered`() {
        val direct = trip(
            "t9", "9",
            Triple("bog1", boguszowice, "08:10"),
            Triple("kam1", kamien, "08:40"),
        )
        val found = JourneyPlanner.plan(
            boardable = listOf(direct, line48),
            originIds = setOf("bog1"),
            destinationIds = setOf("kam1"),
            toDestination = listOf(direct, line18),
            earliest = h("08:00"),
        )
        assertTrue(found.all { it.legs.size == 1 })
    }

    @Test
    fun `boarding never counts a stop the bus already passed`() {
        val passing = trip(
            "t1", "1",
            Triple("kam1", kamien, "07:00"),
            Triple("bog1", boguszowice, "07:30"),
        )
        val found = JourneyPlanner.plan(
            boardable = listOf(passing),
            originIds = setOf("bog1"),
            destinationIds = setOf("kam1"),
            toDestination = listOf(passing),
            earliest = h("07:00"),
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `an empty timetable yields nothing rather than throwing`() {
        assertTrue(
            JourneyPlanner.plan(
                boardable = emptyList(),
                originIds = setOf("bog1"),
                destinationIds = setOf("kam1"),
                toDestination = emptyList(),
                earliest = 0,
            ).isEmpty()
        )
    }
}
