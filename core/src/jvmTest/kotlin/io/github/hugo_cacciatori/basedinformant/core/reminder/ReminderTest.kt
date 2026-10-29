@file:OptIn(ExperimentalTime::class)

package io.github.hugo_cacciatori.basedinformant.core.reminder

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/** Offline: the reminder's next time and missed rule (07-testing.md, NextReminderCalculator). */
class ReminderTest {
    private val paris = TimeZone.of("Europe/Paris")
    private val mondayAt8 = Reminder(on = true, days = setOf(DayOfWeek.MONDAY))

    private fun at(text: String, zone: TimeZone = paris) = LocalDateTime.parse(text).toInstant(zone).toEpochMilliseconds()

    private fun next(reminder: Reminder, now: String, zone: TimeZone = paris) =
        NextReminderCalculator.next(reminder, at(now, zone), zone)?.let { ReminderText.at(it, zone) }

    @Test
    fun offOrWithoutDaysHasNoNextTime() {
        assertNull(NextReminderCalculator.next(mondayAt8.copy(on = false), at("2026-10-29T10:00"), paris))
        assertNull(NextReminderCalculator.next(Reminder(on = true), at("2026-10-29T10:00"), paris), "no day is preselected")
    }

    @Test
    fun theNextChosenWeekdayAtTheTime() {
        // 29 Oct 2026 is a Thursday.
        assertEquals("Mon 2 Nov, 08:00", next(mondayAt8, "2026-10-29T10:00"))
        val several = Reminder(on = true, days = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY), time = LocalTime(19, 30))
        assertEquals("Thu 29 Oct, 19:30", next(several, "2026-10-29T10:00"))
        assertEquals("Sat 31 Oct, 19:30", next(several, "2026-10-29T19:30"), "now exactly at a reminder: the next one")
        assertEquals("Mon 9 Nov, 08:00", next(mondayAt8, "2026-11-02T08:00"), "the same weekday a week later")
    }

    @Test
    fun monthAndYearBoundaries() {
        assertEquals("Mon 4 Jan, 08:00", next(mondayAt8, "2026-12-29T09:00"))
        val friday = Reminder(on = true, days = setOf(DayOfWeek.FRIDAY))
        assertEquals("Fri 2 Oct, 08:00", next(friday, "2026-09-30T12:00"))
    }

    @Test
    fun dstChangesKeepTheLocalTime() {
        // Europe/Paris goes back on Sun 25 Oct 2026 and forward on Sun 28 Mar 2027.
        val sunday = Reminder(on = true, days = setOf(DayOfWeek.SUNDAY))
        assertEquals("Sun 25 Oct, 08:00", next(sunday, "2026-10-24T09:00"))
        assertEquals("Sun 28 Mar, 08:00", next(sunday, "2027-03-27T09:00"))
        // A week across the change is not 7 × 24 h.
        val before = NextReminderCalculator.next(sunday, at("2026-10-17T09:00"), paris)!!
        val after = NextReminderCalculator.next(sunday, before, paris)!!
        assertEquals(7 * 24 * 3_600_000L + 3_600_000L, after - before)
        // 02:30 does not exist on 28 Mar 2027 in Paris: it moves forward by the gap.
        val early = Reminder(on = true, days = setOf(DayOfWeek.SUNDAY), time = LocalTime(2, 30))
        assertEquals("Sun 28 Mar, 03:30", next(early, "2027-03-27T09:00"))
    }

    @Test
    fun aTimeZoneChangeKeepsTheLocalTimeOfTheNewZone() {
        val tokyo = TimeZone.of("Asia/Tokyo")
        val now = at("2026-10-29T10:00")
        assertEquals("Mon 2 Nov, 08:00", ReminderText.at(NextReminderCalculator.next(mondayAt8, now, tokyo)!!, tokyo))
    }

    @Test
    fun aMissedReminderShowsOnlyWhenLessThan12HoursLate() {
        val due = at("2026-11-02T08:00")
        assertTrue(MissedReminder.shouldShow(due, due))
        assertTrue(MissedReminder.shouldShow(due, at("2026-11-02T19:59")))
        assertFalse(MissedReminder.shouldShow(due, at("2026-11-02T20:00")))
        assertFalse(MissedReminder.shouldShow(due, at("2026-11-02T07:59")), "not due yet")
    }
}
