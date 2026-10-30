package io.github.hugo_cacciatori.basedinformant.core.search

import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline: the homepage rescue (02-ai-call.md section 8) on a made-up front page. */
class HomepageRescueTest {
    private val page = """
        <html><body>
        <a href="/">Home</a>
        <a href="/International/live-updates/">Live updates</a>
        <a class="story" href="/International/death-toll-climbs-spain-train-crash/story?id=1234#top"><h2>Death toll climbs to at least 69 in Spain&#39;s train crash</h2></a>
        <a href="https://othersite.example.com/death-toll-climbs-spanish-train-crash-officials-probe">Death toll climbs as Spanish officials probe train crash</a>
        <a href="/US/alexander-brothers-each-sentenced-30-years">Brothers sentenced</a>
        </body></html>
    """.trimIndent()

    private fun source(title: String, url: String, n: Int) =
        SearchAnswer.SearchSource(n = n, title = title, publisher = "News", url = url, published = "2026-10-29")

    @Test
    fun aHomepageSourceTakesTheMatchingArticleLinkOnTheSameSite() = runBlocking {
        val answer = SearchAnswer(
            searchedAt = "2026-10-30",
            stories = listOf(
                SearchAnswer.Story(
                    story = 1, trending = true,
                    sources = listOf(
                        source("Death toll climbs to at least 69 as Spanish officials probe high-speed train crash", "https://abcnews.example.com/", 1),
                        source("Alexander brothers each sentenced to 30 years for sex trafficking", "https://abcnews.example.com", 2),
                        source("Pope visits the island", "https://abcnews.example.com/", 3),
                        source("Already an article", "https://abcnews.example.com/US/already-an-article", 4),
                    ),
                ),
            ),
        )
        val read = mutableListOf<String>()
        val (rescued, issues) = HomepageRescue.apply(answer) { url -> read += url; page }
        // The headline text, or the words in the link's address, match most of the title; other sites and listings never count.
        assertEquals(
            listOf(
                "https://abcnews.example.com/International/death-toll-climbs-spain-train-crash/story?id=1234",
                "https://abcnews.example.com/US/alexander-brothers-each-sentenced-30-years",
                "https://abcnews.example.com/",
                "https://abcnews.example.com/US/already-an-article",
            ),
            rescued.stories.single().sources.map { it.url },
        )
        assertEquals(listOf(Issue.Kind.SOURCE_HOMEPAGE_RESCUED, Issue.Kind.SOURCE_HOMEPAGE_RESCUED), issues.map { it.kind })
        assertEquals(listOf("https://abcnews.example.com/", "https://abcnews.example.com"), read, "each homepage read once")
    }

    @Test
    fun onlyAFrontPageIsAHomepage() {
        assertTrue(HomepageRescue.isHomepage("https://www.cnn.com/"))
        assertTrue(HomepageRescue.isHomepage("https://www.cnn.com"))
        assertFalse(HomepageRescue.isHomepage("https://www.cnn.com/world"))
        assertFalse(HomepageRescue.isHomepage("http://www.cnn.com/"))
    }
}
