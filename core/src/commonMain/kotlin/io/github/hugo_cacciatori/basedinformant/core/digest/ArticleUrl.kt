package io.github.hugo_cacciatori.basedinformant.core.digest

/**
 * The source-type filter (02-ai-call.md section 8, owner 2026-10-18 and 2026-10-29): a URL shaped like a homepage, an
 * index, an archive, an event, a calendar or a reference page is not one news article. Free and offline: it looks at the URL only. Models kept citing such
 * pages despite the prompt (an ANS topic index, a NASA month archive, a university event page).
 */
object ArticleUrl {
    private val LISTING_SEGMENTS = setOf(
        "event", "events", "calendar", "calendars", "tag", "tags", "category", "categories",
        "topic", "topics", "section", "sections", "archive", "archives", "search", "top",
    )

    /** A section word as the last path segment: /en/video, /news/; any word ending in "updates" counts too. Not applied with a query, as in /news?id=12. */
    private val SECTION_ENDINGS = setOf("video", "videos", "news", "latest", "trending")

    /** A numbered list page: page2, page2.html, or page followed by a number segment. */
    private val PAGE_N = Regex("page\\d+(\\.html?)?")
    private val NUMBER = Regex("\\d+")
    private val LISTING_PREFIXES = listOf("tag-", "topic-", "category-")
    private val LISTING_HOSTS = listOf("events.", "event.", "calendar.")
    private val YEAR = Regex("(19|20)\\d\\d")

    /** Reference pages (owner, 2026-10-29): an encyclopedia, a FAQ or help page, an about, what-is or how-to page. */
    private val REFERENCE_HOSTS = listOf("wikipedia.org", "britannica.com")
    private val REFERENCE_SEGMENTS = setOf("faq", "faqs", "frequently-asked-questions", "help", "glossary")
    private val REFERENCE_PREFIXES = listOf("about-", "what-is-", "how-to-")
    private val MONTH_OR_DAY = Regex("\\d{1,2}")

    /** False for a URL that looks like a listing page; true otherwise, including when the URL cannot be read. */
    fun looksLikeArticle(url: String): Boolean {
        val host = host(url)
        if (LISTING_HOSTS.any(host::startsWith)) return false
        if (REFERENCE_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        val segments = path(url).split('/').filter { it.isNotEmpty() }.map { it.lowercase() }
        if (segments.isEmpty()) return false
        if (segments.any { it in REFERENCE_SEGMENTS }) return false
        val last = segments.last().substringBeforeLast('.')
        if (last == "about" || REFERENCE_PREFIXES.any(last::startsWith)) return false
        if (segments.any { it in LISTING_SEGMENTS || LISTING_PREFIXES.any(it::startsWith) }) return false
        if (segments.any { PAGE_N.matches(it) }) return false
        if (segments.zipWithNext().any { (a, b) -> a == "page" && NUMBER.matches(b) }) return false
        if ((segments.last() in SECTION_ENDINGS || segments.last().endsWith("updates")) && '?' !in url) return false
        return !endsWithDate(segments)
    }

    /** `/2026/`, `/2026/10/` or `/2026/10/15/` at the end of the path: a date archive. */
    private fun endsWithDate(segments: List<String>): Boolean {
        val yearIndex = segments.indexOfLast { YEAR.matches(it) }
        if (yearIndex < 0 || segments.size - yearIndex > 3) return false
        return segments.drop(yearIndex + 1).all { MONTH_OR_DAY.matches(it) }
    }

    private fun host(url: String): String =
        url.substringAfter("://", "").takeWhile { it != '/' && it != '?' && it != '#' }.lowercase()

    private fun path(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        val start = afterScheme.indexOf('/')
        if (start < 0) return ""
        return afterScheme.substring(start).substringBefore('?').substringBefore('#')
    }
}
