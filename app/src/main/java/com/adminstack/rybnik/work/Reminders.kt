package com.adminstack.rybnik.work

import com.adminstack.rybnik.data.announce.shouldNotify
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    const val CHANNEL_APP = "app"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            Triple(CHANNEL_WASTE, "Wywóz odpadów", "Przypomnienie wieczorem przed wywozem"),
            Triple(CHANNEL_EVENTS, "Ulubione wydarzenia", "Przypomnienie dzień przed wydarzeniem"),
            Triple(CHANNEL_SMOG, "Jakość powietrza", "Ostrzeżenia o smogu"),
            Triple(CHANNEL_ALERTS, "Komunikaty miejskie", "Awarie i utrudnienia"),
            Triple(CHANNEL_OUTAGES, "Wyłączenia prądu", "Wyłączenia pod Twoim adresem"),
            Triple(CHANNEL_APP, "Od autora aplikacji", "Ważne informacje o aplikacji, np. o aktualizacjach"),
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

    const val GROUP_ALERTS = "com.adminstack.rybnik.ALERTS"
    private const val MAX_SUMMARY_LINES = 5

    /**
     * Two mechanisms on purpose, because they fail differently.
     *
     * The periodic worker is the safety net: it survives reboots on its own and catches
     * smog, city alerts and power cuts, none of which care about the exact minute. The
     * daily alarm carries the reminders that do have a deadline, because WorkManager's
     * periodic interval is a floor, not a promise, and Doze stretches it further.
     */
    fun rescheduleAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "daily-reminders",
            ExistingPeriodicWorkPolicy.UPDATE,
            // Three hours rather than six: the feed itself updates every six, so this
            // halves the wait between an alert being published and reaching the phone.
            // It is cheap now that the ledger stops every extra run from repeating itself.
            PeriodicWorkRequestBuilder<DailyReminderWorker>(3, TimeUnit.HOURS)
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
    /**
     * @return whether it was actually posted. Only then may the caller record it in the
     *   ledger: a reminder swallowed for lack of permission was never delivered, and
     *   marking it sent would lose it for good once the permission is granted.
     */
    fun notify(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        destination: String,
        group: String? = null,
    ): Boolean {
        if (!canPost(context)) return false

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, id, destination))
            .apply { group?.let { setGroup(it) } }
            .build()

        return runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .isSuccess
    }

    /**
     * The header of a stack of alerts. Android only folds notifications together under
     * one of these; without it, three alerts arrive as three unrelated banners.
     */
    fun notifyGroupSummary(
        context: Context,
        channel: String,
        id: Int,
        group: String,
        title: String,
        lines: List<String>,
        destination: String,
    ) {
        if (!canPost(context)) return
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(MAX_SUMMARY_LINES).forEach { style.addLine(it) }
        if (lines.size > MAX_SUMMARY_LINES) {
            style.setSummaryText("i jeszcze ${lines.size - MAX_SUMMARY_LINES}")
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(style)
            .setGroup(group)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, id, destination))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /**
     * One id per alert, so a second alert sits beside the first instead of replacing it,
     * which is what the single shared id used to do. Kept above 0x10000000 so it cannot
     * collide with the fixed ids of the other reminder types.
     */
    fun alertNotificationId(alertId: String): Int = 0x10000000 or (alertId.hashCode() and 0x0FFFFFFF)

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

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

    /**
     * The daily alarm and the periodic job can both start this worker, and WorkManager
     * will run them side by side. Without the lock both would read the ledger before
     * either wrote to it, and the same reminder would arrive twice.
     */
    override suspend fun doWork(): Result = runLock.withLock {
        val settings = Graph.prefs.settings.first()
        val ledger = settings.notifiedKeys.toMutableSet()
        val before = ledger.toSet()

        if (settings.notifyWaste) runCatching { checkWaste(settings.wasteReminderHour, ledger) }
        if (settings.notifyEvents) runCatching { checkEvents(ledger) }
        if (settings.notifySmog) runCatching { checkSmog(settings.smogThreshold, ledger) }
        if (settings.notifyCityAlerts) runCatching { checkAlerts(ledger) }
        if (settings.notifyOutages) runCatching { checkOutages(ledger) }
        // No switch in the app's settings: it is rare, and its own system channel can be
        // silenced like any other.
        runCatching { checkAnnouncement() }

        val pruned = NotificationRules.prune(ledger, LocalDate.now())
        if (pruned != before) Graph.prefs.setNotifiedKeys(pruned)
        Result.success()
    }

    /**
     * Once per collection. Since 1.2.0 both the 18:00 alarm and the periodic job run after
     * the reminder hour, and each would have announced the same bins.
     */
    private suspend fun checkWaste(reminderHour: Int, ledger: MutableSet<String>) {
        val address = Graph.prefs.settings.first().wasteAddress ?: return
        if (LocalTime.now().hour < reminderHour) return
        val tomorrow = LocalDate.now().plusDays(1)
        val key = NotificationRules.key(tomorrow, "waste", address.rejonId)
        if (key in ledger) return

        Graph.wasteRepo.refresh()
        val next = Graph.wasteRepo.schedule()
            .collections(address.rejonId, tomorrow, tomorrow)
            .firstOrNull() ?: return

        val posted = Reminders.notify(
            applicationContext, Reminders.CHANNEL_WASTE, NOTIF_WASTE,
            "Jutro wywóz odpadów",
            next.types.joinToString(", ") { it.label } + " · ${address.pretty}",
            Reminders.DEST_WASTE,
        )
        if (posted) ledger += key
    }

    /** Each favourite once; a favourite added later the same day still gets its turn. */
    private suspend fun checkEvents(ledger: MutableSet<String>) {
        val favourites = Graph.prefs.settings.first().favouriteEventIds
        if (favourites.isEmpty()) return
        Graph.eventRepo.refresh()
        val tomorrow = LocalDate.now().plusDays(1)
        val due = Graph.eventRepo.snapshot()
            .filter { it.id in favourites && it.start.toLocalDate() == tomorrow }
            .filter { NotificationRules.key(tomorrow, "event", it.id) !in ledger }
        if (due.isEmpty()) return

        val text = due.joinToString("\n") {
            "${it.start.toLocalTime()} · ${it.title}"
        }
        val posted = Reminders.notify(
            applicationContext, Reminders.CHANNEL_EVENTS, NOTIF_EVENTS,
            if (due.size == 1) "Jutro: ${due.first().title}" else "Jutro ${due.size} wydarzenia",
            text,
            Reminders.DEST_EVENTS,
        )
        if (posted) due.forEach { ledger += NotificationRules.key(tomorrow, "event", it.id) }
    }

    /**
     * Once a day while it lasts. Smog sits over Rybnik for days in winter, and with the
     * worker now running every three hours an unguarded check would sound eight times a day.
     */
    private suspend fun checkSmog(threshold: Int, ledger: MutableSet<String>) {
        val key = NotificationRules.key(LocalDate.now(), "smog", "pm10")
        if (key in ledger) return

        Graph.airRepo.refresh()
        val state = Graph.airRepo.state.value
        val pm10 = state.pm10 ?: return
        if (pm10.value < threshold) return

        // The user's own threshold can sit below the official information level, so the
        // index may still read "Dobre" when this fires. Announcing "Smog w Rybniku: Dobre"
        // contradicts itself, so the headline states the measurement and the body explains
        // why it arrived.
        val posted = Reminders.notify(
            applicationContext, Reminders.CHANNEL_SMOG, NOTIF_SMOG,
            "PM10 ${pm10.value.toInt()} µg/m³ w Rybniku",
            "Powyżej Twojego progu $threshold µg/m³. Jakość powietrza: ${state.severity.label}.",
            Reminders.DEST_AIR,
        )
        if (posted) ledger += key
    }

    /**
     * Every new alert, each in its own notification, stacked under one header when there
     * are several. NotificationRules decides which; see there for why the old rule lost
     * most of them.
     */
    private suspend fun checkAlerts(ledger: MutableSet<String>) {
        Graph.newsRepo.refresh()
        Graph.prefs.pruneSeenAlerts(Graph.newsRepo.allIds())
        val settings = Graph.prefs.settings.first()

        val fresh = NotificationRules.alertsToNotify(
            items = Graph.newsRepo.items(settings.hiddenNewsIds),
            hidden = settings.hiddenNewsIds,
            seen = settings.seenAlertIds,
            ledger = ledger,
            now = LocalDateTime.now(),
        )
        if (fresh.isEmpty()) return

        var posted = false
        fresh.take(NotificationRules.MAX_ALERT_NOTIFICATIONS).forEach { alert ->
            posted = Reminders.notify(
                applicationContext, Reminders.CHANNEL_ALERTS,
                Reminders.alertNotificationId(alert.id),
                alert.title,
                listOfNotNull(alert.source, alert.summary?.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                Reminders.DEST_NEWS,
                group = Reminders.GROUP_ALERTS,
            ) || posted
        }
        if (fresh.size > 1) {
            Reminders.notifyGroupSummary(
                applicationContext, Reminders.CHANNEL_ALERTS, NOTIF_ALERTS,
                Reminders.GROUP_ALERTS,
                NotificationRules.newAlertsHeadline(fresh.size),
                fresh.map { it.title },
                Reminders.DEST_NEWS,
            )
        }
        // The overflow beyond the cap is in the summary, so it counts as announced too.
        if (posted) fresh.forEach { ledger += NotificationRules.alertKey(it) }
    }

    /** Only cuts starting within the next two days, each one once. */
    private suspend fun checkOutages(ledger: MutableSet<String>) {
        val address = Graph.prefs.settings.first().wasteAddress ?: return
        Graph.outageRepo.refresh(address)
        val soon = LocalDate.now().plusDays(2)
        fun keyOf(o: com.adminstack.rybnik.data.outages.Outage) =
            NotificationRules.key(o.from.toLocalDate(), "outage", "${o.kind}-${o.from}")

        val next = Graph.outageRepo.state.value.outages
            .filter { !it.from.toLocalDate().isAfter(soon) }
            .firstOrNull { keyOf(it) !in ledger } ?: return

        val posted = Reminders.notify(
            applicationContext, Reminders.CHANNEL_OUTAGES, NOTIF_OUTAGES,
            if (next.kind == OutageKind.PLANNED) "Planowane wyłączenie prądu"
            else "Awaria zasilania",
            "${next.from.toLocalDate()} ${next.from.toLocalTime()} · ${address.pretty}",
            Reminders.DEST_HOME,
        )
        if (posted) ledger += keyOf(next)
    }

    /** The developer's message, once, unless it was already read on the dashboard. */
    private suspend fun checkAnnouncement() {
        Graph.announceRepo.refresh()
        val announcement = Graph.announceRepo.current() ?: return
        val settings = Graph.prefs.settings.first()
        if (!announcement.shouldNotify(settings.announcementSeenId, settings.announcementDismissedId)) return
        val posted = Reminders.notify(
            applicationContext, Reminders.CHANNEL_APP, NOTIF_APP,
            announcement.title,
            announcement.body.orEmpty(),
            Reminders.DEST_HOME,
        )
        if (posted) Graph.prefs.markAnnouncementSeen(announcement.id)
    }

    private companion object {
        const val NOTIF_WASTE = 1001
        const val NOTIF_EVENTS = 1002
        const val NOTIF_SMOG = 1003
        const val NOTIF_ALERTS = 1004
        const val NOTIF_OUTAGES = 1005
        const val NOTIF_APP = 1006

        val runLock = Mutex()
    }
}
