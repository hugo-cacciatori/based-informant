package io.github.hugo_cacciatori.basedinformant.core.render

import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.Source
import io.github.hugo_cacciatori.basedinformant.core.digest.Topic
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DigestViewTest {
    private val meta = RenderMeta(personaDisplayName = null, model = "test:model")

    private fun source(url: String) = Source("S", "Example Wire", url, "en", LocalDate(2026, 10, 17))

    private fun topic(id: String, vararg urls: String) = Topic(
        id = id, title = "Title $id", summary = "Summary $id.",
        detail = null, keywords = listOf("chess"), score = 50, scoreParts = null, sources = urls.map(::source),
    )

    private fun digest(vararg topics: Topic) = Digest(1, LocalDate(2026, 10, 18), "Hello.", null, topics.toList())

    @Test
    fun headerAndFooterCarryTheDigestFacts() {
        val view = DigestViews.build(digest(topic("a", "https://example.com/a")), meta.copy(personaDisplayName = "Roach King"))
        assertEquals("News digest · 18 Oct 2026 · 1 topic", view.subject)
        assertEquals("18 Oct 2026 · 1 topic · Roach King", view.headerLine)
        assertEquals("Hello.", view.opening)
        assertNull(view.closing)
        assertNull(view.emptyMessage)
        assertEquals(listOf("Model: test:model · template v1", DigestViews.AI_NOTE), view.footerLines)
    }

    @Test
    fun cardsKeepTheDigestOrderAndLabelTheirSources() {
        val view = DigestViews.build(
            digest(
                topic("a", "https://example.com/a"),
                topic("b", "https://example.com/b", "javascript:alert(1)"),
            ),
            meta,
        )
        assertEquals(listOf("Title a", "Title b"), view.cards.map { it.title })
        assertEquals("Matched: chess", view.cards[0].matched)
        // A trending card names no keyword (owner, 2026-10-29).
        val trending = DigestViews.build(digest(topic("t", "https://example.com/t").copy(keywords = emptyList(), trending = true)), meta)
        assertEquals("Trending", trending.cards.single().matched)
        assertEquals("1 source", view.cards[0].singleSourceLabel)
        assertNull(view.cards[1].singleSourceLabel)
        assertEquals(SourceView("Example Wire, 17 Oct 2026, en", "https://example.com/b"), view.cards[1].sources[0])
        assertNull(view.cards[1].sources[1].url)
    }

    @Test
    fun anEmptyDigestHasItsMessage() {
        val view = DigestViews.build(digest(), meta)
        assertEquals("News digest · 18 Oct 2026 · 0 topics", view.subject)
        assertEquals(DigestViews.EMPTY, view.emptyMessage)
        assertEquals(emptyList(), view.cards)
    }
}
