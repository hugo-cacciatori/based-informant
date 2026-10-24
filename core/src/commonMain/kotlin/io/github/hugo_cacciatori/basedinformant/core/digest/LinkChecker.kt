package io.github.hugo_cacciatori.basedinformant.core.digest

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.request.prepareRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.io.readByteArray
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

/** What a source URL turned out to be (02-ai-call.md section 8). */
enum class LinkStatus {
    ALIVE,

    /** 404, 410, a host that does not exist, a placeholder URL, a redirect to a homepage, parent or section page, or an empty 200 page: the source is dropped. */
    DEAD,

    /** 401, 403, 429, 5xx, a timeout or another error: many sites block checks, so the source is kept. */
    UNKNOWN,
}

/**
 * A check's outcome, with a short reason for the report ("HTTP 404", "unknown host", "redirect to homepage"), and for a
 * live page the publication date found in its metadata, if any.
 */
data class LinkResult(val status: LinkStatus, val reason: String = "", val pageDate: LocalDate? = null)

fun interface LinkChecker {
    suspend fun check(url: String): LinkResult
}

/** An HTTP client for link checks: short timeouts, redirects followed by hand so a redirect to a homepage is seen. */
fun linkCheckHttpClient(engine: HttpClientEngine, timeoutMillis: Long = 4_000): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = timeoutMillis
        connectTimeoutMillis = timeoutMillis
        socketTimeoutMillis = timeoutMillis
    }
}

/**
 * Checks a link with HEAD (GET when HEAD is refused), then reads a live page for its date and to spot an empty one.
 * Costs no tokens. A dead result is checked once more after [retryDelayMillis], because sites fail briefly: a live
 * source was once dropped that way (2026-10-18).
 */
class HttpLinkChecker(
    private val http: HttpClient,
    private val maxRedirects: Int = 5,
    private val retryDelayMillis: Long = 1_500,
    private val readPages: Boolean = true,
) : LinkChecker {
    override suspend fun check(url: String): LinkResult {
        val first = checkOnce(url)
        if (first.status != LinkStatus.DEAD) return first
        delay(retryDelayMillis)
        return checkOnce(url)
    }

    private suspend fun checkOnce(url: String): LinkResult {
        val original = try {
            Url(url)
        } catch (_: Exception) {
            return LinkResult(LinkStatus.DEAD, "malformed URL")
        }
        if (hasPlaceholder(original)) return LinkResult(LinkStatus.DEAD, "placeholder URL")
        var current = original
        repeat(maxRedirects + 1) {
            val (status, location) = request(current, HttpMethod.Head).let { first ->
                if (first.first in HEAD_REFUSED) request(current, HttpMethod.Get) else first
            }
            when (status) {
                in 200..299 -> return if (readPages) livePage(current, status) else LinkResult(LinkStatus.ALIVE, "HTTP $status")
                in REDIRECTS -> {
                    val next = location?.let { resolve(current, it) }
                        ?: return LinkResult(LinkStatus.UNKNOWN, "redirect without a target")
                    if (isHomepage(next) && !isHomepage(original)) return LinkResult(LinkStatus.DEAD, "redirect to homepage")
                    if (isParent(next, original)) return LinkResult(LinkStatus.DEAD, "redirect to a parent page")
                    if (isSection(next) && !isSection(original)) return LinkResult(LinkStatus.DEAD, "redirect to a section page")
                    current = next
                }
                404, 410 -> return LinkResult(LinkStatus.DEAD, "HTTP $status")
                DNS_FAILURE -> return LinkResult(LinkStatus.DEAD, "unknown host")
                OTHER_FAILURE -> return LinkResult(LinkStatus.UNKNOWN, "no answer")
                else -> return LinkResult(LinkStatus.UNKNOWN, "HTTP $status")
            }
        }
        return LinkResult(LinkStatus.UNKNOWN, "too many redirects")
    }

    /** The status and Location header, or [DNS_FAILURE] / [OTHER_FAILURE] when no answer came. */
    private suspend fun request(url: Url, method: HttpMethod): Pair<Int, String?> = try {
        http.prepareRequest {
            this.method = method
            this.url.takeFrom(url)
            header(HttpHeaders.UserAgent, USER_AGENT)
        }.execute { it.status.value to it.headers[HttpHeaders.Location] }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        (if (isDnsFailure(e)) DNS_FAILURE else OTHER_FAILURE) to null
    }

    /**
     * Reads a live page, at most [MAX_PAGE_BYTES] (the head comes first): an empty 200 is dead; an empty answer with
     * another 2xx code is unknown (CloudFront answers a real page with an empty 202 as a bot challenge, 2026-10-18);
     * otherwise alive with the date in its metadata. A failed read stays alive.
     */
    private suspend fun livePage(url: Url, status: Int): LinkResult = try {
        http.prepareGet(url) { header(HttpHeaders.UserAgent, USER_AGENT) }.execute { response ->
            val code = response.status.value
            if (code !in 200..299) return@execute LinkResult(LinkStatus.ALIVE, "HTTP $status")
            val text = response.bodyAsChannel().readRemaining(MAX_PAGE_BYTES).readByteArray().decodeToString()
            when {
                text.isNotBlank() -> LinkResult(LinkStatus.ALIVE, "HTTP $status", PageDate.extract(text))
                code == 200 -> LinkResult(LinkStatus.DEAD, "empty page")
                else -> LinkResult(LinkStatus.UNKNOWN, "empty answer (HTTP $code)")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        LinkResult(LinkStatus.ALIVE, "HTTP $status")
    }

    private fun resolve(base: Url, location: String): Url? = try {
        URLBuilder(base).takeFrom(location).build()
    } catch (_: Exception) {
        null
    }

    private fun isHomepage(url: Url): Boolean = url.encodedPath.trim('/').isEmpty()

    private fun segments(url: Url): List<String> = url.encodedPath.split('/').filter { it.isNotEmpty() }

    private fun site(url: Url): String = url.host.lowercase().removePrefix("www.")

    /** A shorter path on the same site that the requested path starts with: /news/story -> /news/. */
    private fun isParent(next: Url, original: Url): Boolean {
        val shorter = segments(next)
        val longer = segments(original)
        return site(next) == site(original) && shorter.isNotEmpty() && shorter.size < longer.size &&
            longer.subList(0, shorter.size) == shorter
    }

    private fun isSection(url: Url): Boolean = segments(url).any { it.lowercase() in SECTION_SEGMENTS }

    /** A path segment like xxx, axxx or xxxxxxxx: the model filled in a placeholder instead of a real address. */
    private fun hasPlaceholder(url: Url): Boolean = segments(url).any { PLACEHOLDER.matches(it) }

    /** The JVM and Android both report an unknown host as UnknownHostException, somewhere in the cause chain. */
    private fun isDnsFailure(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any { it::class.simpleName == "UnknownHostException" }

    companion object {
        private const val USER_AGENT = "BasedInformant-LinkCheck"
        private const val MAX_PAGE_BYTES = 300_000L
        private const val DNS_FAILURE = -1
        private const val OTHER_FAILURE = -2
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val HEAD_REFUSED = setOf(400, 403, 405, 501)
        private val SECTION_SEGMENTS = setOf("category", "categories", "tag", "tags", "section", "sections", "topic", "topics", "search")
        private val PLACEHOLDER = Regex("(?i)[a-z]?x{3,}")
    }
}

/** Link-check results of one digest, for the report and the pass bar (at least 80% of sources kept). */
data class LinkReport(
    val alive: Int,
    val unknown: Int,
    val dead: Int,
    val deadLinks: List<String> = emptyList(),
    /** Live pages whose metadata gave a date. */
    val datesFound: Int = 0,
    /** Dates the model gave that differed from the page and were corrected. */
    val datesCorrected: Int = 0,
    /** Sources dropped because the page's own date is outside the window. */
    val datesOutsideWindow: Int = 0,
) {
    val total get() = alive + unknown + dead

    /** Share of sources kept (alive or unknown), 0.0-1.0; 1.0 when there were none. */
    val keptShare get() = if (total == 0) 1.0 else (alive + unknown).toDouble() / total
}

/**
 * Drops dead sources and sources whose page date is outside [window], corrects dates that differ from the page, then
 * drops topics left with no source (02-ai-call.md section 8). Checks run in parallel.
 */
object LinkStage {
    const val PARALLEL_CHECKS = 8

    /** Same tolerance as the validator: a page dated one day after the window may be in a later time zone. */
    private val FUTURE_TOLERANCE = DatePeriod(days = 1)

    suspend fun apply(digest: Digest, checker: LinkChecker, window: RecencyWindow? = null): Triple<Digest, List<Issue>, LinkReport> {
        val urls = digest.topics.flatMap { t -> t.sources.map { it.url } }.distinct()
        val semaphore = Semaphore(PARALLEL_CHECKS)
        val statuses = coroutineScope {
            urls.map { url -> async { url to semaphore.withPermit { checker.check(url) } } }.awaitAll()
        }.toMap()
        val issues = mutableListOf<Issue>()
        var corrected = 0
        var outside = 0
        val topics = digest.topics.mapIndexedNotNull { topicIndex, topic ->
            val live = topic.sources.mapIndexedNotNull { sourceIndex, s ->
                val result = statuses.getValue(s.url)
                val pageDate = result.pageDate
                when {
                    result.status == LinkStatus.DEAD -> null.also { issues += Issue(Issue.Kind.SOURCE_DEAD_LINK, topicIndex, sourceIndex) }
                    pageDate == null -> s
                    window != null && (pageDate < window.start || pageDate > window.end.plus(FUTURE_TOLERANCE)) ->
                        null.also {
                            outside++
                            issues += Issue(Issue.Kind.SOURCE_PAGE_DATE_OUTSIDE_WINDOW, topicIndex, sourceIndex)
                        }
                    pageDate != s.published -> s.copy(published = pageDate).also {
                        corrected++
                        issues += Issue(Issue.Kind.SOURCE_DATE_CORRECTED, topicIndex, sourceIndex)
                    }
                    else -> s
                }
            }
            if (live.isEmpty()) null.also { issues += Issue(Issue.Kind.TOPIC_NO_LIVE_SOURCE, topicIndex) }
            else topic.copy(sources = live)
        }
        val all = digest.topics.flatMap { t -> t.sources.map { statuses.getValue(it.url).status } }
        val report = LinkReport(
            alive = all.count { it == LinkStatus.ALIVE },
            unknown = all.count { it == LinkStatus.UNKNOWN },
            dead = all.count { it == LinkStatus.DEAD },
            deadLinks = statuses.filterValues { it.status == LinkStatus.DEAD }.map { (url, r) -> "$url (${r.reason})" },
            datesFound = statuses.values.count { it.pageDate != null },
            datesCorrected = corrected,
            datesOutsideWindow = outside,
        )
        return Triple(digest.copy(topics = topics), issues, report)
    }
}
