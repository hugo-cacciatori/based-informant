package io.github.hugo_cacciatori.basedinformant.core.search

import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wikipedia's Current events portal (02-ai-call.md section 4, owner 2026-10-30): with Based off no outlet is preferred,
 * so the phone reads today's and yesterday's day pages for free, through Wikipedia's public API. Each listed event
 * becomes a story citing the article links the page gives it: a keyword story when it mentions a keyword, a trending
 * story otherwise. They then go through the search's own checks, as Based's feeds do.
 */
object WikipediaEvents {
    /** The most events taken, newest page first. */
    const val MAX_EVENTS = 10

    /** The API address of one day's page, such as Portal:Current_events/2026_October_30. */
    fun url(day: LocalDate): String {
        val month = day.month.name.lowercase().replaceFirstChar { it.uppercaseChar() }
        return "https://en.wikipedia.org/w/api.php?action=parse&format=json&formatversion=2&prop=text" +
            "&page=Portal:Current_events/${day.year}_${month}_${day.day}"
    }

    /** Reads today's page, then yesterday's; a page that does not answer or holds no event is skipped and reported. */
    suspend fun stories(fetcher: FeedFetcher, keywords: List<String>, today: LocalDate): Pair<List<SearchAnswer.Story>, List<Issue>> {
        val issues = mutableListOf<Issue>()
        val events = listOf(today, LocalDate.fromEpochDays(today.toEpochDays() - 1)).flatMap { day ->
            val found = fetcher.get(url(day))?.let(::html)?.let { events(it, day) }.orEmpty()
            // Today's page may still be empty early in the day: only a page that cannot be read is an issue.
            if (found.isEmpty() && day != today) issues += Issue(Issue.Kind.FEED_UNREADABLE)
            found
        }
        return events.take(MAX_EVENTS).map { story(it, keywords) } to issues
    }

    data class Event(val text: String, val day: LocalDate, val citations: List<Citation>)

    data class Citation(val publisher: String, val url: String)

    /** The page's events: list items with no list inside, citing at least one https article. */
    fun events(html: String, day: LocalDate): List<Event> = LEAF.findAll(html).mapNotNull { m ->
        val body = m.groupValues[1]
        val citations = CITATION.findAll(body).mapNotNull { c ->
            // Wikipedia's own links (edit, history) are not news sources.
            val url = c.groupValues[1].replace("&amp;", "&").takeIf { it.startsWith("https://") && ".wikipedia.org/" !in it } ?: return@mapNotNull null
            Citation(plain(c.groupValues[2]).trim('(', ')', ' '), url)
        }.toList()
        if (citations.isEmpty()) return@mapNotNull null
        val text = plain(CITATION.replace(body, " "))
        Event(text, day, citations).takeIf { text.length >= MIN_TEXT_CHARS }
    }.toList()

    fun story(event: Event, keywords: List<String>): SearchAnswer.Story {
        val matched = keywordsIn(event.text, keywords)
        return SearchAnswer.Story(
            keywords = matched,
            trending = matched.isEmpty(),
            whatHappened = event.text,
            sources = event.citations.map { c ->
                SearchAnswer.SearchSource(
                    title = event.text.take(MAX_TITLE_CHARS), publisher = c.publisher, url = c.url,
                    published = event.day.toString(), facts = listOf(event.text),
                )
            },
        )
    }

    private fun html(json: String): String? = try {
        lenient.decodeFromString(Answer.serializer(), json).parse?.text
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Text without tags or common entities, spaces collapsed; a link's tags leave no space ("Canada's", "Toronto-based"). */
    private fun plain(text: String) = text.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.;:])"), "$1").trim()

    @Serializable
    private data class Answer(val parse: Parsed? = null)

    @Serializable
    private data class Parsed(val text: String = "")

    private val LEAF = Regex("<li>((?:(?!<li>|<ul)[\\s\\S])*?)</li>")
    private val CITATION = Regex("<a[^>]*class=\"external text\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>")
    private const val MIN_TEXT_CHARS = 30
    private const val MAX_TITLE_CHARS = 200
    private val lenient = Json { ignoreUnknownKeys = true }
}
