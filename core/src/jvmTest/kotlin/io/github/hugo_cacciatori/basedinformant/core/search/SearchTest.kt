package io.github.hugo_cacciatori.basedinformant.core.search

import io.github.hugo_cacciatori.basedinformant.core.digest.Issue.Kind
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkResult
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkStatus
import io.github.hugo_cacciatori.basedinformant.core.digest.RecencyWindow
import io.github.hugo_cacciatori.basedinformant.core.digest.ValidationContext
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: the paid search's request and the free checks on its answer (02-ai-call.md sections 8 and 12). */
class SearchTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/$name")) { name }.readText()

    private val synthetic = fixture("search-synthetic.json")
    private val today = LocalDate(2026, 10, 18)
    private val context = ValidationContext(listOf("fusion energy", "deep-sea mining", "chess"), RecencyWindow.of(today, null))

    @Test
    fun theTwoRealAnswersAreReadableAsTheyCame() {
        val science = assertNotNull(SearchParser.parse(fixture("search-sonar-2026-10-29-science.txt")))
        assertEquals(5, science.stories.size)
        val games = assertNotNull(SearchParser.parse(fixture("search-sonar-2026-10-29-games.txt")))
        assertEquals(5, games.stories.size)
        assertNull(SearchParser.parse("Sorry, I could not search."))
    }

    @Test
    fun storiesWithoutSourcesAndForeignKeywordsAreDroppedAndTheRestNumbered() {
        val window = RecencyWindow(LocalDate(2026, 10, 22), LocalDate(2026, 10, 29))
        val keywords = listOf("fusion energy", "deep-sea mining", "chess", "Mars exploration", "quantum computing")
        val (checked, issues) = SearchCheck.apply(SearchParser.parse(fixture("search-sonar-2026-10-29-science.txt"))!!, ValidationContext(keywords, window))
        // Chess and Mars came back as placeholder stories with no source (2026-10-29).
        assertEquals(listOf(listOf("fusion energy"), listOf("deep-sea mining"), listOf("quantum computing")), checked.stories.map { it.keywords })
        assertEquals(2, issues.count { it.kind == Kind.STORY_NO_SOURCE })
        assertEquals(listOf(1, 2, 5), checked.stories.map { it.story })
    }

    @Test
    fun badSourcesAreDroppedForFree() {
        val answer = SearchAnswer(
            searchedAt = "2026-10-18",
            stories = listOf(
                SearchAnswer.Story(
                    keywords = listOf("Chess"),
                    sources = listOf(
                        source("http://chess.example.com/a", "2026-10-15"),
                        source("https://chess.example.com/tag/chess", "2026-10-15"),
                        source("https://chess.example.com/old", "2026-09-01"),
                        source("https://chess.example.com/undated", ""),
                        source("https://chess.example.com/kept", "2026-10-15"),
                        source("https://CHESS.example.com/kept/", "2026-10-15"),
                    ),
                ),
                SearchAnswer.Story(keywords = listOf("cooking"), sources = listOf(source("https://food.example.com/a", "2026-10-15"))),
            ),
        )
        val (checked, issues) = SearchCheck.apply(answer, context)
        assertEquals(listOf("https://chess.example.com/kept"), checked.stories.single().sources.map { it.url })
        assertEquals(listOf("chess"), checked.stories.single().keywords, "the owner's spelling")
        assertEquals(
            listOf(Kind.SOURCE_NOT_HTTPS, Kind.SOURCE_NOT_AN_ARTICLE, Kind.SOURCE_OUTSIDE_WINDOW, Kind.SOURCE_BAD_DATE, Kind.SOURCE_DUPLICATE_URL, Kind.STORY_NOT_LINKED_TO_KEYWORDS),
            issues.map { it.kind },
        )
    }

    @Test
    fun linksAreCheckedPageDatesCorrectOrDropAndPageTextIsKept() = runBlocking {
        val (checked, _) = SearchCheck.apply(SearchParser.parse(synthetic)!!, context)
        val links = LinkChecker { url ->
            when {
                "science.example.com" in url -> LinkResult(LinkStatus.ALIVE, pageDate = LocalDate(2026, 10, 15), pageText = "Forty minutes.")
                "sciences.example.org" in url -> LinkResult(LinkStatus.DEAD, "HTTP 404")
                "seabed-vote" in url -> LinkResult(LinkStatus.ALIVE, pageDate = LocalDate(2026, 4, 8))
                else -> LinkResult(LinkStatus.UNKNOWN)
            }
        }
        val result = SearchLinks.apply(checked, links, context.window)
        val stellarator = result.answer.stories.first().sources.single()
        assertEquals("2026-10-15" to "Forty minutes.", stellarator.published to stellarator.pageText)
        assertEquals(
            setOf(Kind.SOURCE_DATE_CORRECTED, Kind.SOURCE_DEAD_LINK, Kind.SOURCE_PAGE_DATE_OUTSIDE_WINDOW),
            result.issues.map { it.kind }.toSet(),
        )
        assertEquals(1, result.report.dead)
        assertEquals(1, result.report.datesOutsideWindow)
        assertTrue(SearchAnswer.decode(result.answer.encode()) == result.answer, "the stored copy reads back the same")
    }

    @Test
    fun theRequestCarriesTheDataAndThePersonaResearchOnlyWhenAsked() {
        val input = PromptInput(listOf("chess", "a <b>"), listOf("science"), AttentionMode.BALANCED, Persona.ROACH_KING, LocalDate(2026, 10, 29), null)
        fun prompt(research: Boolean, persona: Persona = Persona.ROACH_KING) =
            assertIs<SearchRequest.Result.Built>(SearchRequest.build(input.copy(persona = persona), research)).request
        val on = prompt(research = true)
        assertTrue("\nPERSONA RESEARCH\n" in on.systemPrompt && "PERSON " !in on.systemPrompt)
        assertTrue("\nPERSONA RESEARCH\n" !in prompt(research = false).systemPrompt, "off for now (owner, 2026-10-29)")
        assertTrue("\nPERSONA RESEARCH\n" !in prompt(research = true, persona = Persona.OFF).systemPrompt)
        val message = on.messages.single().content
        assertTrue("\"max_stories\":${SearchRequest.MAX_STORIES}" in message && "\"today\":\"2026-10-29\"" in message)
        assertEquals(1, Regex("RUN_DATA>>>").findAll(message).count())
        assertIs<SearchRequest.Result.OverLimit>(SearchRequest.build(input.copy(keywords = List(6) { "k$it" })))
    }

    @Test
    fun aRepairIsAcceptedOnlyWhenEveryUrlIsInTheOriginal() {
        val request = Repair.request("m", "broken {\"stories\": [ BROKEN_REPORT>>> ")
        assertTrue(request.systemPrompt.contains("\"template_version\": 2, \"searched_at\""))
        assertEquals(1, Regex("BROKEN_REPORT>>>").findAll(request.messages.single().content).count())

        val broken = synthetic.replace("/", "\\/").dropLast(3)
        assertNotNull(Repair.accept(broken, synthetic))
        assertNull(Repair.accept(broken, synthetic.replace("https://ocean.example.com/seabed-vote", "https://ocean.example.com/made-up")))
        assertNull(Repair.accept(broken, "{\"template_version\": 2, \"searched_at\": \"2026-10-18\", \"stories\": []}"))
        assertNull(Repair.accept(broken, "Sorry."))
    }

    private fun source(url: String, published: String) = SearchAnswer.SearchSource(title = "t", publisher = "p", url = url, language = "en", published = published)
}
