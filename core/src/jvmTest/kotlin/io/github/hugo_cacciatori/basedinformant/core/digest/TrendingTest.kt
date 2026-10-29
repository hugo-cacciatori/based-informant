package io.github.hugo_cacciatori.basedinformant.core.digest

import io.github.hugo_cacciatori.basedinformant.core.prompt.DigestMode
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.search.SearchAnswer
import io.github.hugo_cacciatori.basedinformant.core.search.SearchCheck
import io.github.hugo_cacciatori.basedinformant.core.search.SearchRequest
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import io.github.hugo_cacciatori.basedinformant.core.writer.Writer
import io.github.hugo_cacciatori.basedinformant.core.writer.WriterAnswer
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Five cards with trending news in the free slots (owner, 2026-10-29; 02-ai-call.md section 4). */
class TrendingTest {
    private val today = LocalDate(2026, 10, 30)

    private fun topic(id: String, score: Int, trending: Boolean = false) =
        Topic(id, id, "T $id", "S $id", null, if (trending) emptyList() else listOf("chess"), score, null, emptyList(), trending)

    @Test
    fun trendingTopicsFillTheFreeSlotsAfterTheKeywordTopics() {
        val topics = listOf(
            topic("k1", 90), topic("k2", 40), topic("t1", 30, trending = true), topic("k3", 70),
            topic("t2", 55, trending = true), topic("t3", 20, trending = true), topic("t4", 10, trending = true),
        )
        // Keyword topics first, even the weak k2 (owner, 2026-10-30); trending ones fill up to five, best first.
        assertEquals(listOf("k1", "k3", "k2", "t2", "t1"), Selection.select(topics).map { it.id })
        // Five keyword topics leave no slot.
        val five = (1..5).map { topic("k$it", 60 + it) }
        assertEquals(five.map { it.id }.reversed(), Selection.select(five + topic("t", 99, trending = true)).map { it.id })
    }

    @Test
    fun aTrendingStoryNeedsNoKeyword() {
        fun source(url: String) = SearchAnswer.SearchSource(title = "t", publisher = "p", url = url, language = "en", published = "2026-10-29")
        val answer = SearchAnswer(
            searchedAt = "2026-10-30",
            stories = listOf(
                SearchAnswer.Story(keywords = listOf("chess"), sources = listOf(source("https://a.example.com/chess-news"))),
                SearchAnswer.Story(trending = true, sources = listOf(source("https://b.example.com/big-news"))),
                SearchAnswer.Story(keywords = listOf("not the owner's"), sources = listOf(source("https://c.example.com/other-news"))),
            ),
        )
        val (checked, issues) = SearchCheck.apply(answer, ValidationContext(listOf("chess"), today))
        assertEquals(listOf(false, true), checked.stories.map { it.trending })
        assertEquals(listOf(Issue.Kind.STORY_NOT_LINKED_TO_KEYWORDS), issues.map { it.kind })
    }

    @Test
    fun theSearchAsksForTrendingNewsAndTopicModeMayPickNone() {
        val input = PromptInput(listOf("chess"), emptyList(), AttentionMode.BALANCED, Persona.OFF, today)
        val keywords = assertIs<SearchRequest.Result.Built>(SearchRequest.build(input)).request
        assertTrue("Then add today's most popular news stories" in keywords.messages.single().content)
        assertTrue("\nTRENDING\n" in keywords.systemPrompt)

        val none = assertIs<SearchRequest.Result.Built>(SearchRequest.build(input.copy(keywords = emptyList(), mode = DigestMode.TOPICS))).request
        assertTrue(none.messages.single().content.startsWith("Find today's most popular news stories."))
        // Keyword mode still needs one keyword.
        assertIs<SearchRequest.Result.OverLimit>(SearchRequest.build(input.copy(keywords = emptyList())))
    }

    @Test
    fun aTrendingCardHasNoKeywords() {
        val answer = SearchAnswer(
            searchedAt = "2026-10-30",
            stories = listOf(
                SearchAnswer.Story(
                    story = 1, trending = true, attention = 30,
                    sources = listOf(SearchAnswer.SearchSource(n = 1, title = "t", publisher = "p", url = "https://b.example.com/big-news", published = "2026-10-29")),
                ),
            ),
        )
        val written = WriterAnswer(
            templateVersion = 2,
            topics = listOf(WriterAnswer.WriterTopic(story = 1, topicTag = "Big news", title = "Big news", summary = "It happened.", detail = null, keywords = listOf("chess"), relevance = 40, sources = listOf(1))),
        )
        val (digest, _) = Writer.toDigest(written, answer, PromptInput(listOf("chess"), emptyList(), AttentionMode.BALANCED, Persona.OFF, today))
        val card = digest.topics.single()
        assertTrue(card.trending)
        assertEquals(emptyList(), card.keywords)
        assertEquals(70, card.score)
    }
}
