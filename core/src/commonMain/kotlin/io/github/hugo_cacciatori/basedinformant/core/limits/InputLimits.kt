package io.github.hugo_cacciatori.basedinformant.core.limits

/**
 * Input limits (02-ai-call.md section 2, decided 2026-10-18). The UI, the data layer and the prompt builder
 * all enforce them. Change them only with the owner's OK.
 *
 * Lengths count characters (Unicode code points), after trimming.
 */
object InputLimits {
    const val KEYWORDS_MIN = 1
    /** A digest takes exactly this many keywords, in one call (owner, 2026-10-20). */
    const val KEYWORDS_MAX = 5
    const val KEYWORD_MAX_CHARS = 50
    const val TAGS_MAX = 15
    const val TAG_MAX_CHARS = 30
    const val RECIPIENT_MAX_CHARS = 254
}

/** One broken limit. Carries positions and sizes only, never the text itself. */
sealed interface LimitViolation {
    data class TooFewKeywords(val count: Int) : LimitViolation
    data class TooManyKeywords(val count: Int) : LimitViolation
    data class KeywordTooLong(val index: Int, val length: Int) : LimitViolation
    data class BlankKeyword(val index: Int) : LimitViolation
    data class DuplicateKeyword(val index: Int, val firstIndex: Int) : LimitViolation
    data class TooManyTags(val count: Int) : LimitViolation
    data class TagTooLong(val index: Int, val length: Int) : LimitViolation
    data class BlankTag(val index: Int) : LimitViolation
    data class RecipientTooLong(val length: Int) : LimitViolation
}

/** Number of Unicode code points: what the owner sees as characters. */
fun String.charCount(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

object KeywordRules {
    /** Keywords are stored trimmed. */
    fun normalize(text: String): String = text.trim()

    /** A keyword equal to an existing one, ignoring case, is refused. */
    fun isDuplicate(existing: List<String>, candidate: String): Boolean {
        val key = normalize(candidate).lowercase()
        return existing.any { normalize(it).lowercase() == key }
    }
}

object InputChecks {
    fun keywords(keywords: List<String>): List<LimitViolation> = buildList {
        if (keywords.size < InputLimits.KEYWORDS_MIN) add(LimitViolation.TooFewKeywords(keywords.size))
        if (keywords.size > InputLimits.KEYWORDS_MAX) add(LimitViolation.TooManyKeywords(keywords.size))
        val seen = mutableMapOf<String, Int>()
        keywords.forEachIndexed { index, raw ->
            val text = KeywordRules.normalize(raw)
            val length = text.charCount()
            when {
                text.isEmpty() -> add(LimitViolation.BlankKeyword(index))
                length > InputLimits.KEYWORD_MAX_CHARS -> add(LimitViolation.KeywordTooLong(index, length))
            }
            val first = seen.getOrPut(text.lowercase()) { index }
            if (text.isNotEmpty() && first != index) add(LimitViolation.DuplicateKeyword(index, first))
        }
    }

    fun tags(tags: List<String>): List<LimitViolation> = buildList {
        if (tags.size > InputLimits.TAGS_MAX) add(LimitViolation.TooManyTags(tags.size))
        tags.forEachIndexed { index, raw ->
            val text = raw.trim()
            val length = text.charCount()
            when {
                text.isEmpty() -> add(LimitViolation.BlankTag(index))
                length > InputLimits.TAG_MAX_CHARS -> add(LimitViolation.TagTooLong(index, length))
            }
        }
    }

    fun recipient(address: String): List<LimitViolation> {
        val length = address.trim().charCount()
        return if (length > InputLimits.RECIPIENT_MAX_CHARS) listOf(LimitViolation.RecipientTooLong(length)) else emptyList()
    }
}
