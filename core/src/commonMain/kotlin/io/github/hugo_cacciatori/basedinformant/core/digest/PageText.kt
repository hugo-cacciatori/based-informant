package io.github.hugo_cacciatori.basedinformant.core.digest

/**
 * The readable text of an article page, for the free writer and the fact check (02-ai-call.md section 12). Scripts,
 * menus, headers, footers and forms go; the article element is used when the page has one. Every block, table cell and
 * line break starts a new line, so status lines between `<br>` tags are kept (owner, 2026-10-29: paragraphs only lost
 * a volcano notice's alert levels). Lines shorter than [MIN_LINE_CHARS] (buttons, bullets) and repeats are dropped; a
 * short heading such as a volcano's name stays, so each status line keeps its subject.
 */
object PageText {
    /** Read per page: GB News puts an article's text past 340 KB (2026-10-30), so 300 KB read only its logo. */
    const val MAX_PAGE_BYTES = 1_500_000

    /** Sent per source: enough for the facts, small enough for every free model (lab, 2026-10-23). */
    const val MAX_CHARS = 4_000
    const val MIN_LINE_CHARS = 8

    /** Less readable text than this is no page text: a cut or script-built page gives scraps the fact check must not use. */
    const val MIN_TEXT_CHARS = 200

    private val DROPPED = Regex(
        "<(script|style|noscript|svg|nav|header|footer|aside|form|figure|button|template|iframe)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val ARTICLE = Regex("<article\\b[^>]*>(.*)</article\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BODY = Regex("<body\\b[^>]*>(.*)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BREAK = Regex(
        "<(?:br|hr)\\b[^>]*>|</?(?:p|div|section|h[1-6]|li|ul|ol|tr|td|th|dt|dd|blockquote|pre|table|figcaption)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val TAG = Regex("<[^>]+>")

    /** A tag cut off at the end of a page read only in part. */
    private val CUT_TAG = Regex("<[^>]*$")
    private val SPACE = Regex("[ \\t\\u00A0]+")
    private val NUMERIC = Regex("&#(x?)([0-9a-fA-F]+);")
    private val NAMED = mapOf(
        "&quot;" to "\"", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ",
        "&rsquo;" to "’", "&lsquo;" to "‘", "&rdquo;" to "”", "&ldquo;" to "“", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
        // Last, so "&amp;lt;" stays "&lt;".
        "&amp;" to "&",
    )

    /** The page's text, at most [maxChars], cut at a sentence or line end when one is near; null when nothing readable. */
    fun extract(html: String, maxChars: Int = MAX_CHARS): String? {
        val cleaned = DROPPED.replace(COMMENT.replace(html, " "), " ")
        val scope = ARTICLE.find(cleaned)?.groupValues?.get(1) ?: BODY.find(cleaned)?.groupValues?.get(1) ?: cleaned
        val lines = TAG.replace(CUT_TAG.replace(BREAK.replace(scope, "\n"), " "), " ").lines()
            .map { SPACE.replace(decode(it), " ").trim() }
            .filter { it.length >= MIN_LINE_CHARS }
            .distinct()
        val text = lines.joinToString("\n")
        if (text.length < MIN_TEXT_CHARS) return null
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val end = cut.lastIndexOfAny(listOf(". ", ".\n", "? ", "! ", "\n"))
        return if (end > maxChars / 2) cut.substring(0, end + 1).trim() else cut
    }

    private fun decode(fragment: String): String {
        var text = fragment
        NAMED.forEach { (entity, char) -> text = text.replace(entity, char) }
        return NUMERIC.replace(text) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            code?.let(::codePointString) ?: m.value
        }
    }

    private fun codePointString(code: Int): String = when {
        code < 0 || code > 0x10FFFF -> ""
        code < 0x10000 -> Char(code).toString()
        else -> {
            val c = code - 0x10000
            charArrayOf(Char(0xD800 + (c shr 10)), Char(0xDC00 + (c and 0x3FF))).concatToString()
        }
    }
}
