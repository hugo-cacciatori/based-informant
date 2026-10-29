package io.github.hugo_cacciatori.basedinformant.core.search

import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** Reads a feed; null when it does not answer. */
fun interface FeedFetcher {
    suspend fun get(url: String): String?
}

/** An HTTP client for the feeds: redirects followed, a longer timeout than the link check, at most [maxBytes] read. */
fun feedHttpClient(engine: HttpClientEngine, timeoutMillis: Long = 10_000): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = timeoutMillis
        connectTimeoutMillis = timeoutMillis
        socketTimeoutMillis = timeoutMillis
    }
}

class HttpFeedFetcher(private val http: HttpClient, private val maxBytes: Long = 1_000_000) : FeedFetcher {
    override suspend fun get(url: String): String? = try {
        http.prepareGet(url) { header(HttpHeaders.UserAgent, USER_AGENT) }.execute { response ->
            if (response.status.value != 200) null else response.bodyAsChannel().readRemaining(maxBytes).readByteArray().decodeToString()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}

/**
 * Based feeds (02-ai-call.md section 4, owner 2026-10-30): the paid search does not reach GB News or Truth Social, so
 * with Based on the phone reads these feeds for free. Recent items become stories: a keyword story when the title or
 * summary contains one of the keywords, a trending story otherwise. They then go through the search's own checks.
 */
object BasedFeeds {
    enum class Format { RSS, TRUTH_SOCIAL }

    data class Feed(val publisher: String, val url: String, val format: Format)

    /** Checked 2026-10-30: each answers without a sign-in. X is not read (owner, 2026-10-30). */
    val FEEDS = listOf(
        Feed("GB News", "https://www.gbnews.com/feeds/news.rss", Format.RSS),
        Feed("Fox News", "https://moxie.foxnews.com/google-publisher/politics.xml", Format.RSS),
        Feed(
            "Truth Social (Donald Trump)",
            "https://truthsocial.com/api/v1/accounts/107780257626128497/statuses?exclude_replies=true&limit=20",
            Format.TRUTH_SOCIAL,
        ),
    )

    /** The newest items taken from each feed, and how old they may be. */
    const val PER_FEED = 5
    val MAX_AGE = DatePeriod(days = 7)

    data class Item(val title: String, val url: String, val published: LocalDate, val summary: String)

    /** Reads every feed; a feed that does not answer or cannot be read is skipped and reported. */
    suspend fun stories(fetcher: FeedFetcher, keywords: List<String>, today: LocalDate): Pair<List<SearchAnswer.Story>, List<Issue>> {
        val issues = mutableListOf<Issue>()
        val stories = FEEDS.flatMap { feed ->
            val text = fetcher.get(feed.url)
            val items = text?.let { if (feed.format == Format.RSS) rss(it) else truthSocial(it) }.orEmpty()
            if (items.isEmpty()) issues += Issue(Issue.Kind.FEED_UNREADABLE)
            items.filter { it.published >= today.minus(MAX_AGE) && it.published <= today.plus1() }
                .sortedByDescending { it.published }
                .take(PER_FEED)
                .map { story(it, feed.publisher, keywords) }
        }
        return stories to issues
    }

    /** A keyword story for every keyword the item mentions (ignoring case), a trending story when it mentions none. */
    fun story(item: Item, publisher: String, keywords: List<String>): SearchAnswer.Story {
        val text = (item.title + " " + item.summary).lowercase()
        val matched = keywords.filter { k -> k.isNotBlank() && Regex("\\b" + Regex.escape(k.trim().lowercase()) + "\\b").containsMatchIn(text) }
        return SearchAnswer.Story(
            keywords = matched,
            trending = matched.isEmpty(),
            whatHappened = item.title,
            sources = listOf(
                SearchAnswer.SearchSource(
                    title = item.title, publisher = publisher, url = item.url, published = item.published.toString(),
                    facts = listOf(item.summary).filter { it.isNotBlank() },
                ),
            ),
        )
    }

    /** RSS 2.0: each item's title, link, publication date (RFC 822) and description, tags and CDATA removed. */
    fun rss(xml: String): List<Item> = ITEM.findAll(xml).mapNotNull { m ->
        val body = m.groupValues[1]
        val title = field(body, "title") ?: return@mapNotNull null
        val link = field(body, "link")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
        val date = field(body, "pubDate")?.let(::rfc822) ?: return@mapNotNull null
        Item(title, link, date, field(body, "description").orEmpty())
    }.toList()

    /** Truth Social's public posts: original posts with text of their own (no reposts), the text cut to a title. */
    fun truthSocial(json: String): List<Item> = try {
        lenient.decodeFromString<List<TruthPost>>(json).mapNotNull { p ->
            if (p.reblog != null) return@mapNotNull null
            val text = plain(p.content)
            // A quoted repost ("RT: ...") or a post that is little more than a link or an emoji says nothing of its own.
            if (text.startsWith("RT:") || text.replace(URL, "").count { it.isLetter() } < MIN_POST_LETTERS) return@mapNotNull null
            val url = p.url?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(p.createdAt.take(10)) }.getOrNull() ?: return@mapNotNull null
            Item(titleOf(text), url, date, text.take(MAX_SUMMARY_CHARS))
        }
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    private fun field(body: String, tag: String): String? =
        Regex("<$tag(?:\\s[^>]*)?>([\\s\\S]*?)</$tag>").find(body)?.groupValues?.get(1)?.let(::plain)?.takeIf { it.isNotEmpty() }

    /** Text without CDATA, tags or common entities, spaces collapsed. */
    private fun plain(text: String): String = text
        .replace(Regex("<!\\[CDATA\\[|]]>"), "")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()
        .take(MAX_SUMMARY_CHARS)

    /** The first sentence, or the first words, as a title. */
    private fun titleOf(text: String): String {
        val sentence = text.split(Regex("(?<=[.!?])\\s")).first()
        return if (sentence.length <= MAX_TITLE_CHARS) sentence else sentence.take(MAX_TITLE_CHARS).substringBeforeLast(' ') + "…"
    }

    /** "Thu, 29 Oct 2026 23:10:13 +0000" (the day and month name, the year). */
    private fun rfc822(text: String): LocalDate? {
        val m = Regex("(\\d{1,2})\\s+([A-Za-z]{3})[a-z]*\\s+(\\d{4})").find(text) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
        if (month == 0) return null
        return runCatching { LocalDate(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }.getOrNull()
    }

    private fun LocalDate.plus1() = LocalDate.fromEpochDays(toEpochDays() + 1)

    private val ITEM = Regex("<item(?:\\s[^>]*)?>([\\s\\S]*?)</item>")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MAX_TITLE_CHARS = 120
    private const val MAX_SUMMARY_CHARS = 1_500
    private const val MIN_POST_LETTERS = 30
    private val URL = Regex("https?://\\S+")

    @Serializable
    private data class TruthPost(
        @kotlinx.serialization.SerialName("created_at") val createdAt: String,
        val url: String? = null,
        val content: String = "",
        val reblog: kotlinx.serialization.json.JsonElement? = null,
    )

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
}
