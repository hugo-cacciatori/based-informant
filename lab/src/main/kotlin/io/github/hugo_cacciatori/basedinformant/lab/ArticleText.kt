package io.github.hugo_cacciatori.basedinformant.lab

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The readable text of an article page, for the free writer (02-ai-call.md section 12: the writer gets the article
 * text). Paragraphs and headings only, scripts, menus and footers removed. Lab only until redesign step 4.
 */
object ArticleText {
    private const val MAX_PAGE_BYTES = 300_000
    private const val MIN_PARAGRAPH_CHARS = 40

    private val DROPPED = Regex(
        "<(script|style|noscript|svg|nav|header|footer|aside|form|figure|button)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val ARTICLE = Regex("<article\\b[^>]*>(.*)</article\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BLOCK = Regex("<(p|h1|h2|h3|li)\\b[^>]*>(.*?)</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]+>")
    private val SPACE = Regex("\\s+")
    private val NUMERIC = Regex("&#(x?)([0-9a-fA-F]+);")
    private val NAMED = mapOf(
        "&amp;" to "&", "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ",
        "&rsquo;" to "’", "&lsquo;" to "‘", "&rdquo;" to "”", "&ldquo;" to "“", "&mdash;" to "—", "&ndash;" to "–",
    )

    /** The page's article text, at most [maxChars], cut at a sentence end when one is near; null when nothing readable. */
    fun extract(html: String, maxChars: Int = Writer.MAX_PAGE_CHARS): String? {
        val cleaned = DROPPED.replace(COMMENT.replace(html, " "), " ")
        val scope = ARTICLE.find(cleaned)?.groupValues?.get(1) ?: cleaned
        val text = BLOCK.findAll(scope).map { clean(it.groupValues[2]) }.filter { it.length >= MIN_PARAGRAPH_CHARS }.joinToString("\n")
        if (text.isBlank()) return null
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val end = cut.lastIndexOfAny(listOf(". ", ".\n", "? ", "! "))
        return if (end > maxChars / 2) cut.substring(0, end + 1) else cut
    }

    private fun clean(fragment: String): String {
        var text = TAG.replace(fragment, " ")
        NAMED.forEach { (entity, char) -> text = text.replace(entity, char) }
        text = NUMERIC.replace(text) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            code?.let { String(Character.toChars(it)) } ?: m.value
        }
        return SPACE.replace(text, " ").trim()
    }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(8)).build()
    }

    /** Downloads [url] (free: the public page, no tokens) and extracts its text; null on any failure. */
    fun fetch(url: String): String? = try {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) BasedInformantLab/0.0.1")
            .header("Accept", "text/html").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            null
        } else {
            val bytes = response.body().use { it.readNBytes(MAX_PAGE_BYTES) }
            extract(String(bytes, Charsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    }
}
