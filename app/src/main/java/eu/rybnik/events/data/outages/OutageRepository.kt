package eu.rybnik.events.data.outages

import android.util.Log
import eu.rybnik.events.core.net.sharedHttp
import eu.rybnik.events.core.net.sharedJson
import eu.rybnik.events.core.prefs.WasteAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

enum class OutageKind { PLANNED, UNPLANNED }

data class Outage(
    val id: String,
    val kind: OutageKind,
    val from: LocalDateTime,
    val to: LocalDateTime?,
    val message: String,
    val match: Match,
)

data class OutageState(
    val loading: Boolean = false,
    val error: String? = null,
    val outages: List<Outage> = emptyList(),
    val checkedAddress: String? = null,
)

/**
 * Planned and unplanned power cuts from Tauron's public (undocumented) API.
 *
 * Two things this has to work around. There are five places called Rybnik in Poland, so
 * the city id is pinned rather than looked up by name — the first hit is a village in
 * Łódzkie. And the outage endpoint accepts a house number but ignores it, returning
 * everything in the distribution region, so [OutageAddress] does the real filtering.
 */
class OutageRepository {

    private val _state = MutableStateFlow(OutageState())
    val state: StateFlow<OutageState> = _state.asStateFlow()

    suspend fun refresh(address: WasteAddress?): Result<List<Outage>> = withContext(Dispatchers.IO) {
        if (address == null) {
            _state.value = OutageState()
            return@withContext Result.success(emptyList())
        }
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            val streetGaid = findStreetGaid(address.street)
                ?: error("Nie znalazłem ulicy ${address.street} w bazie Tauronu")

            val today = java.time.LocalDate.now()
            val url = "$BASE/outages/address?cityGAID=$CITY_GAID&streetGAID=$streetGaid" +
                "&houseNo=${address.houseNumber}" +
                "&fromDate=$today&toDate=${today.plusDays(WINDOW_DAYS)}"

            val root = sharedJson.parseToJsonElement(get(url)).jsonObject
            val items = root["OutageItems"]?.jsonArray ?: return@runCatching emptyList()

            val parsed = items.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["OutageId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val message = o["Message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val kind = if (o["TypeId"]?.jsonPrimitive?.intOrNull == 2) {
                    OutageKind.UNPLANNED
                } else OutageKind.PLANNED

                val match = OutageAddress.match(message, address.street, address.houseNumber)
                if (match == Match.NONE) return@mapNotNull null

                Outage(
                    id = id,
                    kind = kind,
                    from = utcToLocal(o["StartDate"]?.jsonPrimitive?.contentOrNull)
                        ?: return@mapNotNull null,
                    to = utcToLocal(o["EndDate"]?.jsonPrimitive?.contentOrNull),
                    message = message.replace('\n', ' ').trim(),
                    match = match,
                )
            }.sortedBy { it.from }

            _state.value = OutageState(outages = parsed, checkedAddress = address.pretty)
            parsed
        }.onFailure { e ->
            Log.w(TAG, "outage refresh failed", e)
            _state.value = _state.value.copy(
                loading = false,
                error = e.message ?: "Nie udało się sprawdzić wyłączeń",
            )
        }
    }

    /** Street ids are per-city; querying with the city's own OwnerGAID returns nothing. */
    private fun findStreetGaid(street: String): Int? {
        val probe = street.split(' ', '.')
            .map { it.trim() }
            .filter { it.length >= 4 }
            .maxByOrNull { it.length }
            ?: street
        val body = get("$BASE/enum/geo/streets?partName=${probe.take(12)}&ownerGAID=$CITY_GAID")
        val list = sharedJson.parseToJsonElement(body).jsonArray
        if (list.isEmpty()) return null

        val wanted = simplify(street)
        val exact = list.firstOrNull {
            simplify(it.jsonObject["Name"]?.jsonPrimitive?.contentOrNull.orEmpty()) == wanted
        }
        return (exact ?: list.first()).jsonObject["GAID"]?.jsonPrimitive?.intOrNull
    }

    private fun simplify(s: String) = s.lowercase()
        .replace("ą", "a").replace("ć", "c").replace("ę", "e").replace("ł", "l")
        .replace("ń", "n").replace("ó", "o").replace("ś", "s")
        .replace("ź", "z").replace("ż", "z")
        .filter { it.isLetterOrDigit() }

    private fun utcToLocal(raw: String?): LocalDateTime? = raw?.let {
        runCatching {
            LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault())
        }.getOrNull()
    }

    private fun get(url: String): String =
        sharedHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body?.string() ?: error("Pusta odpowiedź")
        }

    private companion object {
        const val TAG = "Outages"
        const val BASE = "https://www.tauron-dystrybucja.pl/waapi"

        /** Rybnik, Śląskie. Pinned: "Rybnik" also names villages in Łódzkie. */
        const val CITY_GAID = 13
        const val WINDOW_DAYS = 30L
    }
}
