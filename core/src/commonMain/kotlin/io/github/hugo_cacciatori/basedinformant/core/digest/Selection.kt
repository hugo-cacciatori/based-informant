package io.github.hugo_cacciatori.basedinformant.core.digest

/** Local, deterministic selection (02-ai-call.md section 4, step 4). */
object Selection {
    /** [OPEN] Starting value; tuned in the lab. */
    const val MIN_SCORE = 50

    /** Decided 2026-10-18. */
    const val MAX_TOPICS = 5

    /** Drops topics below [minScore], sorts by score (ties keep the model's order), keeps at most [maxTopics]. */
    fun select(topics: List<Topic>, minScore: Int = MIN_SCORE, maxTopics: Int = MAX_TOPICS): List<Topic> =
        topics.filter { it.score >= minScore }.sortedByDescending { it.score }.take(maxTopics)
}
