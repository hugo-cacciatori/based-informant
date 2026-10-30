package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.datetime.LocalDate

/** What the run knows that the answers are checked against. */
data class ValidationContext(
    /** The owner's keywords from the run's snapshot, or the chosen topic tags in topic mode. */
    val keywords: List<String>,
    /** The device's date: there is no lower date limit (owner, 2026-10-29), only dates in the future are refused. */
    val today: LocalDate,
)

/**
 * Something a free check dropped or noticed, for the call history. Kinds and positions only, never model text.
 * [story] is the story's number in the search answer, [source] the source's number, [topic] the written topic's index,
 * [sentence] the fact check's sentence number.
 */
data class Issue(val kind: Kind, val story: Int? = null, val source: Int? = null, val topic: Int? = null, val sentence: Int? = null) {
    enum class Kind {
        /** The search answer was unreadable and a free model rebuilt it (02-ai-call.md section 12, item 10). */
        ANSWER_REPAIRED,
        STORY_NO_SOURCE,
        STORY_NOT_LINKED_TO_KEYWORDS,

        /** The story says no news was found (owner, 2026-10-29). */
        STORY_NOT_NEWS,
        STORY_NO_LIVE_SOURCE,
        SOURCE_MISSING_FIELD,
        SOURCE_NOT_HTTPS,

        /** The URL looks like a homepage, index, archive, event, calendar or reference page (source-type filter). */
        SOURCE_NOT_AN_ARTICLE,

        /** The source was a homepage or listing page, replaced by the matching article link found on it (owner, 2026-10-30). */
        SOURCE_LISTING_RESCUED,
        SOURCE_BAD_DATE,

        /** Dated after tomorrow: impossible for published news (one day of tolerance for time zones). */
        SOURCE_DATE_IN_FUTURE,
        SOURCE_DUPLICATE_URL,

        /** Based is on and the free outlet check did not call the outlet right-wing (owner, 2026-10-30). */
        SOURCE_NOT_RIGHT_WING,

        /** Based is on but no free model answered the outlet check: the sources were kept unchecked. */
        OUTLETS_NOT_CHECKED,

        /** A free feed (Based's, or Wikipedia's Current events without Based) did not answer or could not be read: skipped. */
        FEED_UNREADABLE,
        SOURCE_DEAD_LINK,
        SOURCE_PAGE_DATE_IN_FUTURE,
        SOURCE_DATE_CORRECTED,

        /** The writer named a story or source the search answer does not have, or another story's source. */
        TOPIC_UNKNOWN_STORY,
        TOPIC_FOREIGN_SOURCE,
        TOPIC_TAG_CUT,

        /** A written sentence shared five words in a row with a persona note and was deleted. */
        QUOTE_DELETED,

        /** A written text named the active persona's real identity and the name was replaced (owner, 2026-10-30). */
        PERSONA_NAME_REPLACED,

        /** With a persona on, no free model answered the voice pass: the cards stay as the writer wrote them (owner, 2026-10-30). */
        VOICE_NOT_PASSED,

        /** No free model answered the briefing: the digest has none (owner, 2026-10-30). */
        BRIEFING_NOT_WRITTEN,
        FACT_UNSUPPORTED,
        FACT_SECOND_STORY,

        /** The fact check marked a whole card as another story: ignored (owner, 2026-10-29). */
        FACT_SECOND_STORY_IGNORED,
        FACT_UNCHECKED_NO_PAGE,
        FACT_BAD_SENTENCE_NUMBER,
        FACT_NOT_CHECKED,
        TOPIC_DROPPED_TWO_STORIES,

        /** The fact check found the title tells another event than the card's sentences (owner, 2026-10-30). */
        TOPIC_DROPPED_TITLE_OFF,
        TOPIC_DROPPED_EMPTY,
    }
}

/** Only `https` links with a host and no whitespace are ever shown (03-rendering.md section 1). */
internal fun isHttpsUrl(url: String): Boolean {
    if (!url.startsWith("https://", ignoreCase = true)) return false
    if (url.any { it.isWhitespace() || it.isISOControl() }) return false
    val host = url.substring("https://".length).takeWhile { it != '/' && it != '?' && it != '#' }
    return host.isNotEmpty() && !host.startsWith('.') && !host.contains('@')
}

/** Lower-cased scheme and host, no fragment, no trailing slash: two spellings of one page compare equal. */
internal fun normalizeUrl(url: String): String {
    val noFragment = url.substringBefore('#')
    val schemeEnd = noFragment.indexOf("://")
    val hostEnd = noFragment.indexOfAny(charArrayOf('/', '?'), startIndex = schemeEnd + 3).let { if (it < 0) noFragment.length else it }
    val head = noFragment.substring(0, hostEnd).lowercase()
    val rest = noFragment.substring(hostEnd).trimEnd('/')
    return head + rest
}
