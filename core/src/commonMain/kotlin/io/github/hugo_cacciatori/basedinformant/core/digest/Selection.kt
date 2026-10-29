package io.github.hugo_cacciatori.basedinformant.core.digest

/** Local, deterministic selection (02-ai-call.md section 4, step 4). */
object Selection {
    /** [OPEN] Starting value; tuned in the lab. */
    const val MIN_SCORE = 50

    /** Decided 2026-10-18. */
    const val MAX_TOPICS = 5

    /**
     * The keyword (or chosen-topic) topics first: below [minScore] dropped, sorted by score (ties keep the model's order),
     * at most [maxTopics]. Then trending topics, by score, fill the free slots up to [maxTopics] (owner, 2026-10-29); they
     * have no keyword to score against, so no cutoff.
     */
    fun select(topics: List<Topic>, minScore: Int = MIN_SCORE, maxTopics: Int = MAX_TOPICS): List<Topic> {
        val chosen = topics.filter { !it.trending && it.score >= minScore }.sortedByDescending { it.score }.take(maxTopics)
        return chosen + topics.filter { it.trending }.sortedByDescending { it.score }.take(maxTopics - chosen.size)
    }
}
