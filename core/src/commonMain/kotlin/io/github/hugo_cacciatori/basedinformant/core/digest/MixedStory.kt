package io.github.hugo_cacciatori.basedinformant.core.digest

/**
 * The mixed-story check (ai-call.md section 8, owner 2026-10-18): sonar sometimes puts two stories in one topic, and the
 * prompt alone did not stop it. Its own text often gives the mix away ("Another report said…", "A separate update
 * said…", "Several quantum computing developments…"). Free and offline: it looks at the topic's text only.
 */
object MixedStory {
    private val TELLTALES = listOf(
        Regex("\\banother (report|article|source|story|update)"),
        Regex("\\bseparate (report|update|source|article|story)s?\\b"),
        Regex("\\bseparately reported\\b"),
        Regex("\\bround-?ups?\\b"),
        Regex("\\bin other news\\b"),
        Regex("\\bcoverage this week\\b"),
        Regex("\\bseveral (\\S+ ){0,3}developments\\b"),
    )

    /** True when any of the topic's texts reads like more than one story. */
    fun looksMixed(texts: List<String?>): Boolean =
        texts.filterNotNull().map { it.lowercase() }.any { text -> TELLTALES.any { it.containsMatchIn(text) } }
}
