package io.github.hugo_cacciatori.basedinformant.core.key

import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult

/**
 * Where the rfab key is kept (05-security.md section 3): the Android Keystore or Windows DPAPI, never DataStore,
 * Room, logs or the repo. Implementations never log the key.
 */
interface SecretStore {
    suspend fun rfabKey(): String?
    suspend fun setRfabKey(key: String)
    suspend fun removeRfabKey()
}

/** The same shape rules as `scripts/set-rfab-key`. */
object ApiKeyRules {
    const val PREFIX = "rfab_"
    const val MIN_LENGTH = 25
    private val allowed = Regex("[A-Za-z0-9_-]*")

    /** A pasted key often carries spaces or line breaks; none belong to it. */
    fun normalize(pasted: String): String = pasted.filterNot { it.isWhitespace() }

    fun check(pasted: String): KeyShape {
        val key = normalize(pasted)
        return when {
            key.isEmpty() -> KeyShape.EMPTY
            !key.startsWith(PREFIX) -> KeyShape.NOT_RFAB
            !allowed.matches(key.removePrefix(PREFIX)) -> KeyShape.BAD_CHARACTERS
            key.length < MIN_LENGTH -> KeyShape.TOO_SHORT
            else -> KeyShape.OK
        }
    }

    /** All the app ever shows of a saved key: its last 4 characters. */
    fun lastFour(key: String): String = key.takeLast(4)
}

enum class KeyShape { OK, EMPTY, NOT_RFAB, BAD_CHARACTERS, TOO_SHORT }

/** The token balance as Settings shows it (01-product.md section 3). */
sealed interface BalanceDisplay {
    data class Known(val tokens: Long) : BalanceDisplay
    data class NotKnown(val reason: NotKnownReason) : BalanceDisplay
}

enum class NotKnownReason { NO_KEY, KEY_REFUSED, UNAVAILABLE, NETWORK, OTHER }

/** The outcome of **Test key**: the balance when the key works, or why it doesn't. */
sealed interface KeyTestResult {
    data class Works(val tokens: Long) : KeyTestResult
    data class BadShape(val shape: KeyShape) : KeyTestResult
    data class Failed(val reason: NotKnownReason) : KeyTestResult
}

object KeyCheck {
    /**
     * Test key: the shape check first (free, offline), then the free balance call through a client made with
     * the pasted key by [clientFor]. Never calls anything that spends tokens.
     */
    suspend fun test(pasted: String, clientFor: (String) -> RfabClient): KeyTestResult {
        val shape = ApiKeyRules.check(pasted)
        if (shape != KeyShape.OK) return KeyTestResult.BadShape(shape)
        return when (val display = balance(clientFor(ApiKeyRules.normalize(pasted)))) {
            is BalanceDisplay.Known -> KeyTestResult.Works(display.tokens)
            is BalanceDisplay.NotKnown -> KeyTestResult.Failed(display.reason)
        }
    }

    /** Reads the balance with the free call; [client] is null when no key is saved. */
    suspend fun balance(client: RfabClient?): BalanceDisplay {
        if (client == null) return BalanceDisplay.NotKnown(NotKnownReason.NO_KEY)
        return display(client.balance())
    }

    fun display(result: RfabResult<Balance>): BalanceDisplay = when (result) {
        is RfabResult.Ok -> BalanceDisplay.Known(result.value.tokenBalance)
        is RfabResult.Error -> BalanceDisplay.NotKnown(
            when (result.kind) {
                RfabError.KEY_REFUSED -> NotKnownReason.KEY_REFUSED
                RfabError.UNAVAILABLE -> NotKnownReason.UNAVAILABLE
                RfabError.NETWORK -> NotKnownReason.NETWORK
                else -> NotKnownReason.OTHER
            },
        )
    }
}
