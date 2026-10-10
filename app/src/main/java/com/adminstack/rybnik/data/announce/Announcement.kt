package com.adminstack.rybnik.data.announce

import android.content.Context
import com.adminstack.rybnik.BuildConfig
import com.adminstack.rybnik.core.net.CachedRemoteSource
import com.adminstack.rybnik.core.net.RemoteConfig
import com.adminstack.rybnik.core.net.sharedJson
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * A message from the developer, written by hand into scraper/data/announcement.json.
 * Every field is optional so that `{}` means "nothing to say"; the format is documented
 * in scraper/CONTRACT.md.
 */
@Serializable
data class AnnouncementDto(
    val id: String? = null,
    val title: String? = null,
    val body: String? = null,
    val link: String? = null,
    val link_label: String? = null,
    /** False shows the card on the dashboard without a notification. */
    val notify: Boolean = true,
    /** Inclusive versionCode range, for "please update" aimed only at old builds. */
    val min_version: Int? = null,
    val max_version: Int? = null,
    /** Last day it is shown, yyyy-MM-dd. */
    val until: String? = null,
)

data class Announcement(
    val id: String,
    val title: String,
    val body: String?,
    val link: String?,
    val linkLabel: String,
    val notify: Boolean,
)

/**
 * Null unless this build should show it today.
 *
 * A malformed `until` hides the message rather than showing it forever: a typo in a
 * hand-edited file should fail toward silence, and it is caught the moment the author
 * checks their own phone.
 */
fun AnnouncementDto.activeFor(versionCode: Int, today: LocalDate): Announcement? {
    val id = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val title = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (min_version != null && versionCode < min_version) return null
    if (max_version != null && versionCode > max_version) return null
    this.until?.let { raw ->
        val last = runCatching { LocalDate.parse(raw.trim()) }.getOrNull() ?: return null
        if (today.isAfter(last)) return null
    }
    return Announcement(
        id = id,
        title = title,
        body = body?.trim()?.takeIf { it.isNotEmpty() },
        // Same rule as every other link in the app: only http(s) reaches ACTION_VIEW.
        link = link?.trim()?.takeIf {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        },
        linkLabel = link_label?.trim()?.takeIf { it.isNotEmpty() } ?: "Otwórz",
        notify = notify,
    )
}

/**
 * One notification per announcement, and none for one already read on the dashboard or
 * closed there: the same courtesy the city alerts get.
 */
fun Announcement.shouldNotify(seenId: String?, dismissedId: String?): Boolean =
    notify && id != seenId && id != dismissedId

class AnnouncementRepository(context: Context) : CachedRemoteSource<AnnouncementDto>(
    context, RemoteConfig.dataUrl("announcement.json"), "announcement.json",
) {
    override fun parse(body: String): AnnouncementDto = sharedJson.decodeFromString(body)

    fun current(today: LocalDate = LocalDate.now()): Announcement? =
        data.value?.activeFor(BuildConfig.VERSION_CODE, today)
}
