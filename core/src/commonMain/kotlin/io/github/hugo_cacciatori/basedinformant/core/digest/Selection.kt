package io.github.hugo_cacciatori.basedinformant.core.digest

/** Local, deterministic selection (02-ai-call.md section 4, step 4). */
object Selection {
    /** Decided 2026-10-18. */
    const val MAX_TOPICS = 5

    /**
     * The keyword (or chosen-topic) topics first, even weak ones, sorted by score (ties keep the model's order), at most
     * [maxTopics]; then trending topics, by score, fill the free slots (owner, 2026-10-29 and 2026-10-30). No cutoff.
     */
    fun select(topics: List<Topic>, maxTopics: Int = MAX_TOPICS): List<Topic> {
        val chosen = topics.filter { !it.trending }.sortedByDescending { it.score }.take(maxTopics)
        return chosen + topics.filter { it.trending }.sortedByDescending { it.score }.take(maxTopics - chosen.size)
    }
}
