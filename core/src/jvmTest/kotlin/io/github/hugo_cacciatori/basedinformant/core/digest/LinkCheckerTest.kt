package io.github.hugo_cacciatori.basedinformant.core.digest

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

/** No network: every answer comes from a mock engine. */
class LinkCheckerTest {
    private fun checker(handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpLinkChecker(linkCheckHttpClient(MockEngine(handler)), retryDelayMillis = 0, readPages = false)

    private fun status(url: String = "https://news.example.com/2026/10/story", handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        runBlocking { checker(handler).check(url).status }

    @Test
    fun okIsAlive() {
        assertEquals(LinkStatus.ALIVE, status { respond("", HttpStatusCode.OK) })
    }

    @Test
    fun missingPagesAreDead() {
        assertEquals(LinkStatus.DEAD, status { respond("", HttpStatusCode.NotFound) })
        assertEquals(LinkStatus.DEAD, status { respond("", HttpStatusCode.Gone) })
    }

    @Test
    fun blockedOrFailingSitesAreUnknownAndKept() {
        for (code in listOf(401, 403, 429, 500, 503)) {
            assertEquals(LinkStatus.UNKNOWN, status { respond("", HttpStatusCode.fromValue(code)) }, "HTTP $code")
        }
        assertEquals(LinkStatus.UNKNOWN, status { throw IOException("connection reset") })
    }

    @Test
    fun anUnknownHostIsDead() {
        assertEquals(LinkStatus.DEAD, status { throw IOException("wrapped", UnknownHostException("news.example.com")) })
    }

    @Test
    fun aRedirectToTheHomepageIsDead() {
        assertEquals(LinkStatus.DEAD, status { respond("", HttpStatusCode.MovedPermanently, headersOf("Location", "/")) })
        assertEquals(LinkStatus.DEAD, status { respond("", HttpStatusCode.Found, headersOf("Location", "https://www.example.com/")) })
    }

    @Test
    fun aRedirectToAnotherArticleIsFollowed() {
        val result = status { req ->
            if (req.url.encodedPath == "/2026/10/story") respond("", HttpStatusCode.MovedPermanently, headersOf("Location", "/articles/story"))
            else respond("", HttpStatusCode.OK)
        }
        assertEquals(LinkStatus.ALIVE, result)
    }

    @Test
    fun endlessRedirectsAreUnknown() {
        var n = 0
        assertEquals(LinkStatus.UNKNOWN, status { respond("", HttpStatusCode.Found, headersOf("Location", "/hop/${n++}")) })
    }

    @Test
    fun aBriefFailureIsCheckedAgainBeforeDropping() {
        var calls = 0
        val result = status { calls++; if (calls == 1) respond("", HttpStatusCode.NotFound) else respond("", HttpStatusCode.OK) }
        assertEquals(LinkStatus.ALIVE, result)
        assertEquals(2, calls)
    }

    @Test
    fun aLivePagesMetadataDateIsRead() = runBlocking {
        val html = """<html><head><meta property="article:published_time" content="2026-04-08T23:12:00Z"></head></html>"""
        val checker = HttpLinkChecker(linkCheckHttpClient(MockEngine { req ->
            if (req.method == HttpMethod.Head) respond("", HttpStatusCode.OK) else respond(html, HttpStatusCode.OK)
        }), retryDelayMillis = 0)
        val result = checker.check("https://news.example.com/2026/10/story")
        assertEquals(LinkStatus.ALIVE, result.status)
        assertEquals(LocalDate(2026, 4, 8), result.pageDate)
    }

    @Test
    fun aRedirectToAParentOrSectionPageIsDead() {
        // A made-up BGR URL redirected to the tech section (2026-10-18).
        assertEquals(LinkStatus.DEAD, status("https://www.news.example.com/tech/made-up-story/") { req ->
            if (req.url.encodedPath == "/tech/made-up-story/") respond("", HttpStatusCode.MovedPermanently, headersOf("Location", "/category/tech/"))
            else respond("", HttpStatusCode.OK)
        })
        assertEquals(LinkStatus.DEAD, status { respond("", HttpStatusCode.Found, headersOf("Location", "https://news.example.com/2026/")) })
    }

    @Test
    fun aRedirectWithinASectionIsFollowed() {
        assertEquals(LinkStatus.ALIVE, status("https://news.example.com/category/tech/old-slug") { req ->
            if (req.url.encodedPath.endsWith("old-slug")) respond("", HttpStatusCode.MovedPermanently, headersOf("Location", "/category/tech/new-slug"))
            else respond("", HttpStatusCode.OK)
        })
    }

    @Test
    fun placeholderUrlsAreDeadWithoutARequest() {
        var calls = 0
        for (url in listOf("https://www.espn.com/f1/story/_/id/xxxxxxxx", "https://news.example.com/news/axxx", "https://news.example.com/XXX/story")) {
            assertEquals(LinkStatus.DEAD, status(url) { calls++; respond("", HttpStatusCode.OK) }, url)
        }
        assertEquals(0, calls)
        assertEquals(LinkStatus.ALIVE, status("https://news.example.com/2026/10/xbox-story") { respond("", HttpStatusCode.OK) })
    }

    @Test
    fun anEmpty200IsDeadButAnEmpty202StaysUnknown() = runBlocking {
        fun pageChecker(body: String, code: HttpStatusCode) = HttpLinkChecker(linkCheckHttpClient(MockEngine { req ->
            if (req.method == HttpMethod.Head) respond("", code) else respond(body, code)
        }), retryDelayMillis = 0)
        val url = "https://news.example.com/2026/10/story"
        assertEquals(LinkResult(LinkStatus.DEAD, "empty page"), pageChecker(" \n", HttpStatusCode.OK).check(url))
        // CloudFront's bot challenge: a real page answered with an empty 202 (bas.ac.uk, 2026-10-18).
        assertEquals(LinkResult(LinkStatus.UNKNOWN, "empty answer (HTTP 202)"), pageChecker("", HttpStatusCode.Accepted).check(url))
        assertEquals(LinkStatus.ALIVE, pageChecker("<html>story</html>", HttpStatusCode.Accepted).check(url).status)
    }

    @Test
    fun aRefusedHeadFallsBackToGet() {
        val methods = mutableListOf<HttpMethod>()
        val result = status { req ->
            methods += req.method
            if (req.method == HttpMethod.Head) respond("", HttpStatusCode.MethodNotAllowed) else respond("", HttpStatusCode.OK)
        }
        assertEquals(LinkStatus.ALIVE, result)
        assertEquals(listOf(HttpMethod.Head, HttpMethod.Get), methods)
    }

    @Test
    fun aHomepageLinkItselfIsNotTreatedAsARedirectToHome() {
        assertEquals(LinkStatus.ALIVE, status("https://example.com/") { req ->
            if (req.url.host == "example.com") respond("", HttpStatusCode.MovedPermanently, headersOf("Location", "https://www.example.com/"))
            else respond("", HttpStatusCode.OK)
        })
    }
}

class LinkStageTest {
    private val day = LocalDate(2026, 10, 18)
    private fun source(url: String) = Source("t", "p", url, "en", day)
    private fun topic(id: String, vararg urls: String) = Topic(
        id, null, null, id, "s", null, listOf("k"), 70, null, urls.map(::source),
    )

    @Test
    fun deadSourcesAndTopicsLeftWithoutOneAreDropped() = runBlocking {
        val digest = Digest(1, day, null, null, listOf(
            topic("a", "https://a.example.com/ok", "https://a.example.com/gone"),
            topic("b", "https://b.example.com/gone"),
            topic("c", "https://c.example.com/blocked"),
        ))
        val checker = LinkChecker { url ->
            when {
                url.endsWith("/ok") -> LinkResult(LinkStatus.ALIVE)
                url.endsWith("/gone") -> LinkResult(LinkStatus.DEAD, "HTTP 404")
                else -> LinkResult(LinkStatus.UNKNOWN)
            }
        }
        val (checked, issues, report) = LinkStage.apply(digest, checker)
        assertEquals(listOf("a", "c"), checked.topics.map { it.id })
        assertEquals(listOf("https://a.example.com/ok"), checked.topics[0].sources.map { it.url })
        assertEquals(
            listOf(Issue.Kind.SOURCE_DEAD_LINK, Issue.Kind.SOURCE_DEAD_LINK, Issue.Kind.TOPIC_NO_LIVE_SOURCE),
            issues.map { it.kind },
        )
        assertEquals(LinkReport(1, 1, 2, listOf("https://a.example.com/gone (HTTP 404)", "https://b.example.com/gone (HTTP 404)")), report)
        assertEquals(0.5, report.keptShare)
    }

    @Test
    fun pageDatesCorrectOrDropSources() = runBlocking {
        val window = RecencyWindow.of(day, null)
        val digest = Digest(1, day, null, null, listOf(
            topic("old", "https://a.example.com/april"),
            topic("fixed", "https://b.example.com/misdated", "https://b.example.com/nodate"),
        ))
        val checker = LinkChecker { url ->
            when {
                url.endsWith("/april") -> LinkResult(LinkStatus.ALIVE, pageDate = LocalDate(2026, 4, 8))
                url.endsWith("/misdated") -> LinkResult(LinkStatus.ALIVE, pageDate = LocalDate(2026, 10, 12))
                else -> LinkResult(LinkStatus.ALIVE)
            }
        }
        val (checked, issues, report) = LinkStage.apply(digest, checker, window)
        assertEquals(listOf("fixed"), checked.topics.map { it.id })
        assertEquals(listOf(LocalDate(2026, 10, 12), day), checked.topics.single().sources.map { it.published })
        assertEquals(
            listOf(Issue.Kind.SOURCE_PAGE_DATE_OUTSIDE_WINDOW, Issue.Kind.TOPIC_NO_LIVE_SOURCE, Issue.Kind.SOURCE_DATE_CORRECTED),
            issues.map { it.kind },
        )
        assertEquals(2, report.datesFound)
        assertEquals(1, report.datesCorrected)
        assertEquals(1, report.datesOutsideWindow)
    }
}
