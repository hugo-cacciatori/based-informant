package io.github.hugo_cacciatori.basedinformant.core.render

import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.Source
import io.github.hugo_cacciatori.basedinformant.core.digest.Topic
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SizeCapTest {
    private val meta = RenderMeta(personaDisplayName = null, model = "test:model")

    private fun topic(id: String, score: Int, detailChars: Int?) = Topic(
        id = id, continues = null, newDevelopment = null, title = "Title $id", summary = "Summary $id.",
        detail = detailChars?.let { "Detail $id " + "x".repeat(it) }, keywords = listOf("chess"), score = score,
        scoreParts = null,
        sources = listOf(Source("S", "Example Wire", "https://example.com/$id", "en", LocalDate(2026, 10, 17))),
    )

    private fun digest(vararg topics: Topic) = Digest(1, LocalDate(2026, 10, 18), "Hello.", "Bye.", topics.toList())

    private fun size(html: String) = html.encodeToByteArray().size

    @Test
    fun aDigestUnderTheCapIsWhole() {
        val rendered = DigestRenderer.render(digest(topic("a", 90, 100), topic("b", 50, 100)), meta)
        assertNull(rendered.shortened)
        assertFalse("Shortened" in rendered.html)
    }

    @Test
    fun theLowestScoredDetailGoesFirst() {
        val d = digest(topic("a", 90, 2_000), topic("b", 50, 2_000), topic("c", 70, 2_000))
        val full = size(DigestRenderer.render(d, meta).html)
        val rendered = DigestRenderer.render(d, meta, maxHtmlBytes = full - 1_000)
        assertEquals(Shortened(details = 1, topics = 0), rendered.shortened)
        assertTrue("Detail a" in rendered.html)
        assertFalse("Detail b" in rendered.html)
        assertTrue("Detail c" in rendered.html)
        assertTrue("1 longer text left out" in rendered.text)
    }

    @Test
    fun topicsGoOnlyAfterEveryDetailLowestScoreFirst() {
        val d = digest(topic("a", 90, 200), topic("b", 50, 200), topic("c", 60, 200))
        // Room for one topic without its detail, plus the footer note.
        val cap = size(DigestRenderer.render(digest(topic("a", 90, null)), meta).html) + 300
        val rendered = DigestRenderer.render(d, meta, maxHtmlBytes = cap)
        assertEquals(Shortened(details = 3, topics = 2), rendered.shortened)
        assertTrue("Title a" in rendered.html)
        assertFalse("Title b" in rendered.html)
        assertFalse("Title c" in rendered.html)
        assertEquals("News digest · 18 Oct 2026 · 1 topic", rendered.subject)
        assertTrue("3 longer texts and 2 topics left out" in rendered.html)
        assertTrue("3 longer texts and 2 topics left out" in rendered.text)
        assertTrue(size(rendered.html) <= cap)
    }

    @Test
    fun aTieDropsTheLaterTopic() {
        val d = digest(topic("a", 50, null), topic("b", 50, null))
        val cap = size(DigestRenderer.render(digest(topic("a", 50, null)), meta).html) + 300
        val rendered = DigestRenderer.render(d, meta, maxHtmlBytes = cap)
        assertTrue("Title a" in rendered.html)
        assertFalse("Title b" in rendered.html)
    }

    @Test
    fun anOversizedDigestFitsTheRealCap() {
        val d = digest(*Array(8) { topic("t$it", 90 - it, 20_000) })
        assertTrue(size(DigestRenderer.render(d, meta, maxHtmlBytes = Int.MAX_VALUE).html) > DigestRenderer.MAX_HTML_BYTES)
        val rendered = DigestRenderer.render(d, meta)
        assertTrue(size(rendered.html) <= DigestRenderer.MAX_HTML_BYTES)
        assertEquals(0, rendered.shortened?.topics)
        assertEquals(8, Regex("Title t").findAll(rendered.html).count())
    }
}
