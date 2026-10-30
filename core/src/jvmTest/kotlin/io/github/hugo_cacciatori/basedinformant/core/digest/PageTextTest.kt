package io.github.hugo_cacciatori.basedinformant.core.digest

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: the readable text of a page (02-ai-call.md section 12), on synthetic HTML. */
class PageTextTest {
    @Test
    fun keepsTheArticleAndDropsMenusScriptsAndShortLines() {
        val html = """<html><nav><p>Home | World | Sport | a long navigation paragraph that should go away</p></nav>
            <script>var x = "<p>not text</p>";</script>
            <article><h1>Stellarator record &amp; more</h1><p>The stellarator held a stable plasma for 40 minutes, the lab said on Tuesday.</p>
            <p>Short.</p><p>Engineers credit a new tungsten wall coating &#8212; it sheds less material.</p>
            <p>Two outside physicists called the result real but incremental, and net energy gain is still years away.</p></article>
            <footer><p>Copyright notice that is long enough to count as a paragraph here.</p></footer></html>"""
        val text = PageText.extract(html)!!
        assertTrue("40 minutes" in text && "tungsten wall coating — it sheds" in text && "Stellarator record & more" in text)
        assertFalse("navigation" in text || "not text" in text || "Copyright" in text || "Short." in text)
        assertNull(PageText.extract("<html><body><div>nothing</div></body></html>"))
    }

    @Test
    fun aPageReadOnlyInPartGivesNoScraps() {
        // GB News, 2026-10-30: the read stopped inside the logo, and "<path d=..." became the page text.
        val cut = """<html><body><header><a><svg viewBox="0 0 153 55"><g><path d="M37.7926 39.5561C35.0524 41.5993 31.3933 42.6209 27"""
        assertNull(PageText.extract(cut))
        // Under the floor: scraps, not an article.
        assertNull(PageText.extract("<html><body><p>Cookie settings and a short line here.</p></body></html>"))
    }

    @Test
    fun keepsStatusLinesBetweenLineBreaks() {
        // A synthetic notice shaped like an observatory's daily update: status lines between <br> tags, no paragraphs.
        val html = """<html><body><b>EXAMPLE PEAK</b> (VNUM #000001)<br/>Current Volcano Alert Level: WATCH<br/>
            Current Aviation Color Code: ORANGE<br/><br/><span>Summary: Slow eruption of lava within the crater continues.</span><br/>
            <b>OTHER PEAK</b><br/>Current Volcano Alert Level: ADVISORY<br/>Current Aviation Color Code: YELLOW<br/></body></html>"""
        val lines = PageText.extract(html)!!.lines()
        assertEquals(
            listOf(
                "EXAMPLE PEAK (VNUM #000001)",
                "Current Volcano Alert Level: WATCH",
                "Current Aviation Color Code: ORANGE",
                "Summary: Slow eruption of lava within the crater continues.",
                "OTHER PEAK",
                "Current Volcano Alert Level: ADVISORY",
                "Current Aviation Color Code: YELLOW",
            ),
            lines,
        )
    }

    @Test
    fun aLongPageIsCutAtALineOrSentenceEnd() {
        val html = "<p>" + (1..200).joinToString("</p><p>") { "Sentence number $it of a long article text." } + "</p>"
        val text = PageText.extract(html, maxChars = 500)!!
        assertTrue(text.length <= 500 && text.endsWith("text."))
    }

    @Test
    fun theLinkCheckKeepsThePageText() = runBlocking {
        val checker = HttpLinkChecker(linkCheckHttpClient(MockEngine { req ->
            if (req.method == HttpMethod.Head) respond("", HttpStatusCode.OK)
            else respond("<html><body><p>$ARTICLE</p></body></html>", HttpStatusCode.OK)
        }), retryDelayMillis = 0)
        assertEquals(ARTICLE, checker.check("https://news.example.com/2026/10/vote").pageText)
    }

    private companion object {
        const val ARTICLE = "The council agreed on Monday to vote on a ten-year moratorium on commercial deep-sea mining at its November session. " +
            "Twelve member states back the pause, and two companies holding exploration contracts said it would breach existing agreements."
    }
}
