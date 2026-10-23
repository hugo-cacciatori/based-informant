package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageDateTest {
    @Test
    fun openGraphArticleTimeWins() {
        val html = """<head><meta name="date" content="2026-10-11">
            <meta property="article:published_time" content="2026-04-08T23:12:00+05:30"></head>"""
        assertEquals(LocalDate(2026, 4, 8), PageDate.extract(html))
    }

    @Test
    fun jsonLdIsUsedWithoutOpenGraph() {
        val html = """<script type="application/ld+json">{"@type":"NewsArticle","dateModified":"2026-10-15",
            "datePublished": "2026-10-10T09:00:00Z"}</script><meta name="date" content="2026-10-17">"""
        assertEquals(LocalDate(2026, 10, 10), PageDate.extract(html))
    }

    @Test
    fun otherMetaNamesAndQuoteStyles() {
        assertEquals(LocalDate(2026, 10, 16), PageDate.extract("<META NAME='parsely-pub-date' CONTENT='2026-10-16T13:25:00Z'>"))
        assertEquals(LocalDate(2026, 10, 12), PageDate.extract("""<meta content="2026-10-12" name="dc.date">"""))
        assertEquals(LocalDate(2026, 10, 13), PageDate.extract("""<meta itemprop="datePublished" content="2026-10-13">"""))
    }

    /** The two pages sonar misdated on 2026-10-18, as their HTML shows the dates. */
    @Test
    fun datesWrittenWithMonthNames() {
        assertEquals(
            LocalDate(2026, 4, 8),
            PageDate.extract("""<meta property="article:published_time" content="Apr 08, 2026, 11:09:00 PM IST.000Z">"""),
        )
        assertEquals(LocalDate(2026, 10, 10), PageDate.extract("""<dt>Date:</dt><dd id="date_posted">October 10, 2026</dd>"""))
        assertEquals(LocalDate(2026, 10, 10), PageDate.extract("""<span class="pub-date">10 Oct 2026</span>"""))
        assertEquals(LocalDate(2026, 9, 3), PageDate.parse("Sept. 3, 2026"))
    }

    @Test
    fun noUsableDateIsNull() {
        assertNull(PageDate.extract("<html><p>Published 10 October 2026</p></html>"))
        assertNull(PageDate.extract("""<div class="related-date">October 10, 2026</div>"""))
        assertNull(PageDate.parse("Octember 10, 2026"))
        assertNull(PageDate.parse("Mayday 10, 2026"))
        assertNull(PageDate.extract("""<meta name="description" content="2026-10-10 news">"""))
        assertNull(PageDate.parse("2026-13-40"))
    }
}
