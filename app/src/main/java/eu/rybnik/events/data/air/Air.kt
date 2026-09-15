package eu.rybnik.events.data.air

import android.util.Log
import eu.rybnik.events.core.net.sharedHttp
import eu.rybnik.events.core.net.sharedJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Air quality for Rybnik from GIOŚ (state environmental monitoring). No API key needed.
 *
 * Two traps worth remembering: sending `Accept: application/json` makes the API answer
 * 406, and the JSON keys are Polish prose, so they are matched as literal strings here.
 */
class AirQualityRepository {

    private val _state = MutableStateFlow(AirState())
    val state: StateFlow<AirState> = _state.asStateFlow()

    suspend fun refresh(): Result<AirState> = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            val sensors = fetchSensors()
            val readings = coroutineScope {
                TRACKED.mapNotNull { code ->
                    val ids = sensors[code]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    async { code to firstAvailable(ids) }
                }.map { it.await() }
            }.mapNotNull { (code, r) -> r?.let { Reading(code, it.first, it.second) } }

            val index = runCatching { fetchIndex() }.getOrNull()
            AirState(
                loading = false,
                readings = readings,
                indexLabel = index,
                updatedAt = readings.maxOfOrNull { it.at },
            ).also { _state.value = it }
        }.onFailure { e ->
            Log.w(TAG, "air refresh failed", e)
            _state.value = _state.value.copy(loading = false, error = e.message ?: "Błąd pobierania")
        }
    }

    private fun get(url: String): String =
        sharedHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body?.string()
            // Include what the server actually said — GIOŚ answers 4xx with a JSON
            // explanation, and a bare status code is useless when diagnosing it.
            if (!r.isSuccessful) error("HTTP ${r.code} dla $url :: ${body?.take(300)}")
            body ?: error("Pusta odpowiedź")
        }

    /**
     * A pollutant can have several measuring positions at one station — Rybnik-Borki has
     * two for PM10, one automatic and one manual. Manual ones answer 400 for live data
     * (results are published weeks later via the archive API), and nothing in this
     * response says which is which, so keep every id and let the caller try them in turn.
     */
    private fun fetchSensors(): Map<String, List<Int>> {
        val root = sharedJson.parseToJsonElement(get("$BASE/station/sensors/$STATION_ID")).jsonObject
        val list = root["Lista stanowisk pomiarowych dla podanej stacji"]?.jsonArray
            ?: return emptyMap()
        return list.mapNotNull { el ->
            val o = el.jsonObject
            val code = o["Wskaźnik - kod"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = o["Identyfikator stanowiska"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            code to id
        }.groupBy({ it.first }, { it.second })
    }

    private fun firstAvailable(ids: List<Int>): Pair<LocalDateTime, Double>? =
        ids.firstNotNullOfOrNull { id -> runCatching { fetchLatest(id) }.getOrNull() }

    /** Readings arrive newest-first, but recent hours are often null until validated. */
    private fun fetchLatest(sensorId: Int): Pair<LocalDateTime, Double>? {
        val root = sharedJson.parseToJsonElement(get("$BASE/data/getData/$sensorId")).jsonObject
        val list = root["Lista danych pomiarowych"]?.jsonArray ?: return null
        for (el in list) {
            val o: JsonObject = el.jsonObject
            val value = o["Wartość"]?.jsonPrimitive?.doubleOrNull ?: continue
            val raw = o["Data"]?.jsonPrimitive?.contentOrNull ?: continue
            val at = runCatching { LocalDateTime.parse(raw, FMT) }.getOrNull() ?: continue
            return at to value
        }
        return null
    }

    private fun fetchIndex(): String? {
        val root = sharedJson.parseToJsonElement(get("$BASE/aqindex/getIndex/$STATION_ID")).jsonObject
        return root["AqIndex"]?.jsonObject?.get("Nazwa kategorii indeksu")?.jsonPrimitive?.contentOrNull
    }

    companion object {
        private const val TAG = "AirQuality"
        private const val BASE = "https://api.gios.gov.pl/pjp-api/v1/rest"

        /** Rybnik, ul. Borki — the only in-city GIOŚ station. */
        const val STATION_ID = 834
        const val STATION_NAME = "Rybnik, ul. Borki"

        // Benzo(a)piren is deliberately absent: it is only ever measured manually
        // (lab analysis of filters), so live values do not exist — GIOŚ publishes it
        // weeks later through the archive API. Requesting it live only returns 400.
        private val TRACKED = listOf("PM10", "PM2.5")
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** Poziom informowania / poziom alarmowy for PM10, per the 2019 regulation. */
        const val PM10_INFO = 100.0
        const val PM10_ALARM = 150.0
        const val PM10_LIMIT = 50.0
    }
}

data class Reading(val code: String, val at: LocalDateTime, val value: Double) {
    val label: String
        get() = when (code) {
            "PM10" -> "PM10"
            "PM2.5" -> "PM2,5"
            "BaP(PM10)" -> "Benzo(a)piren"
            else -> code
        }

    val unit: String get() = if (code.startsWith("BaP")) "ng/m³" else "µg/m³"
}

data class AirState(
    val loading: Boolean = false,
    val error: String? = null,
    val readings: List<Reading> = emptyList(),
    val indexLabel: String? = null,
    val updatedAt: LocalDateTime? = null,
) {
    val pm10: Reading? get() = readings.firstOrNull { it.code == "PM10" }

    val severity: AirSeverity
        get() {
            val v = pm10?.value ?: return AirSeverity.UNKNOWN
            return when {
                v >= AirQualityRepository.PM10_ALARM -> AirSeverity.ALARM
                v >= AirQualityRepository.PM10_INFO -> AirSeverity.INFO
                v >= AirQualityRepository.PM10_LIMIT -> AirSeverity.ELEVATED
                else -> AirSeverity.OK
            }
        }
}

enum class AirSeverity(val label: String, val color: Long) {
    OK("Dobre", 0xFF10B981),
    ELEVATED("Podwyższone", 0xFFF59E0B),
    INFO("Poziom informowania", 0xFFF97316),
    ALARM("Poziom alarmowy", 0xFFDC2626),
    UNKNOWN("Brak danych", 0xFF6B7280),
}
