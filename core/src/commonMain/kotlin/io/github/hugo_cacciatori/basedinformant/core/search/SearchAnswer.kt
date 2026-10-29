package io.github.hugo_cacciatori.basedinformant.core.search

import io.github.hugo_cacciatori.basedinformant.core.digest.ArticleUrl
import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkReport
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkResult
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkStatus
import io.github.hugo_cacciatori.basedinformant.core.digest.ValidationContext
import io.github.hugo_cacciatori.basedinformant.core.digest.isHttpsUrl
import io.github.hugo_cacciatori.basedinformant.core.digest.normalizeUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The paid search answer (template v2, `core/prompt/search.txt`): stories with numbered sources, their facts, and the
 * article text the link check read. Kept as a file with the run, so **Write again** uses the pages as they were.
 */
@Serializable
data class SearchAnswer(
    @SerialName("template_version") val templateVersion: Int = 2,
    @SerialName("searched_at") val searchedAt: String,
    val stories: List<Story>,
) {
    @Serializable
    data class Story(
        val story: Int? = null,
        val keywords: List<String> = emptyList(),
        /** Today's popular news filling a free slot: it needs no keyword (owner, 2026-10-29). */
        val trending: Boolean = false,
        @SerialName("what_happened") val whatHappened: String? = null,
        val attention: Int? = null,
        val sources: List<SearchSource>,
        @SerialName("persona_notes") val personaNotes: List<PersonaNote> = emptyList(),
    )

    @Serializable
    data class SearchSource(
        val n: Int? = null,
        val title: String,
        val publisher: String,
        val url: String,
        val language: String = "",
        val published: String = "",
        val facts: List<String> = emptyList(),
        @SerialName("page_text") val pageText: String? = null,
    )

    @Serializable
    data class PersonaNote(
        val said: String,
        val verbatim: Boolean? = null,
        val where: String? = null,
        val url: String? = null,
        val date: String? = null,
    )

    /** Stories numbered from 1, sources numbered from 1 across the whole answer, in order. */
    fun numbered(): SearchAnswer {
        var n = 0
        return copy(stories = stories.mapIndexed { i, s -> s.copy(story = i + 1, sources = s.sources.map { it.copy(n = ++n) }) })
    }

    fun withoutPersonaNotes() = copy(stories = stories.map { it.copy(personaNotes = emptyList()) })

    fun withPages(pages: Map<String, String>) =
        copy(stories = stories.map { s -> s.copy(sources = s.sources.map { it.copy(pageText = pages[it.url] ?: it.pageText) }) })

    fun urls(): List<String> = stories.flatMap { s -> s.sources.map { it.url } }.distinct()

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** The stored copy (Write again, Read again): written by [encode]. */
        fun decode(text: String): SearchAnswer = json.decodeFromString(serializer(), text)

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

/** Reads the paid answer: fences and text around the JSON object allowed. Null when it is not a readable answer. */
object SearchParser {
    fun parse(content: String): SearchAnswer? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            lenient.decodeFromString(SearchAnswer.serializer(), content.substring(start, end + 1))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
}

/**
 * The free checks on a search answer, before any page is opened (02-ai-call.md section 8): a source is dropped when its
 * URL is not `https`, looks like a listing page, repeats one already kept, or its date is not `YYYY-MM-DD` or is in the
 * future (one day of tolerance; no lower limit since 2026-10-29); a story is dropped when none of its keywords is the
 * owner's (a trending story needs none) or no source is left. The answer comes back numbered.
 */
object SearchCheck {
    /** Sources dated one day after the device's date are kept: the publisher may be in a later time zone. */
    private val FUTURE_TOLERANCE = DatePeriod(days = 1)

    fun apply(answer: SearchAnswer, context: ValidationContext): Pair<SearchAnswer, List<Issue>> {
        val issues = mutableListOf<Issue>()
        val owner = context.keywords.associateBy { it.trim().lowercase() }
        val seen = mutableSetOf<String>()
        val numbered = answer.numbered()
        val stories = numbered.stories.mapNotNull { story ->
            val n = story.story
            val matched = story.keywords.mapNotNull { owner[it.trim().lowercase()] }.distinct()
            if (matched.isEmpty() && !story.trending) return@mapNotNull null.also { issues += Issue(Issue.Kind.STORY_NOT_LINKED_TO_KEYWORDS, story = n) }
            if (NOTHING_HAPPENED.containsMatchIn(story.whatHappened.orEmpty())) return@mapNotNull null.also { issues += Issue(Issue.Kind.STORY_NOT_NEWS, story = n) }
            val sources = story.sources.filter { s ->
                val kind = sourceProblem(s, context.today) ?: if (seen.add(normalizeUrl(s.url))) null else Issue.Kind.SOURCE_DUPLICATE_URL
                if (kind != null) issues += Issue(kind, story = n, source = s.n)
                kind == null
            }
            if (sources.isEmpty()) return@mapNotNull null.also { issues += Issue(Issue.Kind.STORY_NO_SOURCE, story = n) }
            story.copy(keywords = matched, attention = story.attention?.coerceIn(0, MAX_ATTENTION), sources = sources)
        }
        return numbered.copy(stories = stories) to issues
    }

    private fun sourceProblem(s: SearchAnswer.SearchSource, today: LocalDate): Issue.Kind? {
        if (s.title.isBlank() || s.publisher.isBlank() || s.url.isBlank()) return Issue.Kind.SOURCE_MISSING_FIELD
        if (!isHttpsUrl(s.url)) return Issue.Kind.SOURCE_NOT_HTTPS
        if (!ArticleUrl.looksLikeArticle(s.url)) return Issue.Kind.SOURCE_NOT_AN_ARTICLE
        val date = date(s.published) ?: return Issue.Kind.SOURCE_BAD_DATE
        if (date > today.plus(FUTURE_TOLERANCE)) return Issue.Kind.SOURCE_DATE_IN_FUTURE
        return null
    }

    internal fun date(text: String): LocalDate? = try {
        LocalDate.parse(text.trim())
    } catch (_: IllegalArgumentException) {
        null
    }

    const val MAX_ATTENTION = 40

    /** A story saying no news was found (owner, 2026-10-29): sonar returned one for a keyword it found nothing on. */
    private val NOTHING_HAPPENED = Regex("\\b(no (recent |new )?news|nothing new|no (recent |new )?developments?)\\b", RegexOption.IGNORE_CASE)
}

/**
 * The link and page-date checks on a search answer (02-ai-call.md section 8), in parallel and free: a dead source or one
 * whose page date is in the future is dropped, a differing page date replaces the model's, and the page's
 * readable text is kept for the writer and the fact check. A story left with no source is dropped.
 */
object SearchLinks {
    const val PARALLEL_CHECKS = 8
    private val FUTURE_TOLERANCE = DatePeriod(days = 1)

    data class Result(val answer: SearchAnswer, val issues: List<Issue>, val report: LinkReport)

    suspend fun apply(answer: SearchAnswer, checker: LinkChecker, today: LocalDate): Result {
        val urls = answer.urls()
        val semaphore = Semaphore(PARALLEL_CHECKS)
        val results: Map<String, LinkResult> = coroutineScope {
            urls.map { url -> async { url to semaphore.withPermit { checker.check(url) } } }.awaitAll()
        }.toMap()
        val issues = mutableListOf<Issue>()
        var corrected = 0
        var future = 0
        val stories = answer.stories.mapNotNull { story ->
            val live = story.sources.mapNotNull { s ->
                val result = results.getValue(s.url)
                val pageDate = result.pageDate
                fun issue(kind: Issue.Kind) = Issue(kind, story = story.story, source = s.n)
                when {
                    result.status == LinkStatus.DEAD -> null.also { issues += issue(Issue.Kind.SOURCE_DEAD_LINK) }
                    pageDate != null && pageDate > today.plus(FUTURE_TOLERANCE) ->
                        null.also { future++; issues += issue(Issue.Kind.SOURCE_PAGE_DATE_IN_FUTURE) }
                    pageDate != null && pageDate.toString() != s.published.trim() -> {
                        corrected++
                        issues += issue(Issue.Kind.SOURCE_DATE_CORRECTED)
                        s.copy(published = pageDate.toString(), pageText = result.pageText)
                    }
                    else -> s.copy(pageText = result.pageText)
                }
            }
            if (live.isEmpty()) null.also { issues += Issue(Issue.Kind.STORY_NO_LIVE_SOURCE, story = story.story) } else story.copy(sources = live)
        }
        val all = answer.stories.flatMap { s -> s.sources.map { results.getValue(it.url).status } }
        val report = LinkReport(
            alive = all.count { it == LinkStatus.ALIVE },
            unknown = all.count { it == LinkStatus.UNKNOWN },
            dead = all.count { it == LinkStatus.DEAD },
            deadLinks = results.filterValues { it.status == LinkStatus.DEAD }.map { (url, r) -> "$url (${r.reason})" },
            datesFound = results.values.count { it.pageDate != null },
            datesCorrected = corrected,
            datesInFuture = future,
        )
        return Result(answer.copy(stories = stories), issues, report)
    }
}
