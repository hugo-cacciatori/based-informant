package io.github.hugo_cacciatori.basedinformant.lab

import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkResult
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkStatus
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.search.SearchAnswer
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Offline: a temporary repo root, synthetic inputs, and a fake rfab client. */
class LabTest {
    private val repo = File("").absoluteFile.parentFile
    private val root = Files.createTempDirectory("lab-test").toFile().apply {
        for (name in listOf(Lab.DEFAULT_INPUT, "lab/inputs/search-answer-synthetic.json", "lab/inputs/writer.json", "lab/inputs/search-call.json", "lab/inputs/repair-cut.txt")) {
            repo.resolve(name).copyTo(resolve(name))
        }
    }
    private val output = ByteArrayOutputStream()

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

    private val freeCatalog = """{"models":[{"model":"free:m","rateMultiplier":0},{"model":"openrouter:perplexity/sonar","rateMultiplier":0.36}]}"""

    private val written = """{"template_version": 2, "opening": "Hi.", "closing": "Bye.", "subject": "Plasma and seabeds", "card_summary": "Two stories",
        "topics": [{"story": 1, "topic_tag": "Stellarator research", "title": "Stellarator record", "summary": "A plasma lasted 40 minutes.",
        "detail": null, "keywords": ["fusion energy"], "relevance": 50, "sources": [1, 2]}]}"""

    private fun lab(
        client: RfabClient? = null,
        key: String? = "rfab_placeholder_lab_test_00",
        links: LinkChecker = LinkChecker { LinkResult(LinkStatus.ALIVE) },
    ) = Lab(
        root = root,
        out = PrintStream(output, true),
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
        assertEquals(LabCommand.SearchCall(null, null, null, false), LabCommand.parse(listOf("--search-call", "--dry-run")))
        assertEquals(LabCommand.SearchCall("x.json", null, null, true, true), LabCommand.parse(listOf("--search-call", "--confirm-paid", "--input", "x.json", "--persona-research")))
        assertEquals(LabCommand.Repair("free:m", "b.txt"), LabCommand.parse(listOf("--repair", "free:m", "--broken", "b.txt")))
        assertEquals(LabCommand.FactCheck("free:m", "dir", null, null, "a.json"), LabCommand.parse(listOf("--fact-check", "free:m", "--run", "dir", "--answer", "a.json")))
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--search-call")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--search-call", "--dry-run", "--confirm-paid")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--write", "m")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--repair", "m", "--models")) }
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--fixture", "good")) }
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
    fun writeSendsTheSearchReportToAFreeModelAndRendersTheDigest() {
        val client = FakeClient(freeCatalog, content = written)
        assertEquals(
            0,
            run(lab(client), "--write", "free:m", "--search-answer", "lab/inputs/search-answer-synthetic.json", "--input", "lab/inputs/writer.json", "--persona", "roach-king"),
        )
        val sent = client.chats.single()
        assertTrue("<<<SEARCH_REPORT" in sent.messages.single().content && "VOICE" in sent.systemPrompt)
        assertTrue("copied quotes: none" in printed && "topics: 1 written; selected: 1" in printed, printed)
        assertTrue(runDir().resolve("digest.html").isFile && runDir().resolve(Lab.WRITER_ANSWER).isFile)
    }

    @Test
    fun writeRefusesAModelNotShownAtZero() {
        val client = FakeClient("""{"models":[{"model":"free:m","rateMultiplier":0.5}]}""", content = written)
        assertEquals(1, run(lab(client), "--write", "free:m", "--search-answer", "lab/inputs/search-answer-synthetic.json"))
        assertTrue(client.chats.isEmpty())
    }

    @Test
    fun aSavedFactCheckIsReadAgainWithoutACall() {
        val folder = root.resolve("lab-out/old-write").apply { mkdirs() }
        repo.resolve("lab/inputs/search-answer-synthetic.json").copyTo(folder.resolve("search-answer.json"))
        folder.resolve(Lab.WRITER_ANSWER).writeText(written.replace("A plasma lasted 40 minutes.", "A plasma lasted 40 minutes. It cost 2 billion."))
        val saved = root.resolve("check-raw.json").apply {
            writeText("""{"success": true, "content": "{\"cards\": [{\"card\": 1, \"unsupported\": [2]}]}"}""")
        }
        assertEquals(0, run(lab(key = null), "--fact-check", "free:m", "--run", folder.path, "--input", "lab/inputs/writer.json", "--answer", saved.path))
        // No page text in the synthetic answer: the check cannot delete, as in the app.
        assertTrue("finding: FACT_UNCHECKED_NO_PAGE" in printed, printed)
    }

    @Test
    fun searchCallDryRunSendsNothingAndLeavesThePersonaResearchOff() {
        assertEquals(0, run(lab(key = null), "--search-call", "--input", "lab/inputs/search-call.json", "--dry-run"))
        val request = Json.decodeFromString(ChatRequest.serializer(), runDir().resolve("request.json").readText())
        assertTrue("\nPERSONA RESEARCH\n" !in request.systemPrompt)
        assertTrue("\"max_stories\":8" in request.messages.single().content)
        assertTrue("research off" in printed)
    }

    @Test
    fun aPaidSearchIsSentOnceThenCheckedForFree() {
        val answer = repo.resolve("lab/inputs/search-answer-synthetic.json").readText()
        val client = FakeClient(freeCatalog, content = answer, billed = 1_440)
        val links = LinkChecker { LinkResult(LinkStatus.ALIVE, pageDate = LocalDate(2026, 10, 16), pageText = "Page text.") }
        assertEquals(0, run(lab(client, links = links), "--search-call", "--input", "lab/inputs/writer.json", "--confirm-paid"))
        assertEquals(1, client.chats.size)
        assertTrue("| 2026-10-18 | paid search | openrouter:perplexity/sonar |" in printed, printed)
        val checked = SearchAnswer.decode(runDir().resolve("search-checked.json").readText())
        assertTrue(checked.stories.isNotEmpty() && checked.stories.all { s -> s.sources.all { it.pageText == "Page text." } })
        assertTrue("after the checks:" in printed)
    }

    @Test
    fun aRepairIsAcceptedOnlyWithTheOriginalsUrls() {
        val good = FakeClient(freeCatalog, content = repo.resolve("lab/inputs/search-answer-synthetic.json").readText())
        assertEquals(0, run(lab(good), "--repair", "free:m", "--broken", "lab/inputs/repair-cut.txt"))
        assertTrue("REJECTED: URLs not in the original answer: https://chess.example.com/open-record" in printed, printed)
        output.reset()
        root.resolve("lab-out").deleteRecursively()
        val cut = repo.resolve("lab/inputs/repair-cut.txt").readText()
        val onlyKnown = SearchAnswer(searchedAt = "2026-10-18", stories = listOf(
            SearchAnswer.Story(keywords = listOf("fusion energy"), sources = listOf(SearchAnswer.SearchSource(title = "t", publisher = "p", url = "https://science.example.com/2026/10/stellarator-record"))),
        ))
        assertTrue("https://science.example.com/2026/10/stellarator-record" in cut)
        assertEquals(0, run(lab(FakeClient(freeCatalog, content = onlyKnown.encode())), "--repair", "free:m", "--broken", "lab/inputs/repair-cut.txt"))
        assertTrue("ACCEPTED: 1 stories" in printed, printed)
        assertNotNull(runDir().resolve("search-answer.json").takeIf { it.isFile })
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
        assertEquals(1, run(lab(key = null), "--search-call", "--confirm-paid"))
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
        run(lab(key = null), "--search-call", "--dry-run")
        run(lab(key = null), "--search-call", "--dry-run")
        assertEquals(
            listOf("20261018-120000-search-dry-run", "20261018-120000-search-dry-run-2"),
            root.resolve("lab-out").listFiles()!!.map { it.name }.sorted(),
        )
    }

    @Test
    fun theCommittedInputsAreValid() {
        for (name in listOf(Lab.DEFAULT_INPUT, "lab/inputs/synthetic-15.json", "lab/inputs/synthetic-merge.json", "lab/inputs/writer.json", "lab/inputs/search-call.json", "lab/inputs/search-call-games.json")) {
            LabInput.read(repo.resolve(name)).toPromptInput("m")
        }
        assertNotNull(LegacyAnswer.read(repo.resolve("lab/inputs/search-answer-synthetic.json").readText()))
        assertNotNull(LegacyAnswer.read(repo.resolve("core/src/jvmTest/resources/fixtures/good.json").readText()), "a template-v1 answer turns into a search answer")
    }
}
