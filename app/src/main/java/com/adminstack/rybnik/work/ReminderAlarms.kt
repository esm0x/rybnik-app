package com.adminstack.rybnik.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A daily alarm for the reminders that have a deadline.
 *
 * The periodic worker alone was not enough. WorkManager's six-hour period is a *minimum*
 * with a flex window on top, and in Doze the system batches it further, so the evening
 * waste reminder could land at three in the morning or after the bins had already gone.
 * A reminder that arrives late is worse than none: it teaches people to ignore it.
 *
 * So the schedule is now: an alarm fires at the hour the user picked, hands the actual
 * work to WorkManager, and books the next day. The periodic worker stays as the safety
 * net for everything that is not time-critical (smog, alerts, power cuts) and as a second
 * chance if an alarm is ever dropped.
 *
 * [AlarmManager.setAndAllowWhileIdle] rather than the exact variants on purpose:
 * `setExactAndAllowWhileIdle` needs SCHEDULE_EXACT_ALARM, which from Android 14 is denied
 * by default and which Google Play only grants to alarm clocks and calendars. Putting the
 * bins out is not an alarm clock. The inexact version has no permission requirement and
 * still fires inside Doze, just within a window of a few minutes, which for "tomorrow
 * morning they take the bins" is entirely enough.
 */
object ReminderAlarms {

    private const val TAG = "ReminderAlarms"
    private const val REQUEST_CODE = 4201
    const val ACTION_DAILY = "com.adminstack.rybnik.DAILY_REMINDER"

    /** Books the next firing at [hour]:00. Safe to call repeatedly; it replaces the old one. */
    fun schedule(context: Context, hour: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = LocalDateTime.now()
        var next = now.withHour(hour.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)

        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
            Log.i(TAG, "next reminder alarm at $next")
        }.onFailure { Log.w(TAG, "could not schedule the alarm", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_DAILY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Runs when the daily alarm fires.
 *
 * A receiver gets roughly ten seconds and this work hits the network, so it only enqueues
 * the worker and returns. It also books tomorrow immediately: [AlarmManager] holds one
 * alarm at a time, so the chain breaks the moment a link forgets to set the next one.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reminders-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DailyReminderWorker>().build(),
        )
        Reminders.rescheduleAlarm(context)
    }
}

/**
 * Alarms do not survive a reboot, unlike WorkManager's own jobs, so without this the
 * evening reminder would quietly stop after the first restart and come back only when
 * somebody opened the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        Reminders.rescheduleAll(context)
    }
}
