package io.github.hugo_cacciatori.basedinformant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.hugo_cacciatori.basedinformant.core.reminder.MissedReminder
import io.github.hugo_cacciatori.basedinformant.core.reminder.NextReminderCalculator
import io.github.hugo_cacciatori.basedinformant.core.reminder.Reminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * The reminder's alarm (04-platforms.md section 3): one one-shot alarm for the next reminder, exact with the "Alarms &
 * reminders" permission, else inexact (it can come late while the phone sleeps). Its time is remembered, so a reminder
 * the device missed is spotted at the next wake and shown when under 12 hours late (owner, 2026-10-29).
 */
object ReminderAlarm {
    const val ACTION = "io.github.hugo_cacciatori.basedinformant.action.REMINDER"
    private const val PREFS = "reminder_alarm"
    private const val DUE = "due"

    /** Arms the next reminder, or cancels it when the reminder is off. */
    fun arm(context: Context, reminder: Reminder) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val operation = operation(context)
        val next = NextReminderCalculator.next(reminder, System.currentTimeMillis(), TimeZone.currentSystemDefault())
        prefs(context).edit().apply { if (next == null) remove(DUE) else putLong(DUE, next) }.apply()
        when {
            next == null -> alarms.cancel(operation)
            exactAllowed(context) -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
            else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
        }
    }

    /** On the alarm, app start, boot, update, time or zone change and permission change: a due reminder, then re-arm. */
    fun wake(context: Context, reminder: Reminder) {
        val due = prefs(context).getLong(DUE, -1L)
        if (reminder.on && due > 0 && MissedReminder.shouldShow(due, System.currentTimeMillis())) Notifications.reminder(context)
        arm(context, reminder)
    }

    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** The system page for "Alarms & reminders" (Android 12+); asked from Settings, never automatically. */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun operation(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The alarm and the system events that re-arm it (04-platforms.md section 3). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        val app = context.applicationContext as BasedInformantApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderAlarm.wake(app, app.settingsStore.load().reminder)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(
            ReminderAlarm.ACTION,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
