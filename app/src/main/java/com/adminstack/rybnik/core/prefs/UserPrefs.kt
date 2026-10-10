package com.adminstack.rybnik.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "rybnik_prefs")

/** Which waste schedule model applies to the user's building. */
enum class HouseType { SINGLE_FAMILY, MULTI_FAMILY }

enum class ThemeMode(val label: String) {
    SYSTEM("Jak system"),
    LIGHT("Jasny"),
    DARK("Ciemny"),
}

data class WasteAddress(
    val district: String,
    val street: String,
    val houseNumber: String,
    val houseType: HouseType,
    val rejonId: String,
) {
    val pretty: String get() = "$street $houseNumber, $district"
}

data class Settings(
    val wasteAddress: WasteAddress? = null,
    val favouriteEventIds: Set<String> = emptySet(),
    val favouriteStopIds: Set<String> = emptySet(),
    /** News the user dismissed by hand. Kept out of the list, the dashboard and alerts. */
    val hiddenNewsIds: Set<String> = emptySet(),
    /** Alerts that have been on screen in the app, so they are not announced again. */
    val seenAlertIds: Set<String> = emptySet(),
    /** Everything already announced, as dated keys; see NotificationRules.key. */
    val notifiedKeys: Set<String> = emptySet(),
    /** The developer announcement already read on the dashboard or announced. */
    val announcementSeenId: String? = null,
    /** The developer announcement the user closed; a new id brings the card back. */
    val announcementDismissedId: String? = null,
    val notifyWaste: Boolean = true,
    val notifyEvents: Boolean = true,
    val notifySmog: Boolean = true,
    val notifyCityAlerts: Boolean = true,
    val notifyOutages: Boolean = true,
    /** PM10 µg/m³ above which we raise a smog notification. */
    val smogThreshold: Int = DEFAULT_SMOG_THRESHOLD,
    /** Hour of the evening before collection when the waste reminder fires. */
    val wasteReminderHour: Int = DEFAULT_WASTE_HOUR,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Which GTFS edition sits in the Room database, and how long it is valid. */
    val gtfsEdition: String? = null,
    val gtfsValidTo: String? = null,
) {
    companion object {
        const val DEFAULT_SMOG_THRESHOLD = 80
        const val DEFAULT_WASTE_HOUR = 18
    }
}

class UserPrefs(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            wasteAddress = readAddress(p),
            favouriteEventIds = p[KEY_FAV_EVENTS] ?: emptySet(),
            favouriteStopIds = p[KEY_FAV_STOPS] ?: emptySet(),
            hiddenNewsIds = p[KEY_HIDDEN_NEWS] ?: emptySet(),
            seenAlertIds = p[KEY_SEEN_ALERTS] ?: emptySet(),
            notifiedKeys = p[KEY_NOTIFIED] ?: emptySet(),
            announcementSeenId = p[KEY_ANNOUNCE_SEEN],
            announcementDismissedId = p[KEY_ANNOUNCE_DISMISSED],
            notifyWaste = p[KEY_NOTIFY_WASTE] ?: true,
            notifyEvents = p[KEY_NOTIFY_EVENTS] ?: true,
            notifySmog = p[KEY_NOTIFY_SMOG] ?: true,
            notifyCityAlerts = p[KEY_NOTIFY_ALERTS] ?: true,
            notifyOutages = p[KEY_NOTIFY_OUTAGES] ?: true,
            smogThreshold = p[KEY_SMOG_THRESHOLD] ?: Settings.DEFAULT_SMOG_THRESHOLD,
            wasteReminderHour = p[KEY_WASTE_HOUR] ?: Settings.DEFAULT_WASTE_HOUR,
            themeMode = runCatching { ThemeMode.valueOf(p[KEY_THEME].orEmpty()) }
                .getOrDefault(ThemeMode.SYSTEM),
            gtfsEdition = p[KEY_GTFS_EDITION],
            gtfsValidTo = p[KEY_GTFS_VALID_TO],
        )
    }

    private fun readAddress(p: Preferences): WasteAddress? {
        val district = p[KEY_ADDR_DISTRICT] ?: return null
        val street = p[KEY_ADDR_STREET] ?: return null
        val number = p[KEY_ADDR_NUMBER] ?: return null
        val rejon = p[KEY_ADDR_REJON] ?: return null
        val type = runCatching { HouseType.valueOf(p[KEY_ADDR_TYPE] ?: "") }
            .getOrDefault(HouseType.SINGLE_FAMILY)
        return WasteAddress(district, street, number, type, rejon)
    }

    suspend fun setWasteAddress(address: WasteAddress) = context.dataStore.edit { p ->
        p[KEY_ADDR_DISTRICT] = address.district
        p[KEY_ADDR_STREET] = address.street
        p[KEY_ADDR_NUMBER] = address.houseNumber
        p[KEY_ADDR_TYPE] = address.houseType.name
        p[KEY_ADDR_REJON] = address.rejonId
    }

    suspend fun clearWasteAddress() = context.dataStore.edit { p ->
        listOf(KEY_ADDR_DISTRICT, KEY_ADDR_STREET, KEY_ADDR_NUMBER, KEY_ADDR_TYPE, KEY_ADDR_REJON)
            .forEach { p.remove(it) }
    }

    suspend fun toggleFavouriteEvent(id: String) = toggle(KEY_FAV_EVENTS, id)

    suspend fun toggleFavouriteStop(id: String) = toggle(KEY_FAV_STOPS, id)

    private suspend fun toggle(key: Preferences.Key<Set<String>>, id: String) {
        context.dataStore.edit { p ->
            val current = p[key] ?: emptySet()
            p[key] = if (id in current) current - id else current + id
        }
    }

    /**
     * The scraper never drops an ALERT by age or by the 120-item cap, so a roadworks
     * notice can sit at the top of the list for months. Hiding is the user's own escape
     * hatch: local, per device, and reversible.
     */
    suspend fun hideNews(id: String) = context.dataStore.edit { p ->
        p[KEY_HIDDEN_NEWS] = (p[KEY_HIDDEN_NEWS] ?: emptySet()) + id
    }

    suspend fun unhideNews(id: String) = context.dataStore.edit { p ->
        p[KEY_HIDDEN_NEWS] = (p[KEY_HIDDEN_NEWS] ?: emptySet()) - id
    }

    suspend fun clearHiddenNews() = context.dataStore.edit { p -> p.remove(KEY_HIDDEN_NEWS) }

    /**
     * Called as an alert scrolls into view. Reading it in the app is reading it: the
     * tester's one notification in a week was for something already read on the dashboard.
     * Skips the write when nothing is new, since this fires on every composition.
     */
    suspend fun markAlertsSeen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { p ->
            val current = p[KEY_SEEN_ALERTS] ?: emptySet()
            if (!current.containsAll(ids)) p[KEY_SEEN_ALERTS] = current + ids
        }
    }

    /** Same guard as [pruneHiddenNews]: an empty feed means a failed fetch, not an empty one. */
    suspend fun pruneSeenAlerts(alive: Set<String>) {
        if (alive.isEmpty()) return
        context.dataStore.edit { p ->
            val current = p[KEY_SEEN_ALERTS] ?: return@edit
            val kept = current intersect alive
            if (kept.size != current.size) p[KEY_SEEN_ALERTS] = kept
        }
    }

    suspend fun markAnnouncementSeen(id: String) = context.dataStore.edit { p ->
        if (p[KEY_ANNOUNCE_SEEN] != id) p[KEY_ANNOUNCE_SEEN] = id
    }

    suspend fun dismissAnnouncement(id: String) = context.dataStore.edit { p ->
        p[KEY_ANNOUNCE_DISMISSED] = id
    }

    /** Replaces the ledger; pruning is the caller's job, it knows what "old" means. */
    suspend fun setNotifiedKeys(keys: Set<String>) = context.dataStore.edit { p ->
        p[KEY_NOTIFIED] = keys
    }

    /**
     * Forget ids that fell out of the feed, so the set does not grow without bound.
     * An empty [alive] means the fetch failed, not that the feed is empty — pruning
     * against it would silently unhide everything.
     */
    suspend fun pruneHiddenNews(alive: Set<String>) {
        if (alive.isEmpty()) return
        context.dataStore.edit { p ->
            val current = p[KEY_HIDDEN_NEWS] ?: return@edit
            val kept = current intersect alive
            if (kept.size != current.size) p[KEY_HIDDEN_NEWS] = kept
        }
    }

    suspend fun setNotify(which: NotifyChannel, enabled: Boolean) = context.dataStore.edit { p ->
        p[which.key] = enabled
    }

    suspend fun setSmogThreshold(value: Int) = context.dataStore.edit { p ->
        p[KEY_SMOG_THRESHOLD] = value
    }

    suspend fun setWasteReminderHour(hour: Int) = context.dataStore.edit { p ->
        p[KEY_WASTE_HOUR] = hour
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { p ->
        p[KEY_THEME] = mode.name
    }

    /**
     * Recorded after a successful GTFS import. Without it the app has no way to tell the
     * timetable in the database apart from the one the city is publishing now, so it kept
     * the first one it ever downloaded.
     */
    suspend fun setGtfsImport(edition: String?, validTo: String?) = context.dataStore.edit { p ->
        if (edition != null) p[KEY_GTFS_EDITION] = edition else p.remove(KEY_GTFS_EDITION)
        if (validTo != null) p[KEY_GTFS_VALID_TO] = validTo else p.remove(KEY_GTFS_VALID_TO)
    }

    enum class NotifyChannel(internal val key: Preferences.Key<Boolean>) {
        Waste(KEY_NOTIFY_WASTE),
        Events(KEY_NOTIFY_EVENTS),
        Smog(KEY_NOTIFY_SMOG),
        CityAlerts(KEY_NOTIFY_ALERTS),
        Outages(KEY_NOTIFY_OUTAGES),
    }
}

private val KEY_ADDR_DISTRICT = stringPreferencesKey("addr_district")
private val KEY_ADDR_STREET = stringPreferencesKey("addr_street")
private val KEY_ADDR_NUMBER = stringPreferencesKey("addr_number")
private val KEY_ADDR_TYPE = stringPreferencesKey("addr_type")
private val KEY_ADDR_REJON = stringPreferencesKey("addr_rejon")
private val KEY_FAV_EVENTS = stringSetPreferencesKey("fav_events")
private val KEY_FAV_STOPS = stringSetPreferencesKey("fav_stops")
private val KEY_HIDDEN_NEWS = stringSetPreferencesKey("hidden_news")
private val KEY_SEEN_ALERTS = stringSetPreferencesKey("seen_alerts")
private val KEY_NOTIFIED = stringSetPreferencesKey("notified_keys")
private val KEY_ANNOUNCE_SEEN = stringPreferencesKey("announce_seen")
private val KEY_ANNOUNCE_DISMISSED = stringPreferencesKey("announce_dismissed")
private val KEY_NOTIFY_WASTE = booleanPreferencesKey("notify_waste")
private val KEY_NOTIFY_EVENTS = booleanPreferencesKey("notify_events")
private val KEY_NOTIFY_SMOG = booleanPreferencesKey("notify_smog")
private val KEY_NOTIFY_ALERTS = booleanPreferencesKey("notify_alerts")
private val KEY_NOTIFY_OUTAGES = booleanPreferencesKey("notify_outages")
private val KEY_SMOG_THRESHOLD = intPreferencesKey("smog_threshold")
private val KEY_WASTE_HOUR = intPreferencesKey("waste_hour")
private val KEY_THEME = stringPreferencesKey("theme_mode")
private val KEY_GTFS_EDITION = stringPreferencesKey("gtfs_edition")
private val KEY_GTFS_VALID_TO = stringPreferencesKey("gtfs_valid_to")
