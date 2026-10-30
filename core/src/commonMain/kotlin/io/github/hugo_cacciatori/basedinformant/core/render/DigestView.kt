package io.github.hugo_cacciatori.basedinformant.core.render

import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.FactStatus
import io.github.hugo_cacciatori.basedinformant.core.digest.isHttpsUrl
import io.github.hugo_cacciatori.basedinformant.core.digest.Source
import io.github.hugo_cacciatori.basedinformant.core.digest.Topic
import kotlinx.datetime.LocalDate

/** What the digest itself does not carry. */
data class RenderMeta(
    /** The persona's display name (never the real person's), or null when Persona is Off. */
    val personaDisplayName: String?,
    val model: String,
)

/**
 * The digest as the reader sees it (03-rendering.md section 3): every text, label and its order, built once from a
 * digest. The email renderer draws it as HTML and text; the History viewer (stage 6) draws it natively, so the
 * two cannot drift apart. Texts are plain and unescaped: each renderer escapes for its own format.
 * Texts are English until the UI language is decided.
 */
data class DigestView(
    val subject: String,
    /** "18 Oct 2026 · 3 topics", plus the persona's display name when there is one. */
    val headerLine: String,
    val title: String,
    val opening: String?,
    /** The three things that matter most today, under [DigestViews.BRIEFING] (01-product.md section 4.6); may be empty. */
    val briefing: List<String>,
    /** Shown instead of the cards when no topic survived. */
    val emptyMessage: String?,
    val cards: List<CardView>,
    val closing: String?,
    /** Topic mode: "No article found this time for: ...", or null (owner, 2026-10-29). */
    val missingLine: String?,
    /** The model and template line, then the AI note. */
    val footerLines: List<String>,
)

data class CardView(
    val title: String,
    val summary: String,
    /** "Matched: x, y", or "Trending": why the card was picked; the score is never shown. */
    val matched: String,
    /** The longer text behind **More**. */
    val detail: String?,
    /** What is new since the reader's earlier card on this topic, shown under [DigestViews.SINCE_LAST]. */
    val sinceLast: String?,
    /** How the outlets frame the event, shown under [DigestViews.FRAMING]. */
    val framing: String?,
    /** The trust layer (01-product.md section 4.6): "Reported by 2 outlets", replacing the "1 source" label of 2026-10-18. */
    val outlets: String,
    /** Whether the card was fact-checked against its pages; null for a digest stored before the trust layer. */
    val checkLine: String?,
    val sources: List<SourceView>,
)

/** What the size cap left out of the email (03-rendering.md section 1). */
data class Shortened(val details: Int, val topics: Int)

/** A source chip: "Publisher, 17 Oct 2026, en", with its link only when it is `https`. */
data class SourceView(val label: String, val url: String?)

object DigestViews {
    const val TITLE = "News digest"
    const val MORE = "More"
    const val EMPTY = "Nothing met your criteria this time."
    const val AI_NOTE = "Summaries are written by AI and can be wrong: check the sources."

    const val MISSING = "No article found this time for: "

    const val BRIEFING = "Today's briefing"
    const val SINCE_LAST = "Since last time"
    const val FRAMING = "How outlets frame it"
    const val CHECKED = "Fact-checked against the article pages"
    const val NO_PAGE = "Not fact-checked: no article page could be read"
    const val NOT_CHECKED = "Not fact-checked this time"

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun build(digest: Digest, meta: RenderMeta, shortened: Shortened? = null): DigestView {
        val date = formatDate(digest.generatedAt)
        val count = topicCount(digest.topics.size)
        return DigestView(
            // The writer's subject in the voice (02-ai-call.md section 12, item 14), else the plain one.
            subject = digest.subject ?: "$TITLE · $date · $count",
            headerLine = listOfNotNull(date, count, meta.personaDisplayName).joinToString(" · "),
            title = TITLE,
            opening = digest.opening,
            briefing = digest.briefing,
            emptyMessage = EMPTY.takeIf { digest.topics.isEmpty() },
            cards = digest.topics.map(::card),
            closing = digest.closing,
            missingLine = digest.missingTopics.takeIf { it.isNotEmpty() }?.let { "$MISSING${it.joinToString(", ")}." },
            footerLines = listOfNotNull(
                shortened?.let(::shortenedNote),
                "Model: ${meta.model} · template v${digest.templateVersion}",
                AI_NOTE,
            ),
        )
    }

    /** "18 Oct 2026". */
    fun formatDate(date: LocalDate): String = "${date.day} ${MONTHS[date.month.ordinal]} ${date.year}"

    private fun topicCount(n: Int) = if (n == 1) "1 topic" else "$n topics"

    private fun shortenedNote(s: Shortened): String {
        val parts = listOfNotNull(
            s.details.takeIf { it > 0 }?.let { if (it == 1) "1 longer text" else "$it longer texts" },
            s.topics.takeIf { it > 0 }?.let { if (it == 1) "1 topic" else "$it topics" },
        )
        return "Shortened to fit in an email: ${parts.joinToString(" and ")} left out. The full digest is in the app's History."
    }

    private fun card(topic: Topic) = CardView(
        title = topic.title,
        summary = topic.summary,
        // A trending card has no keyword: it fills a free slot (owner, 2026-10-29).
        matched = if (topic.trending) "Trending" else "Matched: ${topic.keywords.joinToString(", ")}",
        detail = topic.detail,
        sinceLast = topic.sinceLast,
        framing = topic.framing,
        // A topic backed by one article only is kept, and said so (owner, 2026-10-18).
        outlets = topic.sources.map { it.publisher.trim().lowercase() }.distinct().size.let { if (it == 1) "Reported by 1 outlet" else "Reported by $it outlets" },
        checkLine = when (topic.check) {
            FactStatus.CHECKED -> CHECKED
            FactStatus.NO_PAGE -> NO_PAGE
            FactStatus.NOT_CHECKED -> NOT_CHECKED
            FactStatus.UNKNOWN -> null
        },
        sources = topic.sources.map(::source),
    )

    // The search check already allows only https; checked again here because the views are the last line.
    private fun source(s: Source) = SourceView(
        label = listOf(outlet(s), formatDate(s.published), s.language).filter { it.isNotBlank() }.joinToString(", "),
        url = s.url.takeIf(::isHttpsUrl),
    )

    /** The publisher, with its site when no word of the name is in it: "Fox News (wfmd.com)" shows a mislabel (trust layer). */
    private fun outlet(s: Source): String {
        val site = s.url.substringAfter("://", "").takeWhile { it != '/' && it != '?' }.lowercase().removePrefix("www.")
        val named = s.publisher.lowercase().split(Regex("[^a-z0-9]+")).any { it.length >= 3 && it in site }
        return if (named || site.isEmpty()) s.publisher else "${s.publisher} ($site)"
    }
}
