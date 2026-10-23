package io.github.hugo_cacciatori.basedinformant.core.digest

/**
 * Splits the owner's keywords into groups, one paid call per group (ai-call.md section 11 "Coverage plan"). Sonar
 * covered only 2-5 of 15 keywords in one call; smaller groups should leave more topics after the app's checks.
 */
object KeywordGroups {
    /** The app always splits, in groups of 5 (owner, 2026-10-18, after the split benchmark): 1,440 tokens per group on sonar. */
    const val DEFAULT_SIZE = 5

    /** Groups of at most [size], in the owner's order; the last one may be smaller. */
    fun split(keywords: List<String>, size: Int = DEFAULT_SIZE): List<List<String>> {
        require(size >= 1) { "group size must be at least 1" }
        return keywords.chunked(size)
    }
}
