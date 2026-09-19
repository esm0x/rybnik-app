package eu.rybnik.events.data.transit

import android.util.Log
import eu.rybnik.events.core.net.sharedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.BufferedReader
import java.util.zip.ZipInputStream

/**
 * Downloads the KM Rybnik GTFS zip and loads it into Room.
 *
 * Feed quirks this deliberately works around:
 *  - `calendar.txt` has every weekday flag set to 0, so service comes entirely from
 *    `calendar_dates.txt` (exception_type=1). Reading calendar.txt yields no departures.
 *  - `shapes.txt` is header-only, so there is no route geometry to import.
 */
class GtfsImporter(private val dao: TransitDao) {

    // Downloading and CSV-parsing both block, and this is called from viewModelScope,
    // so without an explicit IO dispatcher it lands on the main thread.
    suspend fun import(url: String, onProgress: (String) -> Unit = {}): Result<ImportStats> =
        withContext(Dispatchers.IO) {
            runCatching {
                onProgress("Pobieranie rozkładu…")
                val files = download(url)

                onProgress("Wczytywanie przystanków…")
                val stops = parse(files["stops.txt"]) { row ->
                    StopEntity(
                        id = row["stop_id"] ?: return@parse null,
                        name = row["stop_name"].orEmpty(),
                        lat = row["stop_lat"]?.toDoubleOrNull() ?: 0.0,
                        lon = row["stop_lon"]?.toDoubleOrNull() ?: 0.0,
                    )
                }

                val routes = parse(files["routes.txt"]) { row ->
                    val id = row["route_id"] ?: return@parse null
                    val short = row["route_short_name"].orEmpty().trim()
                    // The "-->" route is not a passenger line: its 87 trips are depot runs
                    // and shift changes ("Wyjazd na linię", "JADA NA SZYCHTA"), yet they do
                    // call at real stops, so leaving it in puts phantom empty buses in the
                    // departure board. Dropping the route also drops them, because
                    // departures inner-join on routes.
                    // Note "A" is NOT this case — it is a genuine line (54 trips through
                    // Zamysłów and Smolna) that merely has no long name.
                    if (short == DEADHEAD_ROUTE) return@parse null
                    RouteEntity(
                        id = id,
                        shortName = short.ifEmpty {
                            row["route_long_name"].orEmpty().take(6).ifEmpty { id }
                        },
                        longName = row["route_long_name"].orEmpty(),
                    )
                }

                onProgress("Wczytywanie kursów…")
                val trips = parse(files["trips.txt"]) { row ->
                    TripEntity(
                        id = row["trip_id"] ?: return@parse null,
                        routeId = row["route_id"].orEmpty(),
                        serviceId = row["service_id"].orEmpty(),
                        headsign = row["trip_headsign"].orEmpty(),
                    )
                }

                onProgress("Wczytywanie odjazdów…")
                val stopTimes = parse(files["stop_times.txt"]) { row ->
                    val dep = parseGtfsTime(row["departure_time"] ?: row["arrival_time"])
                        ?: return@parse null
                    StopTimeEntity(
                        tripId = row["trip_id"] ?: return@parse null,
                        stopId = row["stop_id"] ?: return@parse null,
                        departure = dep,
                        seq = row["stop_sequence"]?.toIntOrNull() ?: 0,
                    )
                }

                val serviceDates = parse(files["calendar_dates.txt"]) { row ->
                    if (row["exception_type"] != "1") return@parse null
                    val raw = row["date"] ?: return@parse null
                    if (raw.length != 8) return@parse null
                    ServiceDateEntity(
                        serviceId = row["service_id"].orEmpty(),
                        date = "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}",
                    )
                }

                check(stopTimes.isNotEmpty()) { "Brak odjazdów w pliku GTFS" }
                check(serviceDates.isNotEmpty()) { "Brak kalendarza kursowania (calendar_dates.txt)" }

                onProgress("Zapisywanie…")
                dao.clearAll()
                dao.insertStops(stops)
                dao.insertRoutes(routes)
                dao.insertTrips(trips)
                serviceDates.chunked(CHUNK).forEach { dao.insertServiceDates(it) }
                stopTimes.chunked(CHUNK).forEach { dao.insertStopTimes(it) }

                ImportStats(stops.size, routes.size, trips.size, stopTimes.size, serviceDates.size)
            }.onFailure { Log.w(TAG, "GTFS import failed", it) }
        }

    private fun download(url: String): Map<String, String> {
        val wanted = setOf(
            "stops.txt", "routes.txt", "trips.txt", "stop_times.txt", "calendar_dates.txt"
        )
        val out = mutableMapOf<String, String>()
        sharedHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} przy pobieraniu GTFS")
            val body = resp.body ?: error("Pusta odpowiedź")
            ZipInputStream(body.byteStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (name in wanted) out[name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val missing = wanted - out.keys
        check(missing.isEmpty()) { "Plik GTFS niekompletny, brakuje: ${missing.joinToString()}" }
        return out
    }

    private fun <T> parse(content: String?, map: (Map<String, String>) -> T?): List<T> {
        if (content.isNullOrBlank()) return emptyList()
        val reader = BufferedReader(content.reader())
        val header = reader.readLine()?.let { splitCsv(it.removePrefix("\uFEFF")) } ?: return emptyList()
        val out = ArrayList<T>()
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val cells = splitCsv(line)
            val row = HashMap<String, String>(header.size)
            header.forEachIndexed { i, key -> row[key] = cells.getOrNull(i).orEmpty() }
            map(row)?.let(out::add)
        }
        return out
    }

    companion object {
        private const val TAG = "GtfsImporter"
        private const val CHUNK = 5_000

        /** Short name of the depot/shift-change pseudo-line in the KM Rybnik feed. */
        private const val DEADHEAD_ROUTE = "-->"

        /** GTFS allows 25:30:00 to express "01:30 the next service day". */
        fun parseGtfsTime(raw: String?): Int? {
            val parts = raw?.trim()?.split(':') ?: return null
            if (parts.size < 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return h * 3600 + m * 60 + s
        }

        fun splitCsv(line: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                        sb.append('"'); i++
                    }
                    c == '"' -> inQuotes = !inQuotes
                    c == ',' && !inQuotes -> { out.add(sb.toString().trim()); sb.setLength(0) }
                    else -> sb.append(c)
                }
                i++
            }
            out.add(sb.toString().trim())
            return out
        }
    }
}

data class ImportStats(
    val stops: Int,
    val routes: Int,
    val trips: Int,
    val stopTimes: Int,
    val serviceDates: Int,
)
