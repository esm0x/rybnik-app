package com.adminstack.rybnik.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adminstack.rybnik.Graph
import com.adminstack.rybnik.MainActivity
import com.adminstack.rybnik.R
import com.adminstack.rybnik.core.prefs.Settings
import com.adminstack.rybnik.data.outages.OutageKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object Reminders {

    const val CHANNEL_WASTE = "waste"
    const val CHANNEL_EVENTS = "events"
    const val CHANNEL_SMOG = "smog"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_OUTAGES = "outages"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            Triple(CHANNEL_WASTE, "Wywóz odpadów", "Przypomnienie wieczorem przed wywozem"),
            Triple(CHANNEL_EVENTS, "Ulubione wydarzenia", "Przypomnienie dzień przed wydarzeniem"),
            Triple(CHANNEL_SMOG, "Jakość powietrza", "Ostrzeżenia o smogu"),
            Triple(CHANNEL_ALERTS, "Komunikaty miejskie", "Awarie i utrudnienia"),
            Triple(CHANNEL_OUTAGES, "Wyłączenia prądu", "Wyłączenia pod Twoim adresem"),
        ).forEach { (id, name, desc) ->
            mgr.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = desc
                }
            )
        }
    }

    /** Which screen a tapped reminder should open. Read by MainActivity. */
    const val EXTRA_DESTINATION = "rybnik.destination"
    const val DEST_HOME = "home"
    const val DEST_WASTE = "waste"
    const val DEST_EVENTS = "events"
    const val DEST_NEWS = "news"
    const val DEST_AIR = "air"

    /**
     * Two mechanisms on purpose, because they fail differently.
     *
     * The periodic worker is the safety net: it survives reboots on its own and catches
     * smog, city alerts and power cuts, none of which care about the exact minute. The
     * daily alarm carries the reminders that do have a deadline, because WorkManager's
     * six-hour period is a floor, not a promise, and Doze stretches it further.
     */
    fun rescheduleAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "daily-reminders",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DailyReminderWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(nextRunDelayMinutes(), TimeUnit.MINUTES)
                .build(),
        )
        rescheduleAlarm(context)
    }

    /** Books the daily alarm at the hour the user picked for the waste reminder. */
    fun rescheduleAlarm(context: Context) {
        Graph.scope.launch {
            val hour = runCatching { Graph.prefs.settings.first().wasteReminderHour }
                .getOrDefault(Settings.DEFAULT_WASTE_HOUR)
            ReminderAlarms.schedule(context, hour)
        }
    }

    /** Aim the first run at the top of the next hour so reminders land predictably. */
    private fun nextRunDelayMinutes(): Long {
        val now = LocalDateTime.now()
        val next = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1)
        return Duration.between(now, next).toMinutes().coerceAtLeast(1)
    }

    /**
     * @param destination which screen the tap should open, one of the DEST_ constants.
     */
    fun notify(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        destination: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, id, destination))
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /**
     * Without this a reminder is a dead end: tapping it does nothing at all, and even
     * setAutoCancel has no effect, because there is no click for it to follow.
     *
     * [id] doubles as the request code. PendingIntents are matched on requestCode, action
     * and data, and *not* on extras — reuse one code for every reminder and Android hands
     * back the first PendingIntent it made, so the smog notification would open whichever
     * screen the waste one asked for.
     */
    private fun openApp(context: Context, id: Int, destination: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DESTINATION, destination)
        }
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * One periodic worker covers all four reminder types. They share a cadence and each check
 * is cheap, so separate workers would only multiply scheduling overhead.
 */
class DailyReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = Graph.prefs.settings.first()
        val ctx = applicationContext

        if (settings.notifyWaste) runCatching { checkWaste(settings.wasteReminderHour) }
        if (settings.notifyEvents) runCatching { checkEvents() }
        if (settings.notifySmog) runCatching { checkSmog(settings.smogThreshold) }
        if (settings.notifyCityAlerts) runCatching { checkAlerts() }
        if (settings.notifyOutages) runCatching { checkOutages() }

        return Result.success()
    }

    private suspend fun checkWaste(reminderHour: Int) {
        val settings = Graph.prefs.settings.first()
        val address = settings.wasteAddress ?: return
        if (LocalTime.now().hour < reminderHour) return

        Graph.wasteRepo.refresh()
        val tomorrow = LocalDate.now().plusDays(1)
        val next = Graph.wasteRepo.schedule()
            .collections(address.rejonId, tomorrow, tomorrow)
            .firstOrNull() ?: return

        Reminders.notify(
            applicationContext, Reminders.CHANNEL_WASTE, NOTIF_WASTE,
            "Jutro wywóz odpadów",
            next.types.joinToString(", ") { it.label } + " · ${address.pretty}",
            Reminders.DEST_WASTE,
        )
    }

    private suspend fun checkEvents() {
        val favourites = Graph.prefs.settings.first().favouriteEventIds
        if (favourites.isEmpty()) return
        Graph.eventRepo.refresh()
        val tomorrow = LocalDate.now().plusDays(1)
        val due = Graph.eventRepo.snapshot()
            .filter { it.id in favourites && it.start.toLocalDate() == tomorrow }
        if (due.isEmpty()) return

        val text = due.joinToString("\n") {
            "${it.start.toLocalTime()} · ${it.title}"
        }
        Reminders.notify(
            applicationContext, Reminders.CHANNEL_EVENTS, NOTIF_EVENTS,
            if (due.size == 1) "Jutro: ${due.first().title}" else "Jutro ${due.size} wydarzenia",
            text,
            Reminders.DEST_EVENTS,
        )
    }

    private suspend fun checkSmog(threshold: Int) {
        Graph.airRepo.refresh()
        val state = Graph.airRepo.state.value
        val pm10 = state.pm10 ?: return
        if (pm10.value < threshold) return

        // The user's own threshold can sit below the official information level, so the
        // index may still read "Dobre" when this fires. Announcing "Smog w Rybniku: Dobre"
        // contradicts itself, so the headline states the measurement and the body explains
        // why it arrived.
        Reminders.notify(
            applicationContext, Reminders.CHANNEL_SMOG, NOTIF_SMOG,
            "PM10 ${pm10.value.toInt()} µg/m³ w Rybniku",
            "Powyżej Twojego progu $threshold µg/m³. Jakość powietrza: ${state.severity.label}.",
            Reminders.DEST_AIR,
        )
    }

    private suspend fun checkAlerts() {
        Graph.newsRepo.refresh()
        // A dismissed alert should not come back as a notification.
        val hidden = Graph.prefs.settings.first().hiddenNewsIds
        val newest = Graph.newsRepo.items(hidden).firstOrNull { it.isAlert } ?: return
        if (newest.published.toLocalDate() != LocalDate.now()) return

        Reminders.notify(
            applicationContext, Reminders.CHANNEL_ALERTS, NOTIF_ALERTS,
            "Komunikat: ${newest.source}", newest.title,
            Reminders.DEST_NEWS,
        )
    }

    /** Only warn about cuts starting within the next two days — earlier is just noise. */
    private suspend fun checkOutages() {
        val address = Graph.prefs.settings.first().wasteAddress ?: return
        Graph.outageRepo.refresh(address)
        val soon = LocalDate.now().plusDays(2)
        val due = Graph.outageRepo.state.value.outages
            .filter { !it.from.toLocalDate().isAfter(soon) }
        val next = due.firstOrNull() ?: return

        Reminders.notify(
            applicationContext, Reminders.CHANNEL_OUTAGES, NOTIF_OUTAGES,
            if (next.kind == OutageKind.PLANNED) "Planowane wyłączenie prądu"
            else "Awaria zasilania",
            "${next.from.toLocalDate()} ${next.from.toLocalTime()} · ${address.pretty}",
            Reminders.DEST_HOME,
        )
    }

    private companion object {
        const val NOTIF_WASTE = 1001
        const val NOTIF_EVENTS = 1002
        const val NOTIF_SMOG = 1003
        const val NOTIF_ALERTS = 1004
        const val NOTIF_OUTAGES = 1005
    }
}
