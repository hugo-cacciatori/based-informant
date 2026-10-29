package io.github.hugo_cacciatori.basedinformant.core.settings

import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeRfab
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeSecretStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeSettingsStore
import io.github.hugo_cacciatori.basedinformant.core.key.BalanceDisplay
import io.github.hugo_cacciatori.basedinformant.core.key.KeyShape
import io.github.hugo_cacciatori.basedinformant.core.key.KeyTestResult
import io.github.hugo_cacciatori.basedinformant.core.key.NotKnownReason
import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules.Refusal
import io.github.hugo_cacciatori.basedinformant.core.prompt.TemplateConfig
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import kotlinx.coroutines.runBlocking
import io.github.hugo_cacciatori.basedinformant.core.reminder.Reminder
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsModelTest {
    // Synthetic keys: the right shape, not real ones.
    private val good = "rfab_" + "a".repeat(20) + "GOOD"
    private val refused = "rfab_" + "b".repeat(20) + "BAD1"
    private val rfab = FakeRfab(
        mapOf(
            good to RfabResult.Ok(Balance(tokenBalance = 120_174), raw = "{}"),
            refused to RfabResult.Error(RfabError.KEY_REFUSED, httpStatus = 401),
        ),
    )
    private val store = FakeSettingsStore()
    private val secrets = FakeSecretStore()
    private val model = SettingsModel(store, secrets, rfab::clientFor)

    @Test
    fun openWithoutAKeyShowsNotKnownAndCallsNothing() = runBlocking {
        model.open()
        assertNull(model.state.value.keyLastFour)
        assertEquals(BalanceDisplay.NotKnown(NotKnownReason.NO_KEY), model.state.value.balance)
        assertEquals(emptyList(), rfab.keysUsed)
    }

    @Test
    fun savingAKeyKeepsItTrimmedAndShowsOnlyItsLastFour() = runBlocking {
        model.open()
        assertTrue(model.saveKey("  $good\n"))
        assertEquals(good, secrets.key)
        assertEquals("GOOD", model.state.value.keyLastFour)
        assertEquals(BalanceDisplay.Known(120_174), model.state.value.balance)
        assertFalse(model.state.value.toString().contains(good))
    }

    @Test
    fun aBadShapeIsNotSaved() = runBlocking {
        secrets.key = good
        model.open()
        assertFalse(model.saveKey("sk-not-rfab"))
        assertEquals(KeyShape.NOT_RFAB, model.state.value.keyShapeRefusal)
        assertEquals(good, secrets.key)
    }

    @Test
    fun testKeyReportsTheBalanceOrTheReason() = runBlocking {
        secrets.key = refused
        model.open()
        model.testKey()
        assertEquals(KeyTestResult.Failed(NotKnownReason.KEY_REFUSED), model.state.value.keyTest)
        model.saveKey(good)
        model.testKey()
        assertEquals(KeyTestResult.Works(120_174), model.state.value.keyTest)
    }

    @Test
    fun removingTheKeyForgetsIt() = runBlocking {
        secrets.key = good
        model.open()
        model.removeKey()
        assertNull(secrets.key)
        assertNull(model.state.value.keyLastFour)
        assertEquals(BalanceDisplay.NotKnown(NotKnownReason.NO_KEY), model.state.value.balance)
    }

    @Test
    fun changesAreSavedAtOnceAndLimitsHold() = runBlocking {
        model.open()
        model.setPersona(Persona.ROACH_KING)
        model.setAttentionMode(AttentionMode.NICHE)
        assertFalse(model.setModel("gone:model"))
        assertTrue(model.setModel(TemplateConfig.DEFAULT_MODEL))
        assertEquals(
            AppSettings(attentionMode = AttentionMode.NICHE, persona = Persona.ROACH_KING),
            store.settings,
        )
        assertEquals(2, store.saves)
    }

    @Test
    fun tagsRefuseDuplicatesAndOfferUndo() = runBlocking {
        model.open()
        assertTrue(model.addTag("energy"))
        assertFalse(model.addTag("Energy"))
        assertEquals(Refusal.DUPLICATE, model.state.value.tagRefusal)
        model.deleteTag(0)
        assertEquals(emptyList(), store.settings.tags)
        model.undoTag()
        assertEquals(listOf("energy"), store.settings.tags)
    }

    @Test
    fun anUnofferedStoredModelOpensAsTheDefault() = runBlocking {
        store.settings = AppSettings(model = "gone:model")
        model.open()
        assertEquals(TemplateConfig.DEFAULT_MODEL, model.state.value.settings.model)
    }

    @Test
    fun aReminderChangeIsSavedAndReArmsTheAlarm() = runBlocking {
        val armed = mutableListOf<Reminder>()
        val withAlarm = SettingsModel(store, secrets, rfab::clientFor, onReminderChanged = { armed += it })
        withAlarm.open()
        val on = Reminder(on = true, days = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), time = LocalTime(7, 15))
        withAlarm.setReminder(on)
        assertEquals(on, store.settings.reminder)
        // Saved as set, even with no day left: nothing is preselected (owner, 2026-10-29).
        val none = on.copy(days = emptySet())
        withAlarm.setReminder(none)
        assertEquals(none, store.settings.reminder)
        assertEquals(listOf(on, none), armed)
    }
}
