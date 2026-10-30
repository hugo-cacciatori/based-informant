package io.github.hugo_cacciatori.basedinformant.core.search

import io.github.hugo_cacciatori.basedinformant.core.digest.ArticleUrl
import io.github.hugo_cacciatori.basedinformant.core.digest.Issue

/**
 * The homepage rescue (02-ai-call.md section 8, owner 2026-10-30): the search cites a site's homepage for headline
 * stories, so the phone reads that homepage for free and takes the link on the same site whose text (or address)
 * holds at least half of the source title's words and looks like an article. Runs before the source checks; without a match the
 * source stays as it was, and the source-type filter drops it as before.
 */
object HomepageRescue {
    /** The share of the title's words a link must hold: front-page headlines are often shorter than the title. */
    const val MIN_SHARE = 0.5

    suspend fun apply(answer: SearchAnswer, fetcher: FeedFetcher): Pair<SearchAnswer, List<Issue>> {
        val homepages = answer.stories.flatMap { it.sources }.map { it.url }.filter(::isHomepage).distinct()
        if (homepages.isEmpty()) return answer to emptyList()
        val links = homepages.associateWith { url -> fetcher.get(url)?.let { links(it, url) }.orEmpty() }
        val issues = mutableListOf<Issue>()
        val stories = answer.stories.mapIndexed { i, story ->
            story.copy(
                sources = story.sources.map { s ->
                    if (!isHomepage(s.url)) return@map s
                    val found = match(s.title, links[s.url].orEmpty()) ?: return@map s
                    issues += Issue(Issue.Kind.SOURCE_HOMEPAGE_RESCUED, story = story.story ?: (i + 1), source = s.n)
                    s.copy(url = found)
                },
            )
        }
        return answer.copy(stories = stories) to issues
    }

    /** A site's front page: an https URL with no path, query or fragment beyond "/". */
    fun isHomepage(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val rest = url.removePrefix("https://")
        val slash = rest.indexOf('/')
        return slash < 0 || rest.substring(slash).trim('/').isEmpty()
    }

    /** The best link for [title]: the most of its words, at least [MIN_SHARE]; the first on the page wins a tie. */
    fun match(title: String, links: List<Link>): String? {
        val wanted = words(title)
        if (wanted.isEmpty()) return null
        return links.map { it to wanted.count { w -> w in it.words }.toDouble() / wanted.size }
            .filter { it.second >= MIN_SHARE }
            .maxByOrNull { it.second }?.first?.url
    }

    data class Link(val url: String, val words: Set<String>)

    /** The page's links on the same site that look like articles, each with the words of its text and address. */
    fun links(html: String, homepage: String): List<Link> {
        val host = host(homepage)
        return ANCHOR.findAll(html).mapNotNull { m ->
            val url = resolve(m.groupValues[1].trim(), host) ?: return@mapNotNull null
            if (!sameSite(host(url), host) || isHomepage(url) || !ArticleUrl.looksLikeArticle(url)) return@mapNotNull null
            Link(url, words(text(m.groupValues[2]) + " " + url.substringAfter(host).replace(Regex("[-_/.]"), " ")))
        }.distinctBy { it.url }.toList()
    }

    private fun resolve(href: String, host: String): String? = when {
        href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "https://$host$href"
        else -> null
    }?.substringBefore('#')

    private fun host(url: String) = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.lowercase()

    private fun sameSite(a: String, b: String) = a.removePrefix("www.") == b.removePrefix("www.")

    private fun text(html: String) = html.replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&#39;", "'").replace("&#x27;", "'").replace("&quot;", "\"").replace("&rsquo;", "'")

    /** Lowercase words of three letters or more, so small words do not make a match. */
    private fun words(text: String) =
        text.lowercase().replace("’", "'").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    private val ANCHOR = Regex("<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
}
