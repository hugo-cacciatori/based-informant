package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.key.NotKnownReason
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.rfab.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmationTest {
    // 2026-10-20T12:00:00Z
    private val now = 1_792_497_600_000L

    private fun balance(tokens: Long, vararg rows: Transaction) = RfabResult.Ok(Balance(tokens, recentTransactions = rows.toList()), "{}")

    @Test
    fun theAverageCostIsOverTheLastFiveCalls() {
        val c = ConfirmationRules.build(balance(120_174), listOf(1_000, 2_000, 3_000, 4_000, 5_000, 99_000), now)
        assertEquals(3_000, c.averageCost)
        assertNull(ConfirmationRules.build(balance(120_174), emptyList(), now).averageCost)
    }

    @Test
    fun lowBalanceBelowTwiceTheAverage() {
        assertTrue(ConfirmationRules.build(balance(2_879), listOf(1_440), now).lowBalance)
        assertFalse(ConfirmationRules.build(balance(2_880), listOf(1_440), now).lowBalance)
        assertFalse(ConfirmationRules.build(balance(10), emptyList(), now).lowBalance, "no average yet, no warning")
    }

    @Test
    fun aChatCallBilledInTheLastDayIsReported() {
        val c = ConfirmationRules.build(
            balance(
                120_174,
                Transaction("a", "USAGE", -1_440, "openrouter:perplexity/sonar", "2026-10-20T10:00:00Z"),
                Transaction("b", "USAGE", -1_440, "openrouter:perplexity/sonar", "2026-10-18T10:00:00Z"),
                Transaction("c", "DAILY_CLAIM", 25_000, null, "2026-10-20T11:30:00Z"),
            ),
            emptyList(),
            now,
        )
        assertEquals(2, c.chatBilledHoursAgo)
        val old = ConfirmationRules.build(
            balance(1, Transaction("b", "USAGE", -1_440, null, "2026-10-18T10:00:00Z"), Transaction("x", "USAGE", -1, null, "not a date")),
            emptyList(),
            now,
        )
        assertNull(old.chatBilledHoursAgo)
    }

    @Test
    fun thisDevicesOwnDigestAndFreeCallsAreNoWarning() {
        // now is 2026-10-20T12:00Z; this device stored a digest at 11:50 after a search billed at 11:48.
        val own = 1_792_497_600_000L - 10 * 60_000L
        val c = ConfirmationRules.build(
            balance(
                120_174,
                Transaction("a", "USAGE", -1_440, "openrouter:perplexity/sonar", "2026-10-20T11:48:00Z"),
                Transaction("f", "USAGE", 0, "openrouter:minimax/minimax-m3", "2026-10-20T11:55:00Z"),
            ),
            emptyList(),
            now,
            ownDigestAt = own,
        )
        assertNull(c.chatBilledHoursAgo)
    }

    @Test
    fun anUnreadableBalanceCannotBeConfirmed() {
        val c = ConfirmationRules.build(RfabResult.Error(RfabError.NETWORK), listOf(1_440), now)
        assertFalse(c.canConfirm)
        assertEquals(NotKnownReason.NETWORK, c.balanceNotKnown)
        assertTrue(ConfirmationRules.build(balance(5), emptyList(), now).canConfirm)
    }
}
