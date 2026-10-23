package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeCallLog
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeDigestStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeMemoryStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeRunStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.OneAnswerRfab
import io.github.hugo_cacciatori.basedinformant.core.fakes.allAlive
import io.github.hugo_cacciatori.basedinformant.core.memory.CuratedTopic
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DigestRunTest {
    private val runs = FakeRunStore()
    private val digests = FakeDigestStore()
    private val memory = FakeMemoryStore()
    private val calls = FakeCallLog()
    private var clock = 1_000L

    private val seabedMemory = CuratedTopic(
        "seabed-mining-talks", "Seabed mining talks resume", "Delegates met again.",
        LocalDate(2026, 10, 1), LocalDate(2026, 10, 10), 2, 66,
    )

    private val snapshot = RunSnapshot(
        prompt = PromptInput(
            keywords = listOf("fusion energy", "deep-sea mining", "chess"),
            interestTags = listOf("science"),
            memory = listOf(seabedMemory),
            attentionMode = AttentionMode.BALANCED,
            persona = Persona.OFF,
            today = LocalDate(2026, 10, 18),
            lastDigestDate = null,
        ),
        personaDisplayName = null,
        emailOn = false,
        recipient = null,
    )

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/$name")) { name }.readText()

    /** rfab's response body around [content], as the client receives it. */
    private fun body(content: String?, tokens: Long = 1_440) = buildJsonObject {
        put("success", JsonPrimitive(true))
        put("model", JsonPrimitive("openrouter:perplexity/sonar"))
        content?.let { put("content", JsonPrimitive(it)) }
        put("tokensBilled", JsonPrimitive(tokens))
    }.toString()

    private fun ok(content: String?): RfabResult<ChatResponse> {
        val raw = body(content)
        return RfabResult.Ok(Json { ignoreUnknownKeys = true }.decodeFromString(ChatResponse.serializer(), raw), raw)
    }

    private fun run(answer: RfabResult<ChatResponse>) = DigestRun(runs, digests, memory, calls, OneAnswerRfab(answer), allAlive) {
        clock += 500
        clock
    }

    @Test
    fun aReadableAnswerIsStoredSavedAndRemembered() = runBlocking {
        val outcome = run(ok(fixture("good.json"))).execute(snapshot)
        val stored = assertIs<RunOutcome.Stored>(outcome)
        assertEquals(2, stored.topicCount)
        assertEquals(1_440, stored.tokensBilled)

        val digest = digests.saved.single()
        assertEquals(DigestStatus.SAVED, digest.status)
        assertEquals("News digest · 18 Oct 2026 · 2 topics", digest.subject)
        assertTrue(digest.html.startsWith("<!DOCTYPE html>"))
        assertEquals(digest.digest.topics.size, digest.topicCount)

        val record = runs.runs.getValue(stored.runId)
        assertEquals(RunState.DONE, record.state)
        assertEquals(stored.digestId, record.digestId)
        assertTrue(runs.raws.getValue(stored.runId).contains("stellarator"), "the raw answer is kept")
        // Email off: the delivered topics join the curated memory at once.
        assertTrue(memory.entries.any { it.id == "stellarator-record-run" })
    }

    @Test
    fun withEmailOnTheDigestWaitsForTheSendAndTheMemoryWaitsForDelivery() = runBlocking {
        run(ok(fixture("good.json"))).execute(snapshot.copy(emailOn = true, recipient = "reader@example.com"))
        assertEquals(DigestStatus.GENERATED, digests.saved.single().status)
        assertEquals("reader@example.com", digests.saved.single().recipient)
        assertEquals(emptyList(), memory.entries)
    }

    @Test
    fun theCallHistoryRecordsParametersAndResults() = runBlocking {
        run(ok(fixture("good.json"))).execute(snapshot)
        val call = calls.calls.single()
        assertEquals(listOf("fusion energy", "deep-sea mining", "chess"), call.keywords)
        assertEquals(listOf("science"), call.tags)
        assertEquals("balanced", call.attentionMode)
        assertEquals("off", call.persona)
        assertEquals(1_440, call.tokensBilled)
        assertEquals(3, call.validTopics)
        assertEquals(2, call.selectedTopics)
        // The seabed topic continues one already delivered.
        assertEquals(1, call.repeats)
        assertNull(call.error)
        assertEquals(500, call.latencyMillis)
        assertTrue(call.promptChars > 1_000)
    }

    @Test
    fun anEmptyDigestStoresNothingAndOffersTheWiderWindowOnce() = runBlocking {
        val outcome = assertIs<RunOutcome.Empty>(run(ok(fixture("empty.json"))).execute(snapshot))
        assertTrue(outcome.canWiden)
        assertEquals(emptyList(), digests.saved)
        assertEquals(RunError.EMPTY, runs.runs.getValue(outcome.runId).error)

        val wide = snapshot.copy(prompt = snapshot.prompt.copy(wideWindow = true))
        val second = assertIs<RunOutcome.Empty>(run(ok(fixture("empty.json"))).execute(wide))
        assertEquals(false, second.canWiden)
        assertEquals(true, calls.calls.last().wideWindow)
    }

    @Test
    fun theWideWindowSearchesThirtyDays() {
        val window = snapshot.prompt.copy(wideWindow = true).window()
        assertEquals(LocalDate(2026, 9, 18), window.start)
        assertEquals(LocalDate(2026, 10, 18), window.end)
    }

    @Test
    fun anUnreadableAnswerIsKeptAndReadAgainProcessesItForFree() = runBlocking {
        val outcome = assertIs<RunOutcome.Unreadable>(run(ok("Sorry, I cannot help with that.")).execute(snapshot))
        assertEquals(RunError.UNREADABLE, runs.runs.getValue(outcome.runId).error)

        // Simulates an update whose parser can read the kept answer: the raw file now holds a readable one.
        runs.raws[outcome.runId] = body(fixture("good.json"))
        val noPaidCall = DigestRun(runs, digests, memory, calls, OneAnswerRfab(ok(null)), allAlive) { clock }
        assertIs<RunOutcome.Stored>(noPaidCall.readAgain(outcome.runId))
        assertEquals(1, calls.calls.size, "Read again is not a paid call")
    }

    @Test
    fun readAgainOnlyWorksOnAnUnreadableRun() = runBlocking {
        val stored = assertIs<RunOutcome.Stored>(run(ok(fixture("good.json"))).execute(snapshot))
        assertNull(run(ok(null)).readAgain(stored.runId))
    }

    @Test
    fun refusalsBillNothingAndAreNeverResent() = runBlocking {
        val cases = mapOf(
            RfabError.KEY_REFUSED to RunError.KEY_REFUSED,
            RfabError.INSUFFICIENT_TOKENS to RunError.INSUFFICIENT_TOKENS,
            RfabError.UNAVAILABLE to RunError.UNAVAILABLE,
        )
        for ((kind, expected) in cases) {
            val client = OneAnswerRfab(RfabResult.Error(kind, 401))
            val outcome = DigestRun(runs, digests, memory, calls, client, allAlive) { clock }.execute(snapshot)
            assertEquals(expected, assertIs<RunOutcome.Refused>(outcome).error)
            assertEquals(1, client.chats)
        }
        assertEquals(emptyList(), digests.saved)
    }

    @Test
    fun aLostAnswerIsInterruptedAndABilledFailureSaysSo() = runBlocking {
        assertIs<RunOutcome.Interrupted>(run(RfabResult.Error(RfabError.NETWORK)).execute(snapshot))
        assertIs<RunOutcome.Interrupted>(run(RfabResult.Error(RfabError.FAILED_MAYBE_BILLED, 502)).execute(snapshot))
        val billed = assertIs<RunOutcome.FailedBilled>(run(RfabResult.Error(RfabError.FAILED_BILLED, 500, tokensBilled = 1_440)).execute(snapshot))
        assertEquals(1_440, billed.tokensBilled)
        assertEquals(1_440, runs.runs.getValue(billed.runId).tokensBilled)
    }

    @Test
    fun anUnexpectedBodyIsKeptAsUnreadable() = runBlocking {
        val outcome = run(RfabResult.Error(RfabError.BAD_RESPONSE, 200, raw = "<html>oops</html>")).execute(snapshot)
        val unreadable = assertIs<RunOutcome.Unreadable>(outcome)
        assertEquals("<html>oops</html>", runs.raws[unreadable.runId])
    }

    @Test
    fun oneRunAtATime() = runBlocking {
        runs.start(snapshot, 0)
        val client = OneAnswerRfab(ok(fixture("good.json")))
        assertEquals(RunOutcome.Busy, DigestRun(runs, digests, memory, calls, client, allAlive) { clock }.execute(snapshot))
        assertEquals(0, client.chats)
    }

    @Test
    fun anInputTheBuilderRefusesSendsNothing() = runBlocking {
        val tooMany = snapshot.copy(prompt = snapshot.prompt.copy(keywords = List(6) { "k$it" }))
        val client = OneAnswerRfab(ok(fixture("good.json")))
        assertEquals(RunOutcome.NotBuilt, DigestRun(runs, digests, memory, calls, client, allAlive) { clock }.execute(tooMany))
        assertEquals(0, client.chats)
        assertEquals(emptyMap(), runs.runs)
    }

    @Test
    fun recoveryNeverResendsAndResumesAReceivedAnswer() = runBlocking {
        val calling = runs.start(snapshot, 0)!!
        runs.update(runs.runs.getValue(calling).copy(state = RunState.INTERRUPTED))
        val received = runs.start(snapshot, 0)!!
        runs.saveRaw(received, body(fixture("good.json")))
        runs.update(runs.runs.getValue(received).copy(state = RunState.RECEIVED, tokensBilled = 1_440))
        runs.update(runs.runs.getValue(calling).copy(state = RunState.CALLING))

        val client = OneAnswerRfab(ok(null))
        val outcomes = DigestRun(runs, digests, memory, calls, client, allAlive) { clock }.recover()
        assertEquals(0, client.chats)
        assertTrue(outcomes.any { it is RunOutcome.Interrupted })
        assertTrue(outcomes.any { it is RunOutcome.Stored })
        assertEquals(RunState.INTERRUPTED, runs.runs.getValue(calling).state)
        assertEquals(RunState.DONE, runs.runs.getValue(received).state)
    }

    @Test
    fun theSummaryShowsTheTopThreeByScore() {
        val digest = Json { ignoreUnknownKeys = true }.decodeFromString(
            io.github.hugo_cacciatori.basedinformant.core.digest.Digest.serializer(),
            fixture("good.json"),
        )
        assertEquals(
            "Test stellarator holds plasma for a record 40 minutes · Seabed authority schedules a moratorium vote · " +
                "A regional chess open draws a record field",
            DigestSummary.of(digest),
        )
        val five = digest.copy(topics = digest.topics + digest.topics.take(2))
        assertTrue(DigestSummary.of(five).endsWith(" +2 more"))
    }
}
