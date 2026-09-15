package eu.rybnik.events.core.prefs

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
    val notifyWaste: Boolean = true,
    val notifyEvents: Boolean = true,
    val notifySmog: Boolean = true,
    val notifyCityAlerts: Boolean = true,
    /** PM10 µg/m³ above which we raise a smog notification. */
    val smogThreshold: Int = DEFAULT_SMOG_THRESHOLD,
    /** Hour of the evening before collection when the waste reminder fires. */
    val wasteReminderHour: Int = DEFAULT_WASTE_HOUR,
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
            notifyWaste = p[KEY_NOTIFY_WASTE] ?: true,
            notifyEvents = p[KEY_NOTIFY_EVENTS] ?: true,
            notifySmog = p[KEY_NOTIFY_SMOG] ?: true,
            notifyCityAlerts = p[KEY_NOTIFY_ALERTS] ?: true,
            smogThreshold = p[KEY_SMOG_THRESHOLD] ?: Settings.DEFAULT_SMOG_THRESHOLD,
            wasteReminderHour = p[KEY_WASTE_HOUR] ?: Settings.DEFAULT_WASTE_HOUR,
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

    suspend fun setNotify(which: NotifyChannel, enabled: Boolean) = context.dataStore.edit { p ->
        p[which.key] = enabled
    }

    suspend fun setSmogThreshold(value: Int) = context.dataStore.edit { p ->
        p[KEY_SMOG_THRESHOLD] = value
    }

    suspend fun setWasteReminderHour(hour: Int) = context.dataStore.edit { p ->
        p[KEY_WASTE_HOUR] = hour
    }

    enum class NotifyChannel(internal val key: Preferences.Key<Boolean>) {
        Waste(KEY_NOTIFY_WASTE),
        Events(KEY_NOTIFY_EVENTS),
        Smog(KEY_NOTIFY_SMOG),
        CityAlerts(KEY_NOTIFY_ALERTS),
    }
}

private val KEY_ADDR_DISTRICT = stringPreferencesKey("addr_district")
private val KEY_ADDR_STREET = stringPreferencesKey("addr_street")
private val KEY_ADDR_NUMBER = stringPreferencesKey("addr_number")
private val KEY_ADDR_TYPE = stringPreferencesKey("addr_type")
private val KEY_ADDR_REJON = stringPreferencesKey("addr_rejon")
private val KEY_FAV_EVENTS = stringSetPreferencesKey("fav_events")
private val KEY_FAV_STOPS = stringSetPreferencesKey("fav_stops")
private val KEY_NOTIFY_WASTE = booleanPreferencesKey("notify_waste")
private val KEY_NOTIFY_EVENTS = booleanPreferencesKey("notify_events")
private val KEY_NOTIFY_SMOG = booleanPreferencesKey("notify_smog")
private val KEY_NOTIFY_ALERTS = booleanPreferencesKey("notify_alerts")
private val KEY_SMOG_THRESHOLD = intPreferencesKey("smog_threshold")
private val KEY_WASTE_HOUR = intPreferencesKey("waste_hour")
