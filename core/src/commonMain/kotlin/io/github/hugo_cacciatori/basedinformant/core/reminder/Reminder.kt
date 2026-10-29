@file:OptIn(ExperimentalTime::class)

package io.github.hugo_cacciatori.basedinformant.core.reminder

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The reminder (01-product.md section 6, owner 2026-10-29): a free notification at the chosen weekdays and one time of
 * day. It never generates anything. Monday 08:00 when first turned on.
 */
data class Reminder(
    val on: Boolean = false,
    val days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
    val time: LocalTime = LocalTime(8, 0),
)

object NextReminderCalculator {
    /**
     * The next reminder strictly after [nowMillis], from the local date and time in [zone], so DST and time zone changes
     * come out right (never a fixed 7 × 24 h). Null when the reminder is off or has no weekday. A time skipped by a DST
     * change moves forward by the gap; a time that happens twice is the first one.
     */
    fun next(reminder: Reminder, nowMillis: Long, zone: TimeZone): Long? {
        if (!reminder.on || reminder.days.isEmpty()) return null
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
        // Eight days: today's time may be past, and the same weekday comes back after seven.
        for (offset in 0..7) {
            val date = today.plus(offset, DateTimeUnit.DAY)
            if (date.dayOfWeek !in reminder.days) continue
            val at = LocalDateTime(date, reminder.time).toInstant(zone).toEpochMilliseconds()
            if (at > nowMillis) return at
        }
        return null
    }
}

/** A reminder the device missed (off or asleep): shown when found less than 12 hours late, else dropped (owner, 2026-10-29). */
object MissedReminder {
    const val MAX_LATE_MILLIS = 12 * 3_600_000L

    fun shouldShow(dueMillis: Long, nowMillis: Long): Boolean = nowMillis >= dueMillis && nowMillis - dueMillis < MAX_LATE_MILLIS
}

/** "Mon 19 Oct, 08:00": the Settings line (English, like the digest's dates). */
object ReminderText {
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun at(millis: Long, zone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${DAYS[t.dayOfWeek.ordinal]} ${t.day} ${MONTHS[t.month.ordinal]}, ${two(t.hour)}:${two(t.minute)}"
    }

    /** "Mon", for the weekday chips. */
    fun day(day: DayOfWeek): String = DAYS[day.ordinal]
}
