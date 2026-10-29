package io.github.hugo_cacciatori.basedinformant.core.search

import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Based's free feeds (02-ai-call.md section 4, owner 2026-10-30), on made-up feed texts: no network. */
class BasedFeedsTest {
    private val today = LocalDate(2026, 10, 30)

    private val rss = """
        <?xml version="1.0" encoding="utf-8"?><rss version="2.0"><channel><title>Example</title>
        <item><title><![CDATA[Councils ban electric cars from town centres]]></title><link>https://news.example.com/a</link>
          <pubDate>Thu, 29 Oct 2026 23:10:13 +0000</pubDate><description><![CDATA[<p>Three councils voted &amp; agreed.</p>]]></description></item>
        <item><title>Paper straws worse than plastic</title><link>https://news.example.com/b</link>
          <pubDate>Wed, 28 Oct 2026 08:00:00 GMT</pubDate><description>A study says so.</description></item>
        <item><title>An old story</title><link>https://news.example.com/c</link><pubDate>Mon, 05 Oct 2026 08:00:00 GMT</pubDate></item>
        <item><title>No link</title><pubDate>Thu, 29 Oct 2026 08:00:00 GMT</pubDate></item>
        </channel></rss>
    """.trimIndent()

    private val truth = """
        [{"created_at":"2026-10-29T22:42:09.852Z","url":"https://social.example.com/@leader/1","content":"<p>Free speech is under attack in Europe and we will not stand for it, believe me!</p>","reblog":null},
         {"created_at":"2026-10-29T22:41:00.000Z","url":"https://social.example.com/@leader/2","content":"<p>RT: https://social.example.com/users/leader/statuses/9 GREAT NEWS</p>","reblog":null},
         {"created_at":"2026-10-29T22:40:00.000Z","url":"https://social.example.com/@leader/3","content":"<p>🚨 MONTANA!</p>","reblog":null},
         {"created_at":"2026-10-29T22:39:00.000Z","url":"https://social.example.com/@leader/4","content":"","reblog":{"id":"5"}}]
    """.trimIndent()

    @Test
    fun rssItemsAreReadWithTheirDatesAndPlainText() {
        val items = BasedFeeds.rss(rss)
        assertEquals(listOf("https://news.example.com/a", "https://news.example.com/b", "https://news.example.com/c"), items.map { it.url })
        assertEquals(LocalDate(2026, 10, 29), items[0].published)
        assertEquals("Three councils voted & agreed.", items[0].summary)
    }

    @Test
    fun onlyPostsWithTextOfTheirOwnAreRead() {
        val posts = BasedFeeds.truthSocial(truth)
        assertEquals(listOf("https://social.example.com/@leader/1"), posts.map { it.url })
        assertTrue(posts.single().title.startsWith("Free speech is under attack"))
        assertEquals(emptyList(), BasedFeeds.truthSocial("not json"))
    }

    @Test
    fun anItemNamingAKeywordIsAKeywordStoryTheOthersTrending() {
        val item = BasedFeeds.rss(rss).first()
        val story = BasedFeeds.story(item, "Example News", listOf("electric cars", "chess"))
        assertEquals(listOf("electric cars"), story.keywords)
        assertEquals(false, story.trending)
        assertTrue(BasedFeeds.story(item, "Example News", listOf("cars and vans")).trending)
    }

    @Test
    fun recentItemsOfEveryFeedAndAFeedThatDoesNotAnswer() = runBlocking {
        val texts = mapOf(BasedFeeds.FEEDS[0].url to rss, BasedFeeds.FEEDS[2].url to truth)
        val (stories, issues) = BasedFeeds.stories({ texts[it] }, listOf("free speech"), today)
        // The old item (25 days) is left out; Fox News did not answer.
        assertEquals(3, stories.size)
        assertEquals(listOf("GB News", "GB News", "Truth Social (Donald Trump)"), stories.map { it.sources.single().publisher })
        assertEquals(listOf("free speech"), stories.last().keywords)
        assertEquals(listOf(Issue.Kind.FEED_UNREADABLE), issues.map { it.kind })
    }
}
