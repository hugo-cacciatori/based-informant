package io.github.hugo_cacciatori.basedinformant.core.render

import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.Topic

data class RenderedDigest(
    val subject: String,
    val html: String,
    val text: String,
    /** What the size cap left out, or null when the whole digest fits. */
    val shortened: Shortened? = null,
)

/**
 * The email renderer (rendering.md section 1): draws the [DigestView] as HTML with inline styles only, no scripts,
 * no remote content, every model string escaped, links only for `https`; and as plain text.
 */
object DigestRenderer {
    /** The hard cap on the HTML part: Gmail clips messages around 102 KB. */
    const val MAX_HTML_BYTES = 100 * 1024

    /**
     * Over [maxHtmlBytes], leaves out the `detail` texts first, then whole topics, lowest score first (the later one
     * on a tie), and says so in the footer. The digest itself is not changed: History keeps it whole.
     */
    fun render(digest: Digest, meta: RenderMeta, maxHtmlBytes: Int = MAX_HTML_BYTES): RenderedDigest {
        var topics = digest.topics
        var details = 0
        var dropped = 0
        while (true) {
            val shortened = Shortened(details, dropped).takeIf { details + dropped > 0 }
            val view = DigestViews.build(digest.copy(topics = topics), meta, shortened)
            val html = html(view)
            if (html.encodeToByteArray().size <= maxHtmlBytes || topics.isEmpty()) {
                return RenderedDigest(view.subject, html, text(view), shortened)
            }
            val withDetail = lowest(topics) { it.detail != null }
            if (withDetail != null) {
                topics = topics.mapIndexed { i, t -> if (i == withDetail) t.copy(detail = null) else t }
                details++
            } else {
                val index = lowest(topics) { true }!!
                topics = topics.filterIndexed { i, _ -> i != index }
                dropped++
            }
        }
    }

    /** The index of the lowest-scored topic matching [filter]; on a tie, the later one. */
    private fun lowest(topics: List<Topic>, filter: (Topic) -> Boolean): Int? =
        topics.indices.filter { filter(topics[it]) }.minWithOrNull(compareBy<Int> { topics[it].score }.thenByDescending { it })

    private fun html(view: DigestView): String = buildString {
        append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>").append(esc(view.subject)).append("</title></head>\n")
        append("<body style=\"margin:0;padding:0;background-color:#f2f2ef;color:#1d1d1b;\">\n")
        append("<div style=\"max-width:640px;margin:0 auto;padding:16px;background-color:#f2f2ef;font-family:Arial,Helvetica,sans-serif;font-size:16px;line-height:1.5;\">\n")

        append(paragraph(view.headerLine, "margin:0 0 4px;font-size:13px;color:#5a5a55;"))
        append("<h1 style=\"margin:0 0 12px;font-size:22px;color:#1d1d1b;\">").append(esc(view.title)).append("</h1>\n")
        view.opening?.let { append(paragraph(it, "margin:0 0 16px;")) }

        view.emptyMessage?.let { append(paragraph(it, "margin:0 0 16px;")) }
        view.cards.forEach { append(card(it)) }

        append("<div style=\"margin-top:24px;padding-top:12px;border-top:1px solid #d0d0ca;font-size:13px;color:#5a5a55;\">\n")
        view.closing?.let { append(paragraph(it, "margin:0 0 8px;font-size:16px;color:#1d1d1b;")) }
        view.footerLines.forEachIndexed { i, line ->
            append(paragraph(line, if (i == view.footerLines.lastIndex) "margin:0;" else "margin:0 0 4px;"))
        }
        append("</div>\n</div>\n</body></html>\n")
    }

    private fun card(card: CardView): String = buildString {
        append("<div style=\"margin:0 0 16px;padding:16px;background-color:#ffffff;border:1px solid #d0d0ca;border-radius:8px;\">\n")
        append("<h2 style=\"margin:0 0 8px;font-size:18px;color:#1d1d1b;\">").append(esc(card.title)).append("</h2>\n")
        card.newDevelopment?.let { append(paragraph(it, "margin:0 0 8px;font-style:italic;")) }
        append(paragraph(card.summary, "margin:0 0 8px;"))
        append(paragraph(card.matched, "margin:0 0 8px;font-size:13px;color:#5a5a55;"))
        card.detail?.let {
            append("<details style=\"margin:0 0 8px;\"><summary style=\"cursor:pointer;color:#1d1d1b;\">")
            append(DigestViews.MORE).append("</summary>\n")
            append(paragraph(it, "margin:8px 0 0;"))
            append("</details>\n")
        }
        append("<p style=\"margin:0;font-size:13px;\">")
        card.singleSourceLabel?.let { append("<span style=\"color:#5a5a55;\">").append(esc(it)).append(": </span>") }
        card.sources.forEachIndexed { i, s ->
            if (i > 0) append(" · ")
            append(sourceChip(s))
        }
        append("</p>\n</div>\n")
    }

    private fun sourceChip(s: SourceView): String =
        s.url?.let { "<a href=\"${esc(it)}\" style=\"color:#1a4f9c;\">${esc(s.label)}</a>" } ?: esc(s.label)

    private fun paragraph(text: String, style: String) = "<p style=\"$style\">${esc(text)}</p>\n"

    /** HTML-escapes untrusted text for element content and quoted attributes. */
    internal fun esc(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private fun text(view: DigestView): String = buildString {
        append(view.title).append(" · ").append(view.headerLine).append("\n\n")
        view.opening?.let { append(it).append("\n\n") }
        view.emptyMessage?.let { append(it).append("\n\n") }
        view.cards.forEachIndexed { i, c ->
            append(i + 1).append(". ").append(c.title).append('\n')
            c.newDevelopment?.let { append(it).append('\n') }
            append(c.summary).append('\n')
            append(c.matched).append('\n')
            c.detail?.let { append(DigestViews.MORE).append(": ").append(it).append('\n') }
            c.singleSourceLabel?.let { append(it).append(":\n") }
            c.sources.forEach { s ->
                append("- ").append(s.label)
                s.url?.let { append(": ").append(it) }
                append('\n')
            }
            append('\n')
        }
        view.closing?.let { append(it).append("\n\n") }
        view.footerLines.forEach { append(it).append('\n') }
    }
}
