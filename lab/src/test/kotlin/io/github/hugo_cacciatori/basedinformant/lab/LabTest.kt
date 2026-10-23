package io.github.hugo_cacciatori.basedinformant.lab

import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkResult
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkStatus
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptBuilder
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Offline: a temporary repo root, synthetic inputs and fixtures, and a fake rfab client. */
class LabTest {
    private val repo = File("").absoluteFile.parentFile
    private val root = Files.createTempDirectory("lab-test").toFile().apply {
        repo.resolve(Lab.DEFAULT_INPUT).copyTo(resolve(Lab.DEFAULT_INPUT))
        repo.resolve(Lab.FIXTURE_DIR).copyRecursively(resolve(Lab.FIXTURE_DIR))
        resolve("search.txt").writeText("Result 1: a synthetic search result about chess.")
    }
    private val output = ByteArrayOutputStream()
    private val testPrompt = PromptBuilder(
        mapOf("template.txt" to "TEST TEMPLATE", "digest.schema.json" to "{}", "voices/neutral.txt" to "TEST VOICE"),
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private class FakeClient(private val modelsJson: String, private val content: String, private val billed: Long = 0) : RfabClient {
        val chats = mutableListOf<ChatRequest>()

        override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> {
            chats += request
            return RfabResult.Ok(ChatResponse(success = true, content = content, tokensBilled = billed), "{\"raw\":true}")
        }

        override suspend fun balance(): RfabResult<Balance> = RfabResult.Ok(Balance(tokenBalance = 1000), "{}")

        override suspend fun models(): RfabResult<ModelCatalog> =
            RfabResult.Ok(Json.decodeFromString(ModelCatalog.serializer(), modelsJson), modelsJson)
    }

    private fun lab(
        client: RfabClient? = null,
        key: String? = "rfab_placeholder_lab_test_00",
        prompt: PromptBuilder = testPrompt,
        links: LinkChecker = LinkChecker { LinkResult(LinkStatus.ALIVE) },
    ) = Lab(
        root = root,
        out = PrintStream(output, true),
        promptBuilder = prompt,
        loadKey = { key },
        clientFor = { client ?: error("no client expected") },
        linkChecker = { links },
        now = { LocalDateTime.of(2026, 10, 18, 12, 0, 0) },
        clockMillis = { 0L },
    )

    private fun run(lab: Lab, vararg args: String) = runBlocking { lab.run(LabCommand.parse(args.toList())) }

    private fun runDir() = root.resolve("lab-out").listFiles()!!.single()

    private val printed get() = output.toString()

    @Test
    fun commandLineParsing() {
        assertEquals(LabCommand.Help, LabCommand.parse(emptyList()))
        assertEquals(LabCommand.Fixture("good", null), LabCommand.parse(listOf("--fixture", "good")))
        assertEquals(LabCommand.DryRun("x.json", "roach-king", null), LabCommand.parse(listOf("--live", "--dry-run", "--input", "x.json", "--persona", "roach-king")))
        assertEquals(LabCommand.Free("free:m", "s.txt", null, null), LabCommand.parse(listOf("--free", "free:m", "--search", "s.txt")))
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--live")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--live", "--dry-run", "--confirm-paid")) }
        assertEquals(LabCommand.Live(null, null, null), LabCommand.parse(listOf("--live", "--confirm-paid")))
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--fixture", "a", "--models")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--free", "m")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--fixture")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--bogus")) }
    }

    @Test
    fun checkInputScoresEachFreeModelAndSkipsPaidOnes() {
        root.resolve("list.json").writeText(
            """{"_comment": "x", "entries": [{"text": "chess", "expect": "ok"}, {"text": "xqzvbt", "expect": "refuse"}]}""",
        )
        val models = """{"success":true,"models":[{"model":"free:m","rateMultiplier":0},{"model":"paid:m","rateMultiplier":0.36}]}"""
        // One group of two entries; the fake refuses entry 1, whichever it is after the shuffle.
        val client = FakeClient(models, """{"refused": [{"n": 1, "reason": "random letters"}]}""")
        assertEquals(0, run(lab(client), "--check-input", "--model", "free:m,paid:m", "--list", "list.json"))
        assertEquals(1, client.chats.size)
        assertEquals("free:m", client.chats.single().model)
        assertTrue("paid:m: SKIPPED, rate 0.36" in printed, printed)
        assertTrue(Regex("free:m: nonsense caught [01] / 1; real entries refused [01] / 1; groups failed 0 / 1").containsMatchIn(printed), printed)
        assertTrue(Regex("balance before -> after: 1000 -> 1000\r?\n").containsMatchIn(printed), printed)
        assertTrue(runDir().resolve("1-free_m-1-raw.json").isFile)
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--check-input")) }
    }

    @Test
    fun fixtureWritesTheDigestFiles() {
        assertEquals(0, run(lab(), "--fixture", "good"))
        val dir = runDir()
        assertEquals("20261018-120000-fixture", dir.name)
        for (name in listOf("raw.json", "digest.json", "digest.html", "digest.txt", "digest.eml", "report.txt")) {
            assertTrue(dir.resolve(name).isFile, "missing $name")
        }
        val digest = Json.parseToJsonElement(dir.resolve("digest.json").readText()).toString()
        assertTrue("\"template_version\":1" in digest)
        assertTrue("stellarator-record-run" in digest && "club-chess-open" !in digest)
        assertTrue("multipart/alternative" in dir.resolve("digest.eml").readText())
        assertTrue("valid topics: 3; selected: 2" in printed)
    }

    @Test
    fun unreadableFixtureFailsAndKeepsTheRaw() {
        assertEquals(1, run(lab(), "--fixture", "malformed"))
        assertTrue(runDir().resolve("raw.json").isFile)
        assertTrue("unreadable answer: NOT_JSON" in printed)
    }

    @Test
    fun dryRunBuildsWithoutSending() {
        assertEquals(0, run(lab(), "--live", "--dry-run"))
        val request = runDir().resolve("request.json").readText()
        assertTrue("TEST TEMPLATE" in request && "fusion energy" in request)
        assertFalse("rfab_" in request)
        assertTrue("payload:" in printed && "estimate:" in printed)
    }

    @Test
    fun dryRunWaitsForMissingPromptFiles() {
        assertEquals(1, run(lab(prompt = PromptBuilder(emptyMap())), "--live", "--dry-run"))
        assertTrue("core/prompt/template.txt is not written yet" in printed)
    }

    @Test
    fun freeRefusesAModelNotShownAtZero() {
        for (models in listOf("""{"models":[{"model":"free:m","rateMultiplier":0.36}]}""", """{"models":[{"model":"other:m","rateMultiplier":0}]}""")) {
            output.reset()
            val client = FakeClient(models, content = "{}")
            assertEquals(1, run(lab(client), "--free", "free:m", "--search", "search.txt"))
            assertTrue(client.chats.isEmpty())
            assertTrue("nothing sent" in printed)
        }
    }

    @Test
    fun freeSendsOnceToAZeroModelAndProcessesTheAnswer() {
        val good = root.resolve(Lab.FIXTURE_DIR).resolve("good.json").readText()
        val client = FakeClient("""{"models":[{"model":"free:m","rateMultiplier":0}]}""", content = good)
        assertEquals(0, run(lab(client), "--free", "free:m", "--search", "search.txt"))
        val sent = client.chats.single()
        assertEquals("free:m", sent.model)
        assertTrue(sent.messages.last().content.contains("<<<SEARCH_RESULTS\nResult 1"))
        val dir = runDir()
        assertEquals("{\"raw\":true}", dir.resolve("raw.json").readText())
        assertTrue(dir.resolve("digest.html").isFile && dir.resolve("models.json").isFile)
        assertTrue("tokensBilled: 0" in printed)
        assertFalse("WARNING" in printed)
    }

    @Test
    fun writeSendsTheSearchReportToAFreeModelAndRendersTheDigest() {
        for (name in listOf("lab/inputs/search-answer-synthetic.json", "lab/inputs/writer.json")) repo.resolve(name).copyTo(root.resolve(name))
        val written = """{"template_version": 2, "opening": "Hi.", "closing": "Bye.", "subject": "Plasma and seabeds", "card_summary": "Two stories",
            "topics": [{"story": 1, "topic_tag": "Stellarator research", "title": "Stellarator record", "summary": "A plasma lasted 40 minutes.",
            "detail": null, "keywords": ["fusion energy"], "relevance": 50, "sources": [1, 2]}]}"""
        val client = FakeClient("""{"models":[{"model":"free:m","rateMultiplier":0}]}""", content = written)
        assertEquals(
            0,
            run(lab(client), "--write", "free:m", "--search-answer", "lab/inputs/search-answer-synthetic.json", "--input", "lab/inputs/writer.json", "--persona", "roach-king"),
        )
        val sent = client.chats.single()
        assertTrue("<<<SEARCH_REPORT" in sent.messages.single().content && "VOICE" in sent.systemPrompt)
        assertTrue("copied quotes: none" in printed && "valid topics: 1; selected: 1" in printed)
        assertTrue(runDir().resolve("digest.html").isFile)
    }

    @Test
    fun freeWarnsWhenAFreeModelBills() {
        val good = root.resolve(Lab.FIXTURE_DIR).resolve("good.json").readText()
        val client = FakeClient("""{"models":[{"model":"free:m","rateMultiplier":0}]}""", content = good, billed = 12)
        run(lab(client), "--free", "free:m", "--search", "search.txt")
        assertTrue("WARNING: a free model billed 12 tokens" in printed)
        assertTrue("| 2026-10-18 | free-model format test | free:m | 3 /" in printed)
    }

    private val sonarCatalog = """{"models":[{"model":"openrouter:perplexity/sonar","rateMultiplier":0.36}]}"""

    @Test
    fun livePaidCallSendsOnceChecksLinksAndPrintsTheLogLine() {
        val good = root.resolve(Lab.FIXTURE_DIR).resolve("good.json").readText()
        val client = FakeClient(sonarCatalog, content = good, billed = 6_000)
        val links = LinkChecker { url -> if ("ocean.example.com" in url) LinkResult(LinkStatus.DEAD, "HTTP 404") else LinkResult(LinkStatus.ALIVE) }
        assertEquals(0, run(lab(client, links = links), "--live", "--confirm-paid"))
        val sent = client.chats.single()
        assertEquals("openrouter:perplexity/sonar", sent.model)
        assertEquals(1, sent.messages.size)
        assertTrue("rate: 0.36x" in printed)
        assertTrue("links: 4 alive, 0 unknown (kept), 1 dead; 80% kept (bar: 80%) PASS" in printed)
        assertTrue("| 2026-10-18 | paid template test | openrouter:perplexity/sonar | 3 /" in printed)
        assertTrue("dead link: https://ocean.example.com/seabed-vote (HTTP 404)" in printed)
        assertFalse("STOP RULE" in printed)
        assertTrue(runDir().resolve("raw.json").isFile && runDir().resolve("digest.html").isFile)
    }

    @Test
    fun liveWarnsWhenOverTheStopRule() {
        val good = root.resolve(Lab.FIXTURE_DIR).resolve("good.json").readText()
        run(lab(FakeClient(sonarCatalog, content = good, billed = 15_001)), "--live", "--confirm-paid")
        assertTrue("STOP RULE: this call billed 15001 tokens" in printed)
    }

    @Test
    fun liveRefusesAModelMissingFromTheCatalog() {
        val client = FakeClient("""{"models":[]}""", content = "{}")
        assertEquals(1, run(lab(client), "--live", "--confirm-paid"))
        assertTrue(client.chats.isEmpty())
        assertTrue("not in rfab's models list; nothing sent" in printed)
    }

    @Test
    fun groupSizeParsing() {
        assertEquals(LabCommand.DryRun(null, null, null, 5), LabCommand.parse(listOf("--live", "--dry-run", "--group-size", "5")))
        assertEquals(LabCommand.Live(null, null, null, 2), LabCommand.parse(listOf("--live", "--confirm-paid", "--group-size", "2")))
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--live", "--dry-run", "--group-size", "0")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--live", "--dry-run", "--group-size", "five")) }
    }

    @Test
    fun splitDryRunBuildsOneRequestPerGroup() {
        assertEquals(0, run(lab(), "--live", "--dry-run", "--group-size", "2"))
        val dir = runDir()
        val first = Json.decodeFromString(ChatRequest.serializer(), dir.resolve("request-1.json").readText()).messages.single().content
        val second = Json.decodeFromString(ChatRequest.serializer(), dir.resolve("request-2.json").readText()).messages.single().content
        assertTrue("\"fusion energy\"" in first && "\"chess\"" !in first)
        assertTrue("\"chess\"" in second && "\"fusion energy\"" !in second)
        assertTrue("calls: 2 (groups of 2)" in printed)
        assertTrue("call 2: chess" in printed)
    }

    @Test
    fun splitPaidRunSendsEachGroupOnceAndMergesTheAnswers() {
        val good = root.resolve(Lab.FIXTURE_DIR).resolve("good.json").readText()
        val client = FakeClient(sonarCatalog, content = good, billed = 1_440)
        assertEquals(0, run(lab(client), "--live", "--confirm-paid", "--group-size", "2"))
        assertEquals(2, client.chats.size)
        val dir = runDir()
        assertTrue(dir.resolve("raw-1.json").isFile && dir.resolve("raw-2.json").isFile)
        assertTrue("total billed: 2880 tokens over 2 calls" in printed)
        assertTrue("| 2026-10-18 | paid template test | openrouter:perplexity/sonar | 2 /" in printed)
        assertTrue("| 2026-10-18 | paid template test | openrouter:perplexity/sonar | 1 /" in printed)
        // The second answer repeats the first one's stories: they are dropped, not shown twice.
        assertTrue("TOPIC_DUPLICATE_STORY call=2" in printed)
        assertTrue("valid topics: 3; selected: 2" in printed)
    }

    @Test
    fun splitPaidRunStopsAtTheFirstFailedCall() {
        val good = root.resolve(Lab.FIXTURE_DIR).resolve("good.json").readText()
        val client = object : RfabClient {
            var chats = 0
            override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> =
                if (++chats == 1) RfabResult.Ok(ChatResponse(success = true, content = good, tokensBilled = 1_440), "{}")
                else RfabResult.Error(RfabError.KEY_REFUSED, 401)
            override suspend fun balance(): RfabResult<Balance> = RfabResult.Ok(Balance(tokenBalance = 1000), "{}")
            override suspend fun models(): RfabResult<ModelCatalog> =
                RfabResult.Ok(Json.decodeFromString(ModelCatalog.serializer(), sonarCatalog), sonarCatalog)
        }
        val input = root.resolve("five.json")
        input.writeText(root.resolve(Lab.DEFAULT_INPUT).readText().replace(
            "\"keywords\": [\"fusion energy\", \"deep-sea mining\", \"chess\"]",
            "\"keywords\": [\"fusion energy\", \"deep-sea mining\", \"chess\", \"volcanoes\", \"coral reefs\"]",
        ))
        assertEquals(0, run(lab(client), "--live", "--confirm-paid", "--group-size", "2", "--input", "five.json"))
        assertEquals(2, client.chats)
        assertTrue("call 2/3: call failed: KEY_REFUSED" in printed)
        assertTrue("stopped: the remaining 1 calls were not sent" in printed)
        assertTrue("CALL_UNREADABLE call=2" in printed)
    }

    private val grokCatalog = """{"models":[{"model":"openrouter:x-ai/grok-4.20","rateMultiplier":0.172}]}"""
    private val article = """Here it is: {"title":"T","publisher":"P","url":"https://news.example.com/2026/10/story","published":"2026-10-15"}"""

    private fun probe(content: String, link: LinkResult): FakeClient {
        val client = FakeClient(grokCatalog, content = content, billed = 120)
        assertEquals(0, run(lab(client, links = { link }), "--probe", "--model", "openrouter:x-ai/grok-4.20", "--confirm-paid"))
        return client
    }

    @Test
    fun probeParsing() {
        assertEquals(
            LabCommand.Probe("m", "chess", null, paid = false),
            LabCommand.parse(listOf("--probe", "--model", "m", "--keyword", "chess", "--dry-run")),
        )
        assertEquals(LabCommand.Probe("m", null, null, paid = true), LabCommand.parse(listOf("--probe", "--model", "m", "--confirm-paid")))
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--probe", "--model", "m")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--probe", "--dry-run")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--probe", "--model", "m", "--dry-run", "--confirm-paid")) }
    }

    @Test
    fun probeDryRunSendsNothing() {
        assertEquals(0, run(lab(key = null), "--probe", "--model", "openrouter:x-ai/grok-4.20", "--dry-run"))
        val request = Json.decodeFromString(ChatRequest.serializer(), runDir().resolve("request.json").readText())
        assertEquals(Lab.PROBE_MAX_TOKENS, request.maxTokens)
        assertTrue("from 2026-10-11 to 2026-10-18" in request.messages.single().content)
        assertTrue("\"fusion energy\"" in request.messages.single().content)
    }

    @Test
    fun probeSendsOnceAndSaysItSearchesForALiveDatedPage() {
        val client = probe(article, LinkResult(LinkStatus.ALIVE, "HTTP 200", LocalDate(2026, 10, 15)))
        assertEquals(1, client.chats.size)
        assertEquals(Lab.PROBE_MAX_TOKENS, client.chats.single().maxTokens)
        assertTrue("verdict: SEARCHES" in printed)
        assertTrue("| 2026-10-18 | paid search probe | openrouter:x-ai/grok-4.20 | 1 /" in printed)
        assertTrue(runDir().resolve("raw.json").isFile)
    }

    @Test
    fun probeVerdictsForDeadOldUndatedOrMissingArticles() {
        probe(article, LinkResult(LinkStatus.DEAD, "placeholder URL"))
        assertTrue("verdict: NO SEARCH (dead or made-up URL)" in printed)
        output.reset()
        probe(article, LinkResult(LinkStatus.ALIVE, "HTTP 200", LocalDate(2026, 4, 8)))
        assertTrue("verdict: SEARCHES, WRONG DATE (a live page dated 2026-04-08" in printed)
        output.reset()
        probe(article, LinkResult(LinkStatus.UNKNOWN, "HTTP 403"))
        assertTrue("verdict: UNCLEAR" in printed)
        output.reset()
        probe("""{"url": null}""", LinkResult(LinkStatus.ALIVE))
        assertTrue("verdict: NO SEARCH (no article URL in the answer)" in printed)
    }

    @Test
    fun probeRefusesAModelMissingFromTheCatalog() {
        val client = FakeClient("""{"models":[]}""", content = article)
        assertEquals(1, run(lab(client), "--probe", "--model", "openrouter:x-ai/grok-4.20", "--confirm-paid"))
        assertTrue(client.chats.isEmpty())
    }

    @Test
    fun noKeyMeansNoCall() {
        assertEquals(1, run(lab(key = null), "--free", "free:m", "--search", "search.txt"))
        assertTrue("sh scripts/set-rfab-key" in printed)
        assertFalse("rfab_placeholder" in printed)
    }

    @Test
    fun keyLoaderOrderAndMasking() {
        val home = Files.createTempDirectory("home").toFile()
        try {
            val fromEnv = KeyLoader(env = { "rfab_from_environment_0000ab" }, home = home, repoRoot = root).load()
            assertEquals("rfab_from_environment_0000ab", fromEnv)
            root.resolve(".env").writeText("RFAB_API_KEY=rfab_from_dot_env_file_000cd\n")
            assertEquals("rfab_from_dot_env_file_000cd", KeyLoader(env = { null }, home = home, repoRoot = root).load())
            home.resolve(".config/based-informant").mkdirs()
            home.resolve(".config/based-informant/rfab-api-key").writeText("rfab_from_config_file_0000ef\n")
            assertEquals("rfab_from_config_file_0000ef", KeyLoader(env = { null }, home = home, repoRoot = root).load())
            assertEquals("key found, ends with 00ef", KeyLoader.describe("rfab_from_config_file_0000ef"))
            assertEquals(null, KeyLoader(env = { "rfab_your_key_here" }, home = File("missing"), repoRoot = File("missing")).load())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun outputFoldersNeverCollide() {
        run(lab(), "--fixture", "good")
        run(lab(), "--fixture", "good")
        assertEquals(
            listOf("20261018-120000-fixture", "20261018-120000-fixture-2"),
            root.resolve("lab-out").listFiles()!!.map { it.name }.sorted(),
        )
    }

    @Test
    fun syntheticInputIsValid() {
        val input = LabInput.read(repo.resolve(Lab.DEFAULT_INPUT))
        assertIs<PromptBuilder.Result.Built>(testPrompt.build(input.toPromptInput("m")))
    }
}
