package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionTest {
    private fun topic(id: String, score: Int) = Topic(
        id = id, title = id, summary = id, detail = null,
        keywords = listOf("k"), score = score, scoreParts = null,
        sources = listOf(Source("t", "p", "https://example.com/$id", "en", LocalDate(2026, 10, 18))),
    )

    @Test
    fun dropsBelowMinSortsAndCaps() {
        val topics = listOf(49, 50, 90, 70, 70, 80, 60, 100).mapIndexed { i, s -> topic("t$i", s) }
        val selected = Selection.select(topics)
        assertEquals(Selection.MAX_TOPICS, selected.size)
        assertEquals(listOf(100, 90, 80, 70, 70), selected.map { it.score })
        // Ties keep the model's order.
        assertEquals(listOf("t3", "t4"), selected.filter { it.score == 70 }.map { it.id })
        assertTrue(selected.none { it.score < Selection.MIN_SCORE })
    }

    @Test
    fun zeroTopicsIsAValidResult() {
        assertTrue(Selection.select(listOf(topic("low", 10))).isEmpty())
    }
}
