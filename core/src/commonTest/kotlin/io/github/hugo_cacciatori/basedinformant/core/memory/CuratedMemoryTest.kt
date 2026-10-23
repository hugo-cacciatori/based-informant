package io.github.hugo_cacciatori.basedinformant.core.memory

import io.github.hugo_cacciatori.basedinformant.core.digest.Source
import io.github.hugo_cacciatori.basedinformant.core.digest.Topic
import io.github.hugo_cacciatori.basedinformant.core.limits.InputChecks
import io.github.hugo_cacciatori.basedinformant.core.limits.charCount
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CuratedMemoryTest {
    private val today = LocalDate(2026, 10, 18)

    private fun entry(id: String, last: LocalDate, times: Int = 1) =
        CuratedTopic(id, "Title $id", "Gist $id.", last, last, times, 60)

    private fun topic(id: String, continues: String? = null, summary: String = "First sentence. Second one.") = Topic(
        id = id, continues = continues, newDevelopment = null, title = "Title $id", summary = summary, detail = null,
        keywords = listOf("k"), score = 70, scoreParts = null,
        sources = listOf(Source("t", "p", "https://example.com/$id", "en", today)),
    )

    @Test
    fun pruneDropsEntriesOlderThanThirtyDays() {
        val kept = entry("kept", today.minus(DatePeriod(days = 30)))
        val dropped = entry("dropped", today.minus(DatePeriod(days = 31)))
        assertEquals(listOf("kept"), CuratedMemory.prune(listOf(dropped, kept), today).map { it.id })
    }

    @Test
    fun pruneKeepsTheSixtyMostRecentlyFeatured() {
        val entries = (0 until 70).map { entry("t$it", today.minus(DatePeriod(days = it % 30))) }
        val pruned = CuratedMemory.prune(entries, today)
        assertEquals(60, pruned.size)
        val oldestKept = pruned.minOf { it.lastFeatured }
        assertTrue(entries.filter { it !in pruned }.all { it.lastFeatured <= oldestKept })
    }

    @Test
    fun recordAddsNewTopics() {
        val result = CuratedMemory.record(emptyList(), listOf(topic("a")), today)
        assertEquals(listOf(CuratedTopic("a", "Title a", "First sentence.", today, today, 1, 70)), result)
    }

    @Test
    fun recordUpdatesAContinuedTopicUnderItsOriginalId() {
        val first = LocalDate(2026, 10, 1)
        val old = entry("talks", first, times = 2)
        val result = CuratedMemory.record(listOf(old), listOf(topic("vote", continues = "talks")), today).single()
        assertEquals("talks", result.id)
        assertEquals(first, result.firstFeatured)
        assertEquals(today, result.lastFeatured)
        assertEquals(3, result.timesFeatured)
        assertEquals("Title vote", result.title)
    }

    @Test
    fun aContinuesPointingNowhereCreatesAnEntryUnderTheTopicId() {
        val result = CuratedMemory.record(emptyList(), listOf(topic("vote", continues = "unknown")), today)
        assertEquals(listOf("vote"), result.map { it.id })
    }

    @Test
    fun gistIsTheFirstSentenceOnOneLine() {
        assertEquals("A record run.", CuratedMemory.gistOf("A record\n run. Then more."))
        assertEquals("v1.2 shipped today.", CuratedMemory.gistOf("v1.2 shipped today."))
        assertEquals(CuratedMemory.GIST_MAX_CHARS, CuratedMemory.gistOf("x".repeat(200)).charCount())
    }

    @Test
    fun promptLinesRespectTheInputLimits() {
        val long = CuratedTopic("id", "T".repeat(200), "G".repeat(80), today, today, 4, 90)
        val lines = CuratedMemory.promptLines(listOf(long, entry("b", today.minus(DatePeriod(days = 1)))))
        assertTrue(InputChecks.memory(lines).isEmpty())
        assertTrue(lines[0].startsWith("id | last 2026-10-18 | x4 | TTT"))
        assertTrue(lines[0].endsWith("…"))
        assertEquals("b | last 2026-10-17 | x1 | Title b: Gist b.", lines[1])
    }
}
