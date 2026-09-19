package eu.rybnik.events

import android.app.Application
import android.content.Context
import androidx.room.Room
import eu.rybnik.events.core.prefs.UserPrefs
import eu.rybnik.events.data.RemoteEventRepository
import eu.rybnik.events.data.air.AirQualityRepository
import eu.rybnik.events.data.news.NewsRepository
import eu.rybnik.events.data.outages.OutageRepository
import eu.rybnik.events.data.transit.TransitDb
import eu.rybnik.events.data.transit.TransitRepository
import eu.rybnik.events.data.waste.WasteRepository
import eu.rybnik.events.work.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RybnikApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Reminders.createChannels(this)
        Graph.scope.launch { Reminders.rescheduleAll(this@RybnikApplication) }
    }
}

/**
 * Poor man's DI. Worth replacing with Hilt once anything here needs a real lifecycle,
 * but the whole graph is process-scoped singletons, so a manual object still fits.
 */
object Graph {

    lateinit var eventRepo: RemoteEventRepository private set
    lateinit var wasteRepo: WasteRepository private set
    lateinit var newsRepo: NewsRepository private set
    lateinit var transitRepo: TransitRepository private set
    lateinit var airRepo: AirQualityRepository private set
    lateinit var outageRepo: OutageRepository private set
    lateinit var prefs: UserPrefs private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(appContext: Context) {
        if (::eventRepo.isInitialized) return

        val db = Room.databaseBuilder(appContext, TransitDb::class.java, "transit.db")
            .fallbackToDestructiveMigration()
            .build()

        eventRepo = RemoteEventRepository(appContext)
        wasteRepo = WasteRepository(appContext)
        newsRepo = NewsRepository(appContext)
        transitRepo = TransitRepository(appContext, db)
        airRepo = AirQualityRepository()
        outageRepo = OutageRepository()
        prefs = UserPrefs(appContext)

        // Cached payloads first so a cold start renders real content instead of spinners.
        scope.launch {
            listOf(eventRepo, wasteRepo, newsRepo, transitRepo).forEach { it.loadCache() }
            transitRepo.checkReady()
            eventRepo.refresh()
            wasteRepo.refresh()
            newsRepo.refresh()
            airRepo.refresh()
        }
    }
}
