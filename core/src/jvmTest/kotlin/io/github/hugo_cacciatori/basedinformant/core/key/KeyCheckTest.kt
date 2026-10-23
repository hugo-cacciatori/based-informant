package io.github.hugo_cacciatori.basedinformant.core.key

import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class KeyCheckTest {
    // Synthetic: the right shape, not a real key.
    private val key = "rfab_" + "x".repeat(24)

    /** Answers the balance call only: any chat call fails the test, since Test key must stay free. */
    private class BalanceOnly(private val result: RfabResult<Balance>) : RfabClient {
        var keyUsed: String? = null
        override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> = fail("Test key must not chat")
        override suspend fun balance() = result
        override suspend fun models(): RfabResult<ModelCatalog> = fail("Test key must not list models")
    }

    @Test
    fun shapeRulesMatchTheSetupScript() {
        assertEquals(KeyShape.OK, ApiKeyRules.check(key))
        assertEquals(KeyShape.OK, ApiKeyRules.check(" ${key.take(10)}\n${key.drop(10)} "))
        assertEquals(KeyShape.EMPTY, ApiKeyRules.check("  "))
        assertEquals(KeyShape.NOT_RFAB, ApiKeyRules.check("sk-" + "x".repeat(30)))
        assertEquals(KeyShape.BAD_CHARACTERS, ApiKeyRules.check("rfab_" + "x".repeat(20) + "!"))
        assertEquals(KeyShape.TOO_SHORT, ApiKeyRules.check("rfab_" + "x".repeat(19)))
        assertEquals("xxxx", ApiKeyRules.lastFour(key))
    }

    @Test
    fun aBadShapeIsRefusedWithoutAnyCall() = runBlocking {
        val result = KeyCheck.test("nope") { fail("no client for a bad shape") }
        assertEquals(KeyTestResult.BadShape(KeyShape.NOT_RFAB), result)
    }

    @Test
    fun aWorkingKeyShowsTheBalanceAndIsUsedTrimmed() = runBlocking {
        val client = BalanceOnly(RfabResult.Ok(Balance(tokenBalance = 120_174), raw = "{}"))
        val result = KeyCheck.test(" $key\n") { client.keyUsed = it; client }
        assertEquals(KeyTestResult.Works(120_174), result)
        assertEquals(key, client.keyUsed)
    }

    @Test
    fun errorsBecomeReasons() = runBlocking {
        suspend fun reasonFor(kind: RfabError) =
            KeyCheck.test(key) { BalanceOnly(RfabResult.Error(kind)) }
        assertEquals(KeyTestResult.Failed(NotKnownReason.KEY_REFUSED), reasonFor(RfabError.KEY_REFUSED))
        assertEquals(KeyTestResult.Failed(NotKnownReason.UNAVAILABLE), reasonFor(RfabError.UNAVAILABLE))
        assertEquals(KeyTestResult.Failed(NotKnownReason.NETWORK), reasonFor(RfabError.NETWORK))
        assertEquals(KeyTestResult.Failed(NotKnownReason.OTHER), reasonFor(RfabError.BAD_RESPONSE))
    }

    @Test
    fun noSavedKeyMeansNotKnown() = runBlocking {
        assertEquals(BalanceDisplay.NotKnown(NotKnownReason.NO_KEY), KeyCheck.balance(null))
    }
}
