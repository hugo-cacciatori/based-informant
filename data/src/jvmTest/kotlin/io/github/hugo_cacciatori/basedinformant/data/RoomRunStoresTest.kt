package io.github.hugo_cacciatori.basedinformant.data

import io.github.hugo_cacciatori.basedinformant.core.check.CheckAttempt
import io.github.hugo_cacciatori.basedinformant.core.check.CheckEntry
import io.github.hugo_cacciatori.basedinformant.core.check.CheckOutcome
import io.github.hugo_cacciatori.basedinformant.core.check.CheckRecord
import io.github.hugo_cacciatori.basedinformant.core.check.Refusal
import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import io.github.hugo_cacciatori.basedinformant.core.digest.Source
import io.github.hugo_cacciatori.basedinformant.core.digest.Topic
import io.github.hugo_cacciatori.basedinformant.core.memory.CuratedTopic
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.run.CallRecord
import io.github.hugo_cacciatori.basedinformant.core.run.DigestStatus
import io.github.hugo_cacciatori.basedinformant.core.run.RunError
import io.github.hugo_cacciatori.basedinformant.core.run.RunSnapshot
import io.github.hugo_cacciatori.basedinformant.core.run.RunState
import io.github.hugo_cacciatori.basedinformant.core.run.StoredDigest
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The run stores on a real (temporary) database and folder; synthetic data only. */
class RoomRunStoresTest {
    private val folder = Files.createTempDirectory("bi-runs").toFile()
    private val db = buildDatabase(databaseBuilder(folder))
    private val runs = RoomRunStore(db, rawFiles(folder))

    @AfterTest
    fun close() {
        db.close()
        folder.deleteRecursively()
    }

    private val memory = CuratedTopic("club-chess-open", "Club chess open", "A synthetic open.", LocalDate(2026, 10, 1), LocalDate(2026, 10, 18), 2, 71)

    private fun snapshot(model: String = "openrouter:perplexity/sonar") = RunSnapshot(
        PromptInput(
            keywords = listOf("fusion energy", "chess"),
            interestTags = listOf("science"),
            memory = listOf(memory),
            attentionMode = AttentionMode.NICHE,
            persona = Persona.ROACH_KING,
            today = LocalDate(2026, 10, 20),
            lastDigestDate = LocalDate(2026, 10, 18),
            wideWindow = true,
            model = model,
        ),
        personaDisplayName = "The Roach King",
        emailOn = true,
        recipient = "you@example.com",
    )

    @Test
    fun aRunKeepsItsSnapshotAndStates() = runBlocking {
        val id = runs.start(snapshot(), startedAt = 100)!!
        val started = runs.get(id)!!
        assertEquals(RunState.CALLING, started.state)
        assertEquals(snapshot(), started.snapshot)
        assertEquals(listOf(id), runs.unfinished().map { it.id })

        runs.update(started.copy(state = RunState.FAILED, error = RunError.UNREADABLE, tokensBilled = 1_440, finishedAt = 200))
        val failed = runs.get(id)!!
        assertEquals(RunError.UNREADABLE, failed.error)
        assertEquals(200L, failed.finishedAt)
        assertEquals(emptyList(), runs.unfinished())
        assertNull(runs.get(999))
    }

    @Test
    fun oneRunAtATime() = runBlocking {
        val first = runs.start(snapshot(), 1)!!
        assertNull(runs.start(snapshot(), 2))
        runs.update(runs.get(first)!!.copy(state = RunState.RECEIVED))
        assertNull(runs.start(snapshot(), 3), "a received run is still active")
        runs.update(runs.get(first)!!.copy(state = RunState.DONE))
        assertNotNull(runs.start(snapshot(), 4))
        Unit
    }

    @Test
    fun recentCostsAreNewestFirstPerModel() = runBlocking {
        for ((model, cost) in listOf("a" to 10L, "b" to 99L, "a" to 20L, "a" to null, "a" to 30L)) {
            val id = runs.start(snapshot(model), 1)!!
            runs.update(runs.get(id)!!.copy(state = RunState.DONE, tokensBilled = cost))
        }
        assertEquals(listOf(30L, 20L), runs.recentCosts("a", 2))
        assertEquals(listOf(99L), runs.recentCosts("b", 5))
    }

    @Test
    fun rawAnswersAreFilesThatSurviveReopening() = runBlocking {
        assertNull(runs.raw(7))
        runs.saveRaw(7, "{\"content\": \"é ✓\"}")
        runs.saveRaw(7, "{\"content\": \"second\"}")
        assertEquals("{\"content\": \"second\"}", RoomRunStore(db, rawFiles(folder)).raw(7))
        assertTrue(folder.resolve("raw/7.json").isFile)
        assertFalse(folder.resolve("raw/7.json.tmp").exists())
    }

    @Test
    fun aDigestComesBackWhole() = runBlocking {
        val digest = Digest(
            templateVersion = 1,
            generatedAt = LocalDate(2026, 10, 20),
            opening = "Hello.",
            closing = null,
            topics = listOf(
                Topic(
                    "t1", null, null, "A title", "A summary.", null, listOf("chess"), 80, null,
                    listOf(Source("A source", "Example News", "https://example.com/a", "en", LocalDate(2026, 10, 19))),
                ),
            ),
        )
        val stored = StoredDigest(
            runId = 1, status = DigestStatus.SAVED, generatedAt = 5, subject = "Subject", html = "<p>x</p>", text = "x",
            summary = "A title", topicCount = 1, digest = digest, model = "m", attentionMode = "BALANCED", persona = "OFF",
            recipient = null, tokensBilled = 1_440,
        )
        val store = RoomDigestStore(db)
        val id = store.save(stored)
        assertEquals(stored, store.get(id))
    }

    @Test
    fun memoryIsReplacedWhole() = runBlocking {
        val store = RoomMemoryStore(db)
        val other = memory.copy(id = "other", lastFeatured = LocalDate(2026, 10, 19))
        store.save(listOf(memory, other))
        assertEquals(listOf(other, memory), store.load())
        store.save(listOf(memory))
        assertEquals(listOf(memory), store.load())
    }

    @Test
    fun callsAndChecksAreLogged() = runBlocking {
        val log = RoomCallLog(db)
        log.record(
            CallRecord(
                runId = 1, at = 2, model = "m", templateVersion = 1, keywords = listOf("chess"), tags = listOf("science"),
                attentionMode = "BALANCED", persona = "OFF", wideWindow = false, promptChars = 900, latencyMillis = 7_000,
                tokensBilled = 1_440, error = null, validTopics = 3, selectedTopics = 2, repeats = 1,
                issues = mapOf(Issue.Kind.TOPIC_MIXED_STORIES to 1),
            ),
        )
        val call = db.calls().all().single()
        assertEquals("[\"chess\"]", call.keywords)
        assertEquals("{\"TOPIC_MIXED_STORIES\":1}", call.issues)

        val refusal = Refusal(CheckEntry(CheckEntry.Kind.TAG, 0, "xqzvbt"), "random letters")
        val attempts = listOf(
            CheckAttempt("free:a", CheckAttempt.Result.FAILED, RfabError.UNAVAILABLE),
            CheckAttempt("free:b", CheckAttempt.Result.ANSWERED, tokensBilled = 0),
        )
        log.recordCheck(CheckRecord(at = 3, entries = 6, outcome = CheckOutcome.Refused(listOf(refusal), attempts)))
        log.recordCheck(CheckRecord(at = 4, entries = 6, outcome = CheckOutcome.NoAnswer(emptyList(), modelsListRead = false)))
        val (refused, none) = db.checks().all()
        assertEquals("REFUSED", refused.outcome)
        assertEquals(attempts, StoredJson.decodeAttempts(refused.attempts))
        assertEquals(listOf(refusal), StoredJson.decodeRefusals(refused.refusals))
        assertEquals("NO_ANSWER", none.outcome)
        assertFalse(none.modelsListRead)
    }
}
