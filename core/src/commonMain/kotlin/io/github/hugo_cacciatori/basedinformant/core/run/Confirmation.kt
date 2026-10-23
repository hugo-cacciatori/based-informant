@file:OptIn(ExperimentalTime::class)

package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.key.BalanceDisplay
import io.github.hugo_cacciatori.basedinformant.core.key.KeyCheck
import io.github.hugo_cacciatori.basedinformant.core.key.NotKnownReason
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** What the confirmation dialog states before any paid call (product.md section 4 step 1). */
data class Confirmation(
    /** Null when the balance could not be read: the dialog says why and cannot be confirmed. */
    val balance: Long?,
    val balanceNotKnown: NotKnownReason?,
    /** The average of the last [ConfirmationRules.AVERAGE_OF] calls with the current model; null before the first. */
    val averageCost: Long?,
    /** The balance is below twice the average cost (owner, 2026-10-20). */
    val lowBalance: Boolean,
    /** Hours since a chat call was billed on the account, when one was in the last 24 hours (section 4.2). */
    val chatBilledHoursAgo: Long?,
) {
    val canConfirm: Boolean get() = balance != null
}

object ConfirmationRules {
    const val AVERAGE_OF = 5
    const val RECENT_CHAT_HOURS = 24L

    /** [balance] comes from the free balance call made when Generate is pressed; [recentCosts] newest first. */
    fun build(balance: RfabResult<Balance>, recentCosts: List<Long>, nowMillis: Long): Confirmation {
        val average = recentCosts.take(AVERAGE_OF).takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }
        return when (balance) {
            is RfabResult.Error -> Confirmation(
                balance = null,
                balanceNotKnown = (KeyCheck.display(balance) as? BalanceDisplay.NotKnown)?.reason,
                averageCost = average,
                lowBalance = false,
                chatBilledHoursAgo = null,
            )
            is RfabResult.Ok -> {
                val tokens = balance.value.tokenBalance
                Confirmation(
                    balance = tokens,
                    balanceNotKnown = null,
                    averageCost = average,
                    lowBalance = average != null && tokens < 2 * average,
                    chatBilledHoursAgo = lastChatHoursAgo(balance.value, nowMillis),
                )
            }
        }
    }

    /**
     * A chat call shows as a `USAGE` row naming the model (checked 2026-10-19). A chat in rfab's web app looks the
     * same, so the dialog says "a chat call", never "a digest". Rows without a readable time are skipped.
     */
    private fun lastChatHoursAgo(balance: Balance, nowMillis: Long): Long? =
        balance.recentTransactions
            .filter { it.type == "USAGE" }
            .mapNotNull { row -> row.createdAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } }
            .map { (nowMillis - it).coerceAtLeast(0) / 3_600_000 }
            .filter { it < RECENT_CHAT_HOURS }
            .minOrNull()
}
