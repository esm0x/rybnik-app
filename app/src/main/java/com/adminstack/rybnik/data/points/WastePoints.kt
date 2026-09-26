package com.adminstack.rybnik.data.points

import android.content.Context
import com.adminstack.rybnik.core.net.CachedRemoteSource
import com.adminstack.rybnik.core.net.RemoteConfig
import com.adminstack.rybnik.core.net.sharedJson
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

@Serializable
data class WastePointsPayload(
    val generated_at: String? = null,
    val points: List<WastePointDto> = emptyList(),
    val guide: List<GuideEntryDto> = emptyList(),
)

@Serializable
data class WastePointDto(
    val id: String,
    val name: String,
    val kind: String,
    val address: String,
    val district: String? = null,
    val phone: String? = null,
    val email: String? = null,
    /** Raw line from the city page; it is not parsed into times on purpose. */
    val hours: String = "",
    val accepted: List<String> = emptyList(),
    val note: String? = null,
    val url: String = "",
)

@Serializable
data class GuideEntryDto(
    val item: String,
    val where: String,
    val note: String? = null,
    val keywords: List<String> = emptyList(),
)

/** Where a thing goes. The first seven mirror the bin colours the schedule already uses. */
enum class Destination(val label: String, val hint: String) {
    ZMIESZANE("Zmieszane", "czarny pojemnik"),
    BIO("Bio", "brązowy pojemnik"),
    PAPIER("Papier", "niebieski pojemnik"),
    SZKLO("Szkło", "zielony pojemnik"),
    PLASTIK("Plastik i metal", "żółty pojemnik"),
    POPIOLY("Popiół", "szary pojemnik, tylko wystudzony"),
    GABARYTY("Gabaryty", "odbiór wielkogabarytowy albo PSZOK"),
    PSZOK("PSZOK", "punkt zbiórki, dowozisz sam"),
    GPZON("GPZON", "odpady niebezpieczne, ul. Jankowicka 41B"),
    INNE("Inne", "sprawdź na rybnik.eu"),
}

data class GuideEntry(
    val item: String,
    val destination: Destination,
    val note: String?,
    private val keywords: List<String>,
) {
    private val haystack: String = fold(
        (listOf(item) + keywords + listOfNotNull(note)).joinToString(" ")
    )

    fun matches(query: String): Boolean = haystack.contains(fold(query))
}

data class WastePoint(
    val id: String,
    val name: String,
    val kind: String,
    val address: String,
    val district: String?,
    val phone: String?,
    val email: String?,
    val hours: String,
    val accepted: List<String>,
    val note: String?,
    val url: String,
)

/**
 * Lowercase and strip Polish diacritics, so "zarowka" finds "żarówka".
 *
 * People search with whatever keyboard they have, and a dictionary that only answers to
 * perfectly typed Polish is a dictionary nobody uses twice.
 */
internal fun fold(text: String): String {
    val lowered = text.lowercase(Locale("pl", "PL")).replace("ł", "l")
    return Normalizer.normalize(lowered, Normalizer.Form.NFKD)
        .filter { it.code < 0x300 || it.code > 0x36F }
}

class WastePointsRepository(context: Context) :
    CachedRemoteSource<WastePointsPayload>(
        context,
        RemoteConfig.dataUrl("waste_points.json"),
        "waste_points.json",
    ) {

    override fun parse(body: String): WastePointsPayload = sharedJson.decodeFromString(body)

    fun points(): List<WastePoint> = (data.value?.points ?: emptyList()).map {
        WastePoint(
            id = it.id,
            name = it.name,
            kind = it.kind,
            address = it.address,
            district = it.district,
            phone = it.phone,
            email = it.email,
            hours = it.hours,
            accepted = it.accepted,
            note = it.note,
            url = it.url,
        )
    }

    fun guide(): List<GuideEntry> = (data.value?.guide ?: emptyList()).map {
        GuideEntry(
            item = it.item,
            destination = runCatching { Destination.valueOf(it.where) }
                .getOrDefault(Destination.INNE),
            note = it.note,
            keywords = it.keywords,
        )
    }

    /** Blank query lists everything, so the screen is useful before anyone types. */
    fun search(query: String): List<GuideEntry> {
        val all = guide()
        if (query.isBlank()) return all
        return all.filter { it.matches(query) }
    }
}
