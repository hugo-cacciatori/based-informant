package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** What the run knows that the answer is checked against. */
data class ValidationContext(
    /** The owner's keywords from the run's snapshot. */
    val keywords: List<String>,
    val window: RecencyWindow,
)

/**
 * Something the validator dropped or noticed. Positions and kinds only, never model text. [call] is the call's index
 * when a run made several calls; positions are then within that call's answer (link issues: within the merged digest).
 */
data class Issue(val kind: Kind, val topicIndex: Int? = null, val sourceIndex: Int? = null, val call: Int? = null) {
    enum class Kind {
        TRUNCATED,
        TOPIC_NOT_AN_OBJECT,
        TOPIC_MISSING_FIELD,
        TOPIC_BAD_SCORE,

        /** The model's total differed from its parts; the parts' sum is used. */
        TOPIC_SCORE_CORRECTED,
        TOPIC_NOT_LINKED_TO_KEYWORDS,

        /** The topic's own text reads like two stories in one (mixed-story check). */
        TOPIC_MIXED_STORIES,
        TOPIC_NO_VALID_SOURCE,
        TOPIC_DUPLICATE_ID,
        SOURCE_NOT_AN_OBJECT,
        SOURCE_MISSING_FIELD,
        SOURCE_NOT_HTTPS,

        /** The URL looks like a homepage, index, archive, event or calendar page (source-type filter). */
        SOURCE_NOT_AN_ARTICLE,
        SOURCE_BAD_DATE,
        SOURCE_OUTSIDE_WINDOW,
        SOURCE_DUPLICATE_URL,
        SOURCE_DEAD_LINK,
        SOURCE_PAGE_DATE_OUTSIDE_WINDOW,
        SOURCE_DATE_CORRECTED,
        TOPIC_NO_LIVE_SOURCE,

        /** Two calls returned the same story (same id or a shared source URL): the later one is dropped. */
        TOPIC_DUPLICATE_STORY,

        /** A call of a split run gave no readable answer; the other calls still count. */
        CALL_UNREADABLE,
    }
}

/**
 * Checks the parsed answer against the contract and keeps only what is safe to show (02-ai-call.md sections 6-8).
 * A bad header fails the whole answer; a bad topic or source is dropped and reported as an [Issue].
 */
object DigestValidator {
    /** The template versions this parser accepts. Template v1 is still a draft (02-ai-call.md section 11). */
    const val CURRENT_TEMPLATE_VERSION = 1

    /** Sources dated one day after the device's date are kept: the publisher may be in a later time zone. */
    private val FUTURE_TOLERANCE = DatePeriod(days = 1)

    sealed interface Result {
        data class Valid(val digest: Digest, val issues: List<Issue>) : Result
        data class Invalid(val reason: Failure) : Result
    }

    enum class Failure { UNSUPPORTED_TEMPLATE_VERSION, MISSING_TEMPLATE_VERSION, BAD_GENERATED_AT, MISSING_TOPICS }

    fun validate(json: JsonObject, context: ValidationContext): Result {
        val version = (json["template_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: return Result.Invalid(Failure.MISSING_TEMPLATE_VERSION)
        if (version != CURRENT_TEMPLATE_VERSION) return Result.Invalid(Failure.UNSUPPORTED_TEMPLATE_VERSION)
        val generatedAt = json.date("generated_at") ?: return Result.Invalid(Failure.BAD_GENERATED_AT)
        val topicsJson = json["topics"] as? JsonArray ?: return Result.Invalid(Failure.MISSING_TOPICS)

        val issues = mutableListOf<Issue>()
        val ownerKeywords = context.keywords.associateBy { it.trim().lowercase() }
        val seenIds = mutableSetOf<String>()
        val topics = topicsJson.mapIndexedNotNull { index, element ->
            topic(element, index, ownerKeywords, context.window, issues)?.takeIf { topic ->
                seenIds.add(topic.id).also { if (!it) issues += Issue(Issue.Kind.TOPIC_DUPLICATE_ID, index) }
            }
        }
        val digest = Digest(
            templateVersion = version,
            generatedAt = generatedAt,
            opening = json.text("opening"),
            closing = json.text("closing"),
            topics = topics,
        )
        return Result.Valid(digest, issues)
    }

    private fun topic(
        element: JsonElement,
        index: Int,
        ownerKeywords: Map<String, String>,
        window: RecencyWindow,
        issues: MutableList<Issue>,
    ): Topic? {
        val obj = element as? JsonObject ?: return null.also { issues += Issue(Issue.Kind.TOPIC_NOT_AN_OBJECT, index) }
        val id = obj.text("id")
        val title = obj.text("title")
        val summary = obj.text("summary")
        if (id == null || title == null || summary == null) {
            issues += Issue(Issue.Kind.TOPIC_MISSING_FIELD, index)
            return null
        }
        // The score is computed here from its parts (owner, 2026-10-18): models get the sum wrong. The model's own
        // total is used only when the parts are missing.
        val parts = scoreParts(obj["score_parts"])
        val modelScore = (obj["score"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..100 }
        val score = parts?.total() ?: modelScore
        if (score == null) {
            issues += Issue(Issue.Kind.TOPIC_BAD_SCORE, index)
            return null
        }
        if (parts != null && modelScore != score) issues += Issue(Issue.Kind.TOPIC_SCORE_CORRECTED, index)
        // Eligibility rule (02-ai-call.md section 4): a topic must match at least one of the owner's keywords.
        val matched = (obj["keywords"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.lowercase() }
            .mapNotNull { ownerKeywords[it] }
            .distinct()
        if (matched.isEmpty()) {
            issues += Issue(Issue.Kind.TOPIC_NOT_LINKED_TO_KEYWORDS, index)
            return null
        }
        if (MixedStory.looksMixed(listOf(title, summary, obj.text("detail"), obj.text("new_development")))) {
            issues += Issue(Issue.Kind.TOPIC_MIXED_STORIES, index)
            return null
        }
        val seenUrls = mutableSetOf<String>()
        val sources = (obj["sources"] as? JsonArray).orEmpty().mapIndexedNotNull { sourceIndex, s ->
            source(s, index, sourceIndex, window, issues)?.takeIf { source ->
                seenUrls.add(normalizeUrl(source.url)).also {
                    if (!it) issues += Issue(Issue.Kind.SOURCE_DUPLICATE_URL, index, sourceIndex)
                }
            }
        }
        if (sources.isEmpty()) {
            issues += Issue(Issue.Kind.TOPIC_NO_VALID_SOURCE, index)
            return null
        }
        return Topic(
            id = id,
            continues = obj.text("continues"),
            newDevelopment = obj.text("new_development"),
            title = title,
            summary = summary,
            detail = obj.text("detail"),
            keywords = matched,
            score = score,
            scoreParts = parts,
            sources = sources,
        )
    }

    private fun source(
        element: JsonElement,
        topicIndex: Int,
        index: Int,
        window: RecencyWindow,
        issues: MutableList<Issue>,
    ): Source? {
        fun drop(kind: Issue.Kind): Source? = null.also { issues += Issue(kind, topicIndex, index) }
        val obj = element as? JsonObject ?: return drop(Issue.Kind.SOURCE_NOT_AN_OBJECT)
        val title = obj.text("title")
        val publisher = obj.text("publisher")
        val url = obj.text("url")
        val language = obj.text("language")
        if (title == null || publisher == null || url == null || language == null) return drop(Issue.Kind.SOURCE_MISSING_FIELD)
        if (!isHttpsUrl(url)) return drop(Issue.Kind.SOURCE_NOT_HTTPS)
        if (!ArticleUrl.looksLikeArticle(url)) return drop(Issue.Kind.SOURCE_NOT_AN_ARTICLE)
        val published = obj.date("published") ?: return drop(Issue.Kind.SOURCE_BAD_DATE)
        if (published < window.start || published > window.end.plus(FUTURE_TOLERANCE)) {
            return drop(Issue.Kind.SOURCE_OUTSIDE_WINDOW)
        }
        return Source(title, publisher, url, language, published)
    }

    /** The three parts, each clamped to its range in the template (relevance 0-60, attention 0-40, penalty 0-50). */
    private fun scoreParts(element: JsonElement?): ScoreParts? {
        val obj = element as? JsonObject ?: return null
        fun int(name: String) = (obj[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        return ScoreParts(
            relevance = (int("relevance") ?: return null).coerceIn(0, ScoreParts.MAX_RELEVANCE),
            attention = (int("attention") ?: return null).coerceIn(0, ScoreParts.MAX_ATTENTION),
            repeatPenalty = (int("repeat_penalty") ?: return null).coerceIn(0, ScoreParts.MAX_REPEAT_PENALTY),
        )
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

    private fun JsonObject.text(name: String): String? {
        val value = this[name]
        if (value == null || value is JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.date(name: String): LocalDate? = text(name)?.let {
        try {
            LocalDate.parse(it)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
