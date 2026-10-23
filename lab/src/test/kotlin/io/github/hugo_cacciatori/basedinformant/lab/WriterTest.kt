package io.github.hugo_cacciatori.basedinformant.lab

import io.github.hugo_cacciatori.basedinformant.core.digest.RecencyWindow
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: the call redesign's writer pieces (ai-call.md section 12) on synthetic data. */
class WriterTest {
    private val repo = File("").absoluteFile.parentFile
    private val synthetic = SearchAnswer.read(repo.resolve("lab/inputs/search-answer-synthetic.json").readText()).numbered()
    private val today = LocalDate(2026, 10, 18)

    @Test
    fun `stories and sources are numbered in order across the answer`() {
        assertEquals(listOf(1, 2, 3, 4), synthetic.stories.map { it.story })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), synthetic.stories.flatMap { s -> s.sources.map { it.n } })
    }

    @Test
    fun `a saved v1 answer becomes a search answer`() {
        val answer = SearchAnswer.read(repo.resolve("core/src/jvmTest/resources/fixtures/good.json").readText())
        assertTrue(answer.stories.isNotEmpty())
        assertTrue(answer.stories.all { it.sources.isNotEmpty() && it.sources.first().facts.isNotEmpty() })
    }

    @Test
    fun `persona notes go only to a persona voice`() {
        fun request(persona: Persona) = Writer.request(
            "m", persona, synthetic, RecencyWindow.of(today, null), "balanced", listOf("chess"), emptyList(), listOf("Chess rules"),
        ).messages.single().content
        assertTrue("electricity bill" in request(Persona.ROACH_KING))
        assertFalse("electricity bill" in request(Persona.OFF))
        assertTrue("known_topic_tags" in request(Persona.OFF))
    }

    @Test
    fun `five words in a row from a persona note count as a copied quote`() {
        val notes = listOf("Forty minutes of plasma is cool and all, but call me when my electricity bill goes down, chat.")
        assertEquals(
            listOf("call me when my electricity", "me when my electricity bill"),
            Writer.copiedQuotes(listOf("Sure, call me when my electricity bill drops."), notes),
        )
        assertTrue(Writer.copiedQuotes(listOf("Forty minutes of plasma, and the bill stays the same."), notes).isEmpty())
    }

    @Test
    fun `a sentence copying a persona note is deleted and the rest kept`() {
        val written = WriterAnswer(
            2, "Hi chat.", null, null, null,
            listOf(
                WriterAnswer.WriterTopic(
                    1, "Stellarator research", "Record", "A plasma lasted 40 minutes. Call me when my electricity bill goes down, honestly.",
                    "Forty minutes of plasma is cool and all! The lab plans more.", listOf("fusion energy"), 50, listOf(1),
                ),
            ),
        )
        val (cleaned, deleted) = Writer.removeCopies(written, synthetic)
        assertEquals("A plasma lasted 40 minutes.", cleaned.topics.single().summary)
        assertEquals("The lab plans more.", cleaned.topics.single().detail)
        assertEquals(2, deleted.size)
        assertEquals(written, Writer.removeCopies(written, synthetic.withoutPersonaNotes()).first)
    }

    @Test
    fun `a long topic tag is cut at a word`() {
        assertEquals("Deep-sea mining rules", Writer.cutTag(" Deep-sea mining rules "))
        val cut = Writer.cutTag("International seabed authority moratorium vote on mining")
        assertTrue(cut.length <= Writer.MAX_TAG_CHARS && !cut.endsWith(" "))
        assertEquals("International seabed authority", cut)
    }

    @Test
    fun `the writer's topics take their sources from the search answer`() {
        val written = Writer.parse(
            """Here: {"template_version": 2, "opening": "Hi.", "closing": "Bye.", "subject": "S", "card_summary": "C", "topics": [
               {"story": 2, "topic_tag": "Deep-sea mining rules", "title": "Vote set", "summary": "A vote is set.", "detail": null,
                "keywords": ["deep-sea mining"], "relevance": 50, "sources": [3, 1]},
               {"story": 9, "topic_tag": "x", "title": "t", "summary": "s", "keywords": ["chess"], "relevance": 10, "sources": [5]}]}""",
        )
        assertNotNull(written)
        val (digest, findings) = Writer.toDigest(written, synthetic, today)
        val topic = digest.topics.single()
        assertEquals(listOf("https://ocean.example.com/seabed-vote"), topic.sources.map { it.url })
        assertEquals(80, topic.score)
        assertEquals(setOf("UNKNOWN_STORY", "FOREIGN_SOURCE"), findings.map { it.kind }.toSet())
    }

    @Test
    fun `an answer with no JSON is unreadable`() {
        assertNull(Writer.parse("Sorry, I cannot help."))
    }

    @Test
    fun `article text keeps paragraphs and drops menus and scripts`() {
        val html = """<html><nav><p>Home | World | Sport | a long navigation paragraph that should go away</p></nav>
            <script>var x = "<p>not text</p>";</script>
            <article><h1>Stellarator record &amp; more</h1><p>The stellarator held a stable plasma for 40 minutes, the lab said on Tuesday.</p>
            <p>Short.</p><p>Engineers credit a new tungsten wall coating &#8212; it sheds less material.</p></article>
            <footer><p>Copyright notice that is long enough to count as a paragraph here.</p></footer></html>"""
        val text = ArticleText.extract(html)!!
        assertTrue("40 minutes" in text && "tungsten wall coating — it sheds" in text)
        assertFalse("navigation" in text || "not text" in text || "Copyright" in text || "Short." in text)
        assertNull(ArticleText.extract("<html><body><div>nothing</div></body></html>"))
    }
}
