package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkResult
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkStatus
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeCallLog
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeDigestStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeRunStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.ScriptedRfab
import io.github.hugo_cacciatori.basedinformant.core.fakes.allAlive
import io.github.hugo_cacciatori.basedinformant.core.fakes.okResponse
import io.github.hugo_cacciatori.basedinformant.core.fakes.responseBody
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.prompt.DigestMode
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.render.DigestViews
import io.github.hugo_cacciatori.basedinformant.core.render.RenderMeta
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.search.SearchAnswer
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: the run of template v2 (02-ai-call.md section 12) on a synthetic search answer and scripted free models. */
class DigestRunTest {
    private val runs = FakeRunStore()
    private val digests = FakeDigestStore()
    private val calls = FakeCallLog()
    private var clock = 1_000L

    private val snapshot = RunSnapshot(
        prompt = PromptInput(
            keywords = listOf("fusion energy", "deep-sea mining", "chess"),
            interestTags = listOf("science"),
            attentionMode = AttentionMode.BALANCED,
            persona = Persona.OFF,
            today = LocalDate(2026, 10, 18),
            knownTopicTags = listOf("Deep-sea mining rules"),
        ),
        personaDisplayName = null,
    )

    private val search = fixture("search-synthetic.json")

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/$name")) { name }.readText()

    /** A writer answer on the synthetic search: stellarator (story 1) and the seabed vote (story 2). */
    private val written = """{"template_version": 2, "opening": "Hello.", "closing": "Bye.", "subject": "Plasma record and a seabed vote",
        "card_summary": "A plasma record and a mining vote.", "topics": [
        {"story": 1, "topic_tag": "Stellarator research", "title": "Stellarator holds plasma for 40 minutes",
         "summary": "A research stellarator held a stable plasma for 40 minutes. It cost 2 billion.", "detail": "The lab plans a one-hour campaign.",
         "keywords": ["fusion energy"], "relevance": 50, "sources": [1, 2]},
        {"story": 2, "topic_tag": "Deep-sea mining rules", "title": "Seabed body sets a moratorium vote",
         "summary": "The council agreed to vote on a 10-year moratorium.", "detail": null,
         "keywords": ["deep-sea mining"], "relevance": 45, "sources": [3]}]}"""

    private val factCheck = """{"cards": [{"card": 1, "unsupported": [{"s": 2, "why": "no cost"}]}, {"card": 2}]}"""

    /** MiniMax writes and checks; every other free model is at its limit. */
    private fun writes(model: String, request: ChatRequest): String? = when {
        model != "openrouter:minimax/minimax-m3" -> null
        "SEARCH_REPORT" in request.messages.single().content -> written
        "CHECK_DATA" in request.messages.single().content -> factCheck
        else -> null
    }

    /** The pages' text, so the fact check may delete: every link alive with a short text. */
    private val withPages = LinkChecker { LinkResult(LinkStatus.ALIVE, pageText = "The stellarator held plasma for 40 minutes.") }

    private fun run(
        paid: RfabResult<ChatResponse> = okResponse(search, 1_440),
        free: (String, ChatRequest) -> String? = ::writes,
        links: LinkChecker = withPages,
    ) = ScriptedRfab(paid, free).let { client -> client to DigestRun(runs, digests, calls, client, links) { clock += 500; clock } }

    @Test
    fun aSearchIsWrittenCheckedAndStored() = runBlocking {
        val (client, digestRun) = run()
        val stored = assertIs<RunOutcome.Stored>(digestRun.execute(snapshot))
        assertEquals(2, stored.topicCount)
        assertEquals(1_440, stored.tokensBilled)
        assertEquals(1, client.paidCalls)

        val saved = digests.saved.single()
        assertEquals("Plasma record and a seabed vote", saved.subject)
        assertEquals("A plasma record and a mining vote.", saved.summary)
        val first = saved.digest.topics.first()
        assertEquals(2, saved.digest.templateVersion)
        assertEquals("Stellarator research", first.topicTag)
        assertEquals("A research stellarator held a stable plasma for 40 minutes.", first.summary, "the fact check deleted the cost")
        assertEquals(78, first.score, "relevance 50 + the search's attention 28")
        assertEquals(listOf("https://science.example.com/2026/10/stellarator-record", "https://sciences.example.org/stellarator-record"), first.sources.map { it.url })

        val record = runs.runs.getValue(stored.runId)
        assertEquals(RunState.DONE, record.state)
        assertTrue(runs.raws.getValue(stored.runId).contains("stellarator"), "the raw answer is kept")
        assertTrue(SearchAnswer.decode(runs.searches.getValue(stored.runId)).stories.all { s -> s.sources.all { it.pageText != null } })
    }

    @Test
    fun theWriterGetsTheKnownTagsAndNoPersonaNotesWithPersonaOff() = runBlocking {
        var writerRequest: ChatRequest? = null
        val (_, digestRun) = run(free = { model, request ->
            if ("SEARCH_REPORT" in request.messages.single().content) writerRequest = request
            writes(model, request)
        })
        digestRun.execute(snapshot)
        val message = writerRequest!!.messages.single().content
        assertTrue("\"known_topic_tags\":[\"Deep-sea mining rules\"]" in message)
        assertTrue("electricity bill" !in message, "persona notes go only to a persona voice")
    }

    @Test
    fun theCallHistoryRecordsParametersAndWhatTheChecksFound() = runBlocking {
        run().second.execute(snapshot)
        val call = calls.calls.single()
        assertEquals(listOf("fusion energy", "deep-sea mining", "chess"), call.keywords)
        assertEquals(2, call.templateVersion)
        assertEquals(1_440, call.tokensBilled)
        assertEquals(2, call.validTopics)
        assertEquals(2, call.selectedTopics)
        assertEquals(1, call.issues[Issue.Kind.FACT_UNSUPPORTED])
        assertNull(call.error)
        assertEquals(500, call.latencyMillis)
    }

    @Test
    fun withNoFreeWriterTheSearchIsKeptAndWriteAgainWritesItForFree() = runBlocking {
        val (_, down) = run(free = { _, _ -> null })
        val outcome = assertIs<RunOutcome.NotWritten>(down.execute(snapshot))
        assertEquals(RunError.WRITE_FAILED, runs.runs.getValue(outcome.runId).error)
        assertEquals(emptyList(), digests.saved)

        val (client, back) = run(paid = okResponse(null))
        assertIs<RunOutcome.Stored>(back.writeAgain(outcome.runId))
        assertEquals(0, client.paidCalls, "Write again never sends the paid search")
        assertNull(back.writeAgain(outcome.runId), "only a run left unwritten")
    }

    @Test
    fun withNoFactCheckTheCardsStayAsWritten() = runBlocking {
        val (_, digestRun) = run(free = { model, request -> if ("CHECK_DATA" in request.messages.single().content) null else writes(model, request) })
        assertIs<RunOutcome.Stored>(digestRun.execute(snapshot))
        assertTrue("2 billion" in digests.saved.single().digest.topics.first().summary)
        assertEquals(1, calls.calls.single().issues[Issue.Kind.FACT_NOT_CHECKED])
    }

    @Test
    fun theFactCheckRunsOnlyOnMiniMaxThenKimi() = runBlocking {
        val (client, digestRun) = run(free = { model, request -> if ("CHECK_DATA" in request.messages.single().content) null else writes(model, request) })
        digestRun.execute(snapshot)
        assertEquals(listOf("openrouter:minimax/minimax-m3", "openrouter:minimax/minimax-m3", "openrouter:moonshotai/kimi-k2.6"), client.freeCalls)
    }

    @Test
    fun anUnreadableSearchIsRepairedWhenEveryUrlIsInIt() = runBlocking {
        val broken = "Sorry, here it is: " + search.dropLast(3)
        val (_, digestRun) = run(paid = okResponse(broken, 1_440), free = { model, request ->
            if ("BROKEN_REPORT" in request.messages.single().content) search else writes(model, request)
        })
        assertIs<RunOutcome.Stored>(digestRun.execute(snapshot))
        assertEquals(1, calls.calls.single().issues[Issue.Kind.ANSWER_REPAIRED])
    }

    @Test
    fun aRepairThatInventsAUrlIsRefusedAndReadAgainWorksLater() = runBlocking {
        val invented = search.replace("https://ocean.example.com/seabed-vote", "https://ocean.example.com/made-up")
        val (_, digestRun) = run(paid = okResponse("Not JSON at all: https://science.example.com/2026/10/stellarator-record", 1_440), free = { model, request ->
            if ("BROKEN_REPORT" in request.messages.single().content) invented else writes(model, request)
        })
        val outcome = assertIs<RunOutcome.Unreadable>(digestRun.execute(snapshot))
        assertEquals(RunError.UNREADABLE, runs.runs.getValue(outcome.runId).error)

        // Simulates an update whose parser can read the kept answer: the raw file now holds a readable one.
        runs.raws[outcome.runId] = responseBody(search, 1_440)
        val (client, later) = run(paid = okResponse(null))
        assertIs<RunOutcome.Stored>(later.readAgain(outcome.runId))
        assertEquals(0, client.paidCalls, "Read again is not a paid call")
        assertNull(later.readAgain(outcome.runId))
    }

    @Test
    fun aSearchWithNoStoryLeftIsEmpty() = runBlocking {
        val none = """{"template_version": 2, "searched_at": "2026-10-18", "stories": [
            {"keywords": ["chess"], "what_happened": "No qualifying story was found.", "attention": 0, "sources": []}]}"""
        val outcome = assertIs<RunOutcome.Empty>(run(paid = okResponse(none, 1_440)).second.execute(snapshot))
        assertEquals(RunError.EMPTY, runs.runs.getValue(outcome.runId).error)
        assertEquals(1, calls.calls.single().issues[Issue.Kind.STORY_NO_SOURCE])
    }

    @Test
    fun deadLinksAreDroppedBeforeTheWriterSeesThem() = runBlocking {
        var report = ""
        val links = LinkChecker { url -> if ("ocean" in url) LinkResult(LinkStatus.DEAD, "HTTP 404") else LinkResult(LinkStatus.ALIVE) }
        run(links = links, free = { model, request ->
            if ("SEARCH_REPORT" in request.messages.single().content) report = request.messages.single().content
            writes(model, request)
        }).second.execute(snapshot)
        assertTrue("ocean.example.com/seabed-vote" !in report)
        // The writer named the dead source: the story's live one is used instead.
        assertEquals(listOf("https://wire.example.net/council-agenda"), digests.saved.single().digest.topics[1].sources.map { it.url })
        assertEquals(1, calls.calls.single().issues[Issue.Kind.TOPIC_FOREIGN_SOURCE])
    }

    @Test
    fun topicModeCoversEveryChosenTopicAndListsTheMissingOnes() = runBlocking {
        var searchMessage = ""
        var writerMessage = ""
        val topics = snapshot.copy(
            prompt = snapshot.prompt.copy(mode = DigestMode.TOPICS, keywords = listOf("fusion energy", "deep-sea mining", "Mars exploration")),
        )
        val client = ScriptedRfab(okResponse(search, 1_440)) { model, request ->
            if ("SEARCH_REPORT" in request.messages.single().content) writerMessage = request.messages.single().content
            writes(model, request)
        }
        val digestRun = DigestRun(runs, digests, calls, object : io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient by client {
            override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> {
                if (request.model == "openrouter:perplexity/sonar") searchMessage = request.messages.single().content
                return client.chat(request)
            }
        }, withPages) { clock += 500; clock }
        assertIs<RunOutcome.Stored>(digestRun.execute(topics))
        assertTrue("\"mode\":\"topics\"" in searchMessage && "\"interest_tags\":[]" in searchMessage, searchMessage)
        assertTrue("which the reader followed in earlier digests" in searchMessage)
        assertTrue("\"mode\":\"topics\"" in writerMessage)
        val digest = digests.saved.single().digest
        assertEquals(listOf("Mars exploration"), digest.missingTopics)
        assertEquals("No article found this time for: Mars exploration.", DigestViews.build(digest, RenderMeta(null, "m")).missingLine)
        assertEquals("topics", calls.calls.single().mode)
    }

    @Test
    fun aTrendingCardFiledUnderAChosenTopicCoversIt() = runBlocking {
        // 2026-10-30: a feed's trending card reused a chosen topic's tag, yet the topic was listed as missing.
        val trendingSearch = """{"template_version": 2, "searched_at": "2026-10-18", "stories": [{"keywords": [], "trending": true,
            "what_happened": "A study ranked straws.", "attention": 30, "sources": [{"title": "Straws study", "publisher": "Example News",
            "url": "https://news.example.com/2026/10/straws", "language": "en", "published": "2026-10-17", "facts": ["Paper straws scored worse."]}]}]}"""
        val writer = """{"template_version": 2, "topics": [{"story": 1, "topic_tag": "Paper straws study", "title": "Paper straws lose",
            "summary": "A study ranked paper straws worse than plastic.", "detail": null, "keywords": [], "relevance": 30, "sources": [1]}]}"""
        val (_, digestRun) = run(paid = okResponse(trendingSearch, 1_440), free = { model, request ->
            when {
                model != "openrouter:minimax/minimax-m3" -> null
                "SEARCH_REPORT" in request.messages.single().content -> writer
                else -> """{"cards": [{"card": 1}]}"""
            }
        })
        val topics = snapshot.copy(prompt = snapshot.prompt.copy(mode = DigestMode.TOPICS, keywords = listOf("paper straws study", "Mars exploration")))
        assertIs<RunOutcome.Stored>(digestRun.execute(topics))
        assertEquals(listOf("Mars exploration"), digests.saved.single().digest.missingTopics)
    }

    @Test
    fun topicModeKeepsAChosenTopicWhateverItsScore() = runBlocking {
        val low = written.replace("\"relevance\": 45", "\"relevance\": 0")
        val topics = snapshot.copy(prompt = snapshot.prompt.copy(mode = DigestMode.TOPICS, keywords = listOf("fusion energy", "deep-sea mining")))
        val (_, digestRun) = run(free = { model, request -> if ("SEARCH_REPORT" in request.messages.single().content && model == "openrouter:minimax/minimax-m3") low else writes(model, request) })
        assertIs<RunOutcome.Stored>(digestRun.execute(topics))
        assertEquals(2, digests.saved.single().digest.topics.size, "a low score keeps a chosen topic")
        assertEquals(emptyList(), digests.saved.single().digest.missingTopics)
    }

    @Test
    fun theStoredTagsAreTheChoicesOfTopicMode() = runBlocking {
        run().second.execute(snapshot)
        assertEquals(listOf("Stellarator research", "Deep-sea mining rules"), digests.topicTags())
    }

    @Test
    fun refusalsBillNothingAndAreNeverResent() = runBlocking {
        val cases = mapOf(
            RfabError.KEY_REFUSED to RunError.KEY_REFUSED,
            RfabError.INSUFFICIENT_TOKENS to RunError.INSUFFICIENT_TOKENS,
            RfabError.UNAVAILABLE to RunError.UNAVAILABLE,
        )
        for ((kind, expected) in cases) {
            val (client, digestRun) = run(paid = RfabResult.Error(kind, 401))
            assertEquals(expected, assertIs<RunOutcome.Refused>(digestRun.execute(snapshot)).error)
            assertEquals(1, client.paidCalls)
        }
        assertEquals(emptyList(), digests.saved)
    }

    @Test
    fun aLostAnswerIsInterruptedAndABilledFailureSaysSo() = runBlocking {
        assertIs<RunOutcome.Interrupted>(run(paid = RfabResult.Error(RfabError.NETWORK)).second.execute(snapshot))
        assertIs<RunOutcome.Interrupted>(run(paid = RfabResult.Error(RfabError.FAILED_MAYBE_BILLED, 502)).second.execute(snapshot))
        val billed = assertIs<RunOutcome.FailedBilled>(run(paid = RfabResult.Error(RfabError.FAILED_BILLED, 500, tokensBilled = 1_440)).second.execute(snapshot))
        assertEquals(1_440, runs.runs.getValue(billed.runId).tokensBilled)
    }

    @Test
    fun anUnexpectedBodyIsKeptAsUnreadable() = runBlocking {
        val outcome = run(paid = RfabResult.Error(RfabError.BAD_RESPONSE, 200, raw = "<html>oops</html>")).second.execute(snapshot)
        assertEquals("<html>oops</html>", runs.raws[assertIs<RunOutcome.Unreadable>(outcome).runId])
    }

    @Test
    fun oneRunAtATimeAndAnOverLimitInputSendsNothing() = runBlocking {
        val tooMany = snapshot.copy(prompt = snapshot.prompt.copy(keywords = List(6) { "k$it" }))
        val (client, digestRun) = run()
        assertEquals(RunOutcome.NotBuilt, digestRun.execute(tooMany))
        runs.start(snapshot, 0)
        assertEquals(RunOutcome.Busy, digestRun.execute(snapshot))
        assertEquals(0, client.paidCalls)
    }

    @Test
    fun recoveryNeverResendsAndResumesAReceivedAnswer() = runBlocking {
        val calling = runs.start(snapshot, 0)!!
        runs.update(runs.runs.getValue(calling).copy(state = RunState.INTERRUPTED))
        val received = runs.start(snapshot, 0)!!
        runs.saveRaw(received, responseBody(search, 1_440))
        runs.update(runs.runs.getValue(received).copy(state = RunState.RECEIVED, tokensBilled = 1_440))
        runs.update(runs.runs.getValue(calling).copy(state = RunState.CALLING))

        val (client, digestRun) = run(paid = okResponse(null))
        val outcomes = digestRun.recover()
        assertEquals(0, client.paidCalls)
        assertTrue(outcomes.any { it is RunOutcome.Interrupted })
        assertTrue(outcomes.any { it is RunOutcome.Stored })
        assertEquals(RunState.DONE, runs.runs.getValue(received).state)
    }

    @Test
    fun theSummaryIsTheWritersLineOrTheTopThreeTitles() = runBlocking {
        run().second.execute(snapshot)
        val digest = digests.saved.single().digest
        assertEquals("A plasma record and a mining vote.", DigestSummary.of(digest))
        val plain = digest.copy(cardSummary = null, topics = digest.topics + digest.topics + digest.topics)
        assertTrue(DigestSummary.of(plain).startsWith("Stellarator holds plasma for 40 minutes · "))
        assertTrue(DigestSummary.of(plain).endsWith(" +3 more"))
        assertTrue(allAlive.check("https://x.example.com/a").pageText == null)
    }
}
