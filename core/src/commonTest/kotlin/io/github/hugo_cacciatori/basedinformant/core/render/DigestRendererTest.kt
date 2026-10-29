package io.github.hugo_cacciatori.basedinformant.core.render

import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.Source
import io.github.hugo_cacciatori.basedinformant.core.digest.Topic
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DigestRendererTest {
    private val date = LocalDate(2026, 10, 18)
    private val meta = RenderMeta(personaDisplayName = null, model = "test:model")

    private fun topic(title: String = "A title", url: String = "https://example.com/a", score: Int = 77) = Topic(
        id = "a", title = title, summary = "A summary.",
        detail = "More text.", keywords = listOf("chess", "fusion energy"), score = score, scoreParts = null,
        sources = listOf(Source("S", "Example Wire", url, "en", LocalDate(2026, 10, 17))),
    )

    private fun digest(vararg topics: Topic) = Digest(1, date, "Hello.", "Bye.", topics.toList())

    @Test
    fun subjectFollowsTheDecidedFormat() {
        assertEquals("News digest · 18 Oct 2026 · 1 topic", DigestRenderer.render(digest(topic()), meta).subject)
        assertEquals("News digest · 18 Oct 2026 · 0 topics", DigestRenderer.render(digest(), meta).subject)
        assertEquals("5 Jan 2027", DigestViews.formatDate(LocalDate(2027, 1, 5)))
    }

    @Test
    fun modelTextIsEscaped() {
        val html = DigestRenderer.render(digest(topic(title = "<script>alert('x')</script> & co")), meta).html
        assertFalse("<script>" in html)
        assertTrue("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; co" in html)
    }

    @Test
    fun onlyHttpsBecomesALink() {
        val html = DigestRenderer.render(digest(topic(url = "javascript:alert(1)")), meta).html
        assertFalse("href=\"javascript" in html)
        assertFalse("javascript:" in html)
        assertTrue("href=\"https://example.com/a\"" in DigestRenderer.render(digest(topic()), meta).html)
    }

    @Test
    fun cardShowsMatchedKeywordsButNeverTheScore() {
        val rendered = DigestRenderer.render(digest(topic(score = 77)), meta)
        assertTrue("Matched: chess, fusion energy" in rendered.html)
        assertTrue("Matched: chess, fusion energy" in rendered.text)
        assertFalse("77" in rendered.html)
        assertFalse("77" in rendered.text)
    }

    @Test
    fun noScriptsNoRemoteContent() {
        val html = DigestRenderer.render(digest(topic()), meta).html
        assertFalse("<script" in html)
        assertFalse("<img" in html)
        assertFalse("<link" in html)
        assertFalse("http://" in html)
    }

    @Test
    fun personaNameAndFooterShow() {
        val rendered = DigestRenderer.render(digest(topic()), meta.copy(personaDisplayName = "Roach King"))
        assertTrue("Roach King" in rendered.html)
        assertTrue("Model: test:model · template v1" in rendered.text)
        assertTrue("written by AI" in rendered.text)
    }

    @Test
    fun aSingleSourceTopicIsLabeled() {
        val single = DigestRenderer.render(digest(topic()), meta)
        assertTrue("1 source: " in single.html)
        assertTrue("1 source:\n- Example Wire" in single.text)
        val two = topic().let { it.copy(sources = it.sources + it.sources.single().copy(url = "https://example.com/b")) }
        assertFalse("1 source" in DigestRenderer.render(digest(two), meta).html)
    }

    @Test
    fun emptyDigestRendersAMessage() {
        val rendered = DigestRenderer.render(digest(), meta)
        assertTrue("Nothing met your criteria" in rendered.html)
        assertTrue("Nothing met your criteria" in rendered.text)
    }
}
