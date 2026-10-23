package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * The period the AI searches (ai-call.md section 3, decided 2026-10-18): since the last digest generated on
 * this device, at most [MAX_DAYS] days back; [MAX_DAYS] days when there is none. Both ends are inclusive.
 */
data class RecencyWindow(val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = date in start..end

    companion object {
        const val MAX_DAYS = 7

        /** The wider window offered after an empty digest (product.md section 4 step 6, 30 days [PROPOSED]). */
        const val WIDE_DAYS = 30

        /** The last [WIDE_DAYS] days, whatever the last digest: a second chance after an empty one. */
        fun wide(today: LocalDate): RecencyWindow = RecencyWindow(today.minus(DatePeriod(days = WIDE_DAYS)), today)

        fun of(today: LocalDate, lastDigestDate: LocalDate?): RecencyWindow {
            val earliest = today.minus(DatePeriod(days = MAX_DAYS))
            val start = when {
                lastDigestDate == null -> earliest
                lastDigestDate > today -> today
                lastDigestDate < earliest -> earliest
                else -> lastDigestDate
            }
            return RecencyWindow(start, today)
        }
    }
}
