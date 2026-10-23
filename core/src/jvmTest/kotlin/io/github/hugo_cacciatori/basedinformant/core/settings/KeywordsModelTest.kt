package io.github.hugo_cacciatori.basedinformant.core.settings

import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeKeywordStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeRfab
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeSecretStore
import io.github.hugo_cacciatori.basedinformant.core.key.BalanceDisplay
import io.github.hugo_cacciatori.basedinformant.core.key.NotKnownReason
import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules.Refusal
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeywordsModelTest {
    private val store = FakeKeywordStore(listOf("solar power"))

    /** A digest takes exactly 5 keywords (owner, 2026-10-20). */
    private val five = listOf("solar power", "wind farms", "tidal power", "chess", "rail")
    private val secrets = FakeSecretStore()
    // Synthetic keys: the right shape, not real ones.
    private val good = "rfab_" + "x".repeat(24)
    private val down = "rfab_" + "y".repeat(24)
    private val rfab = FakeRfab(
        mapOf(
            good to RfabResult.Ok(Balance(tokenBalance = 120_174), raw = "{}"),
            down to RfabResult.Error(RfabError.UNAVAILABLE, httpStatus = 503),
        ),
    )
    private val model = KeywordsModel(store, secrets, rfab::clientFor)

    @Test
    fun loadsTheListAndTheKeyPresence() = runBlocking {
        store.keywords = five
        model.load()
        assertEquals(five, model.state.value.keywords)
        assertEquals(listOf(GenerateBlock.NO_KEY), model.state.value.generateBlocks)
        secrets.key = good
        model.load()
        assertEquals(emptyList(), model.state.value.generateBlocks)
    }

    @Test
    fun readsTheBalanceEachTimeTheScreenOpens() = runBlocking {
        model.load()
        assertEquals(BalanceDisplay.NotKnown(NotKnownReason.NO_KEY), model.state.value.balance)
        assertEquals(emptyList(), rfab.keysUsed)
        secrets.key = good
        model.load()
        assertEquals(BalanceDisplay.Known(120_174), model.state.value.balance)
        secrets.key = down
        model.load()
        assertEquals(BalanceDisplay.NotKnown(NotKnownReason.UNAVAILABLE), model.state.value.balance)
        assertEquals(listOf(good, down), rfab.keysUsed)
    }

    @Test
    fun addSavesAtOnceAndARefusalChangesNothing() = runBlocking {
        model.load()
        assertTrue(model.add(" wind farms "))
        assertEquals(listOf("solar power", "wind farms"), store.keywords)
        assertFalse(model.add("Solar Power"))
        assertEquals(Refusal.DUPLICATE, model.state.value.refusal)
        assertEquals(1, store.saves)
        assertTrue(model.edit(1, "tidal power"))
        assertNull(model.state.value.refusal)
        assertEquals(listOf("solar power", "tidal power"), store.keywords)
    }

    @Test
    fun deleteOffersUndoUntilTheNextEdit() = runBlocking {
        model.load()
        model.delete(0)
        assertEquals(emptyList(), store.keywords)
        assertEquals(listOf(GenerateBlock.NO_KEY, GenerateBlock.TOO_FEW_KEYWORDS), model.state.value.generateBlocks)
        model.undo()
        assertEquals(listOf("solar power"), store.keywords)
        assertNull(model.state.value.undo)

        model.delete(0)
        model.add("other")
        assertNull(model.state.value.undo)
        model.undo()
        assertEquals(listOf("other"), store.keywords)
    }

    @Test
    fun aRunBlocksGenerate() = runBlocking {
        store.keywords = five
        secrets.key = "rfab_" + "x".repeat(24)
        model.load()
        model.setRunActive(true)
        assertEquals(listOf(GenerateBlock.RUN_ACTIVE), model.state.value.generateBlocks)
    }
}
