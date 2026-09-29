package com.adminstack.rybnik

import android.app.Application
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.room.Room
import com.adminstack.rybnik.core.prefs.UserPrefs
import com.adminstack.rybnik.data.RemoteEventRepository
import com.adminstack.rybnik.data.air.AirQualityRepository
import com.adminstack.rybnik.data.news.NewsRepository
import com.adminstack.rybnik.data.outages.OutageRepository
import com.adminstack.rybnik.data.points.WastePointsRepository
import com.adminstack.rybnik.data.sport.SportRepository
import com.adminstack.rybnik.data.transit.TransitDb
import com.adminstack.rybnik.data.transit.TransitRepository
import com.adminstack.rybnik.data.waste.WasteRepository
import com.adminstack.rybnik.widget.WasteWidget
import com.adminstack.rybnik.work.ReminderAlarms
import com.adminstack.rybnik.work.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    lateinit var sportRepo: SportRepository private set
    lateinit var pointsRepo: WastePointsRepository private set
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
        sportRepo = SportRepository(appContext)
        pointsRepo = WastePointsRepository(appContext)
        prefs = UserPrefs(appContext)

        // A widget only redraws on its own half-hourly tick, so without this someone picks
        // an address, goes back to the home screen and finds "wybierz adres" still there.
        // The first emission also refreshes it whenever the app is opened.
        scope.launch {
            prefs.settings
                .map { it.wasteAddress }
                .distinctUntilChanged()
                .collect { runCatching { WasteWidget().updateAll(appContext) } }
        }

        // The alarm is booked for a specific hour, so moving the slider has to re-book it;
        // otherwise the reminder keeps arriving at the old time until the next reboot.
        scope.launch {
            prefs.settings
                .map { it.wasteReminderHour }
                .distinctUntilChanged()
                .collect { hour -> ReminderAlarms.schedule(appContext, hour) }
        }

        // Cached payloads first so a cold start renders real content instead of spinners.
        scope.launch {
            listOf(eventRepo, wasteRepo, newsRepo, transitRepo, sportRepo, pointsRepo)
                .forEach { it.loadCache() }
            transitRepo.checkReady()
            eventRepo.refresh()
            wasteRepo.refresh()
            newsRepo.refresh()
            airRepo.refresh()
            sportRepo.refresh()
            pointsRepo.refresh()
            // Fresh air reading and schedule: worth one more widget redraw.
            runCatching { WasteWidget().updateAll(appContext) }
        }
    }
}
