package io.github.hugo_cacciatori.basedinformant.core.search

import io.github.hugo_cacciatori.basedinformant.core.digest.Issue.Kind
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkResult
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkStatus
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
    private val context = ValidationContext(listOf("fusion energy", "deep-sea mining", "chess"), today)

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
        val keywords = listOf("fusion energy", "deep-sea mining", "chess", "Mars exploration", "quantum computing")
        val (checked, issues) = SearchCheck.apply(SearchParser.parse(fixture("search-sonar-2026-10-29-science.txt"))!!, ValidationContext(keywords, LocalDate(2026, 10, 29)))
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
                        source("https://chess.example.com/future", "2026-11-25"),
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
        // No lower date limit since 2026-10-29: a year-old source is kept (the search ranks it as a fallback).
        val old = answer.copy(stories = listOf(answer.stories.first().copy(sources = listOf(source("https://chess.example.com/last-year", "2025-10-01")))))
        assertEquals(1, SearchCheck.apply(old, context).first.stories.single().sources.size)
        assertEquals(listOf("chess"), checked.stories.single().keywords, "the owner's spelling")
        assertEquals(
            listOf(Kind.SOURCE_NOT_HTTPS, Kind.SOURCE_NOT_AN_ARTICLE, Kind.SOURCE_DATE_IN_FUTURE, Kind.SOURCE_BAD_DATE, Kind.SOURCE_DUPLICATE_URL, Kind.STORY_NOT_LINKED_TO_KEYWORDS),
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
                "seabed-vote" in url -> LinkResult(LinkStatus.ALIVE, pageDate = LocalDate(2026, 11, 30))
                else -> LinkResult(LinkStatus.UNKNOWN)
            }
        }
        val result = SearchLinks.apply(checked, links, context.today)
        val stellarator = result.answer.stories.first().sources.single()
        assertEquals("2026-10-15" to "Forty minutes.", stellarator.published to stellarator.pageText)
        assertEquals(
            setOf(Kind.SOURCE_DATE_CORRECTED, Kind.SOURCE_DEAD_LINK, Kind.SOURCE_PAGE_DATE_IN_FUTURE),
            result.issues.map { it.kind }.toSet(),
        )
        assertEquals(1, result.report.dead)
        assertEquals(1, result.report.datesInFuture)
        assertTrue(SearchAnswer.decode(result.answer.encode()) == result.answer, "the stored copy reads back the same")
    }

    @Test
    fun aStorySayingNothingHappenedIsDropped() {
        fun story(what: String) = SearchAnswer.Story(keywords = listOf("chess"), whatHappened = what, sources = listOf(source("https://chess.example.com/a", "2026-10-15")))
        val answer = SearchAnswer(
            searchedAt = "2026-10-18",
            stories = listOf(
                story("Reference pages about chess were updated, but no news event about chess itself was found."),
                story("Nothing new was reported on chess this week."),
                story("No recent developments on the chess rules."),
                story("A missing chess set was not found after the final."),
            ),
        )
        val (checked, issues) = SearchCheck.apply(answer, context)
        assertEquals(listOf("A missing chess set was not found after the final."), checked.stories.map { it.whatHappened })
        assertEquals(List(3) { Kind.STORY_NOT_NEWS }, issues.map { it.kind })
    }

    @Test
    fun theRequestReadsEachKeywordInTheContextOfTheOthers() {
        val input = PromptInput(listOf("world cup", "2027"), emptyList(), AttentionMode.BALANCED, Persona.OFF, LocalDate(2026, 10, 29))
        val request = assertIs<SearchRequest.Result.Built>(SearchRequest.build(input)).request
        assertTrue(request.messages.single().content.startsWith("Find the most recent news about each of these topics, reading each one in the context of the others:"))
        assertTrue("\nNEWS ONLY\n" in request.systemPrompt)
    }

    @Test
    fun theRequestCarriesTheDataAndThePersonaResearchOnlyWhenAsked() {
        val input = PromptInput(listOf("chess", "a <b>"), listOf("science"), AttentionMode.BALANCED, Persona.ROACH_KING, LocalDate(2026, 10, 29))
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
