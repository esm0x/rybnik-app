package com.adminstack.rybnik.data.transit

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "stops")
data class StopEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
)

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val id: String,
    val shortName: String,
    val longName: String,
)

@Entity(tableName = "trips", indices = [Index("routeId"), Index("serviceId")])
data class TripEntity(
    @PrimaryKey val id: String,
    val routeId: String,
    val serviceId: String,
    val headsign: String,
)

/** [departure] is seconds after midnight and may exceed 86400 for post-midnight trips. */
@Entity(
    tableName = "stop_times",
    indices = [Index("stopId", "departure"), Index("tripId")],
)
data class StopTimeEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val tripId: String,
    val stopId: String,
    val departure: Int,
    val seq: Int,
)

/** Service calendar. This feed drives everything from calendar_dates, not calendar.txt. */
@Entity(tableName = "service_dates", indices = [Index("date")])
data class ServiceDateEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val serviceId: String,
    @ColumnInfo(name = "date") val date: String,
)

data class DepartureRow(
    val departure: Int,
    val shortName: String,
    val headsign: String,
    val tripId: String,
    val routeId: String,
)

data class StopSuggestion(val id: String, val name: String, val lat: Double, val lon: Double)

/** A trip that can be boarded at a given platform, and where in the trip that happens. */
data class BoardingRow(
    val tripId: String,
    val stopId: String,
    val departure: Int,
    val seq: Int,
    val shortName: String,
    val headsign: String,
)

data class TripStopRow(
    val tripId: String,
    val stopId: String,
    val stopName: String,
    val departure: Int,
    val seq: Int,
    val shortName: String,
    val headsign: String,
)

@Dao
interface TransitDao {

    @Insert suspend fun insertStops(rows: List<StopEntity>)
    @Insert suspend fun insertRoutes(rows: List<RouteEntity>)
    @Insert suspend fun insertTrips(rows: List<TripEntity>)
    @Insert suspend fun insertStopTimes(rows: List<StopTimeEntity>)
    @Insert suspend fun insertServiceDates(rows: List<ServiceDateEntity>)

    @Query("DELETE FROM stops") suspend fun clearStops()
    @Query("DELETE FROM routes") suspend fun clearRoutes()
    @Query("DELETE FROM trips") suspend fun clearTrips()
    @Query("DELETE FROM stop_times") suspend fun clearStopTimes()
    @Query("DELETE FROM service_dates") suspend fun clearServiceDates()

    @Transaction
    suspend fun clearAll() {
        clearStopTimes(); clearTrips(); clearServiceDates(); clearRoutes(); clearStops()
    }

    @Query("SELECT COUNT(*) FROM stops") suspend fun stopCount(): Int
    @Query("SELECT COUNT(*) FROM stop_times") suspend fun stopTimeCount(): Int

    @Query(
        """
        SELECT id, name, lat, lon FROM stops
        WHERE name LIKE :q || '%' OR name LIKE '% ' || :q || '%'
        ORDER BY name LIMIT 40
        """
    )
    suspend fun searchStops(q: String): List<StopSuggestion>

    @Query("SELECT id, name, lat, lon FROM stops ORDER BY name LIMIT 60")
    suspend fun allStops(): List<StopSuggestion>

    @Query("SELECT id, name, lat, lon FROM stops WHERE id IN (:ids)")
    suspend fun stopsByIds(ids: List<String>): List<StopSuggestion>

    /**
     * Every platform whose name contains the text. People ask for connections between
     * districts, not between platforms: "Boguszowice Stare" is 23 stops and "Kamień" is 14,
     * so matching loosely here is what makes the search answer the question actually asked.
     */
    @Query("SELECT id FROM stops WHERE name LIKE '%' || :q || '%'")
    suspend fun stopIdsMatching(q: String): List<String>

    /** Sibling platforms of the same physical stop live under separate ids. */
    @Query("SELECT id, name, lat, lon FROM stops WHERE name = :name")
    suspend fun stopsNamed(name: String): List<StopSuggestion>

    @Query(
        """
        SELECT st.departure AS departure, r.shortName AS shortName,
               t.headsign AS headsign, t.id AS tripId, r.id AS routeId
        FROM stop_times st
        JOIN trips t ON st.tripId = t.id
        JOIN routes r ON t.routeId = r.id
        JOIN service_dates sd ON sd.serviceId = t.serviceId
        WHERE st.stopId IN (:stopIds) AND sd.date = :date AND st.departure >= :afterSeconds
        ORDER BY st.departure LIMIT :limit
        """
    )
    suspend fun departures(
        stopIds: List<String>,
        date: String,
        afterSeconds: Int,
        limit: Int = 40,
    ): List<DepartureRow>

    /**
     * Trips leaving the given platforms inside a time window. The window keeps the journey
     * search bounded: without it every trip of the day would be a candidate.
     */
    @Query(
        """
        SELECT st.tripId AS tripId, st.stopId AS stopId, st.departure AS departure,
               st.seq AS seq, r.shortName AS shortName, t.headsign AS headsign
        FROM stop_times st
        JOIN trips t ON st.tripId = t.id
        JOIN routes r ON t.routeId = r.id
        JOIN service_dates sd ON sd.serviceId = t.serviceId
        WHERE st.stopId IN (:stopIds) AND sd.date = :date
          AND st.departure >= :afterSeconds AND st.departure <= :untilSeconds
        ORDER BY st.departure LIMIT :limit
        """
    )
    suspend fun boardings(
        stopIds: List<String>,
        date: String,
        afterSeconds: Int,
        untilSeconds: Int,
        limit: Int = 120,
    ): List<BoardingRow>

    /** Ids of trips that call at the destination on a given service day. */
    @Query(
        """
        SELECT DISTINCT st.tripId FROM stop_times st
        JOIN trips t ON st.tripId = t.id
        JOIN service_dates sd ON sd.serviceId = t.serviceId
        WHERE st.stopId IN (:stopIds) AND sd.date = :date
        """
    )
    suspend fun tripsCalling(stopIds: List<String>, date: String): List<String>

    /** Every stop of the given trips, in order, with the line they belong to. */
    @Query(
        """
        SELECT st.tripId AS tripId, st.stopId AS stopId, s.name AS stopName,
               st.departure AS departure, st.seq AS seq,
               r.shortName AS shortName, t.headsign AS headsign
        FROM stop_times st
        JOIN stops s ON s.id = st.stopId
        JOIN trips t ON st.tripId = t.id
        JOIN routes r ON t.routeId = r.id
        WHERE st.tripId IN (:tripIds)
        ORDER BY st.tripId, st.seq
        """
    )
    suspend fun stopTimesOfTrips(tripIds: List<String>): List<TripStopRow>

    @Query("SELECT id, shortName, longName FROM routes ORDER BY CAST(shortName AS INTEGER), shortName")
    suspend fun allRoutes(): List<RouteEntity>

    /** Stop sequence of the busiest trip on a route — a reasonable stand-in for "the route". */
    @Query(
        """
        SELECT s.id AS id, s.name AS name, s.lat AS lat, s.lon AS lon
        FROM stop_times st
        JOIN stops s ON s.id = st.stopId
        WHERE st.tripId = (
            SELECT t.id FROM trips t
            WHERE t.routeId = :routeId
            ORDER BY (SELECT COUNT(*) FROM stop_times x WHERE x.tripId = t.id) DESC
            LIMIT 1
        )
        ORDER BY st.seq
        """
    )
    suspend fun routeStops(routeId: String): List<StopSuggestion>
}

@Database(
    entities = [
        StopEntity::class, RouteEntity::class, TripEntity::class,
        StopTimeEntity::class, ServiceDateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TransitDb : RoomDatabase() {
    abstract fun dao(): TransitDao
}
