package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.check.CheckEntry
import io.github.hugo_cacciatori.basedinformant.core.check.CheckOutcome
import io.github.hugo_cacciatori.basedinformant.core.check.Refusal
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeCallLog
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeRfab
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeRunStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeSecretStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeSettingsStore
import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.prompt.DigestMode
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.settings.AppSettings
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: Generate from the press to the runner (01-product.md sections 3 and 4). */
class GenerateModelTest {
    private val five = listOf("fusion energy", "deep-sea mining", "chess", "rust", "volcanoes")
    private val calls = FakeCallLog()
    private val settings = FakeSettingsStore(AppSettings(tags = listOf("science"), persona = Persona.ROACH_KING))
    private val rfab = FakeRfab(mapOf("rfab_key" to RfabResult.Ok(Balance(tokenBalance = 50_000), "{}")))
    private val digests = TopicDigests(listOf("Stellarator research", "Deep-sea mining rules"))
    private var check: CheckOutcome = CheckOutcome.Passed(emptyList())
    private val started = mutableListOf<RunJob>()
    private var runnerFree = true

    /** The fake digest store, with fixed topic tags and one digest stored at 500. */
    private class TopicDigests(var tags: List<String>) : DigestStore, TopicStore {
        override suspend fun save(digest: StoredDigest) = 1L
        override suspend fun topicTags() = tags
        override suspend fun lastGeneratedAt() = 500L
        override suspend fun delete(tags: Collection<String>): List<SavedTopic> {
            val keys = tags.map { it.lowercase() }.toSet()
            val gone = this.tags.filter { it.lowercase() in keys }
            this.tags = this.tags - gone.toSet()
            return gone.map { SavedTopic(it, 1L) }
        }
        override suspend fun restore(topics: List<SavedTopic>) { tags = tags + topics.map { it.tag } }
    }

    private val model = GenerateModel(
        keywords = { five },
        settings = settings,
        secrets = FakeSecretStore("rfab_key"),
        clientFor = rfab::clientFor,
        inputCheck = { _, _, _ -> check },
        runs = FakeRunStore(),
        digests = digests,
        topicStore = digests,
        calls = calls,
        start = { job -> runnerFree.also { if (it) started += job } },
        personaName = { if (it == Persona.OFF) null else "Roach King" },
        now = { 1_000L },
        today = { LocalDate(2026, 10, 29) },
    )

    @Test
    fun aPassedCheckLeadsToTheConfirmationWithTheSnapshot() = runBlocking {
        model.generate()
        val confirm = assertIs<GenerateStep.Confirm>(model.state.value.step)
        assertEquals(50_000, confirm.confirmation.balance)
        assertEquals(500L, confirm.lastDigestAt)
        val snapshot = confirm.snapshot
        assertEquals(five, snapshot.prompt.keywords)
        assertEquals(listOf("science"), snapshot.prompt.interestTags)
        assertEquals("Roach King", snapshot.personaDisplayName)
        assertEquals(listOf("Stellarator research", "Deep-sea mining rules"), snapshot.prompt.knownTopicTags)
        assertEquals(1, calls.checks.size, "the check is recorded")
        assertTrue(started.isEmpty(), "nothing runs before confirm")
    }

    @Test
    fun confirmStartsTheRunOnceOnly() = runBlocking {
        model.generate()
        assertTrue(model.confirm())
        assertFalse(model.confirm(), "a repeat tap is ignored")
        assertIs<RunJob.Generate>(started.single())
        assertEquals(GenerateStep.Idle, model.state.value.step)
    }

    @Test
    fun aRefusedCheckStopsBeforeTheBalance() = runBlocking {
        val refusal = Refusal(CheckEntry(CheckEntry.Kind.KEYWORD, 2, "chess"), "misspelled")
        check = CheckOutcome.Refused(listOf(refusal), emptyList())
        model.generate()
        assertEquals(GenerateStep.Refused(listOf(refusal)), model.state.value.step)
        assertTrue(rfab.keysUsed.isEmpty(), "no balance read")
        model.dismiss()
        assertEquals(GenerateStep.Idle, model.state.value.step)
    }

    @Test
    fun noFreeModelLetsTheRunGoOnSilently() = runBlocking {
        check = CheckOutcome.NoAnswer(emptyList())
        model.generate()
        assertIs<GenerateStep.Confirm>(model.state.value.step)
        Unit
    }

    @Test
    fun anUnreadableBalanceCannotBeConfirmed() = runBlocking {
        val noBalance = FakeRfab(mapOf("rfab_key" to RfabResult.Error(io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError.NETWORK)))
        val offline = GenerateModel(
            { five }, settings, FakeSecretStore("rfab_key"), noBalance::clientFor, { _, _, _ -> check }, FakeRunStore(), digests, digests,
            calls, { started += it; true }, { null }, { 1_000L }, { LocalDate(2026, 10, 29) },
        )
        offline.generate()
        val confirm = assertIs<GenerateStep.Confirm>(offline.state.value.step)
        assertNull(confirm.confirmation.balance)
        assertFalse(offline.confirm())
        assertTrue(started.isEmpty())
    }

    @Test
    fun aBusyRunnerSendsNothing() = runBlocking {
        runnerFree = false
        model.generate()
        assertFalse(model.confirm())
        assertTrue(started.isEmpty())
    }

    @Test
    fun topicModeSendsTheChosenTagsWithoutTheInputCheck() = runBlocking {
        model.loadTopics()
        model.setMode(DigestMode.TOPICS)
        model.toggleTopic("Deep-sea mining rules")
        model.generate()
        val prompt = assertIs<GenerateStep.Confirm>(model.state.value.step).snapshot.prompt
        assertEquals(DigestMode.TOPICS, prompt.mode)
        assertEquals(listOf("Deep-sea mining rules"), prompt.keywords)
        assertTrue(calls.checks.isEmpty(), "the keyword rules apply to keyword mode only")
    }

    @Test
    fun topicModeWorksWithoutSavedTopicsAndTakesAtMostFive() = runBlocking {
        // No saved topic: topic mode gives an all-trending digest (owner, 2026-10-29).
        digests.tags = emptyList()
        model.loadTopics()
        model.setMode(DigestMode.TOPICS)
        assertEquals(DigestMode.TOPICS, model.state.value.mode)

        digests.tags = (1..7).map { "Topic $it" }
        model.loadTopics()
        model.setMode(DigestMode.TOPICS)
        digests.tags.forEach(model::toggleTopic)
        assertEquals((1..5).map { "Topic $it" }, model.state.value.chosen)
        model.toggleTopic("Topic 2")
        assertEquals(listOf(1, 3, 4, 5).map { "Topic $it" }, model.state.value.chosen)

        digests.tags = listOf("Topic 3")
        model.loadTopics()
        assertEquals(listOf("Topic 3"), model.state.value.chosen, "a tag no longer stored is dropped")
    }

    @Test
    fun deletedTopicsAreUnpickedAndUndoBringsThemBack() = runBlocking {
        digests.tags = listOf("Topic 1", "Topic 2", "Topic 3")
        model.loadTopics()
        model.toggleTopic("Topic 2")
        model.deleteTopics(listOf("topic 2", "Topic 3"))
        assertEquals(listOf("Topic 1"), model.state.value.topics)
        assertEquals(emptyList(), model.state.value.chosen)
        assertEquals(listOf("Topic 2", "Topic 3"), model.state.value.topicUndo?.map { it.tag })
        model.undoTopics()
        assertEquals(listOf("Topic 1", "Topic 2", "Topic 3"), model.state.value.topics)
        assertEquals(null, model.state.value.topicUndo)
    }
}
