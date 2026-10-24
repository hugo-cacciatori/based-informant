package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the synthetic fixtures (07-testing.md) through parse, validate and select. */
class DigestPipelineFixtureTest {
    private val context = ValidationContext(
        keywords = listOf("fusion energy", "deep-sea mining", "chess"),
        window = RecencyWindow.of(today = LocalDate(2026, 10, 18), lastDigestDate = null),
    )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    private fun processed(name: String): DigestPipeline.Result.Processed =
        assertIs<DigestPipeline.Result.Processed>(DigestPipeline.process(fixture(name), context))

    private fun List<Issue>.kinds() = map { it.kind }

    @Test
    fun goodAnswerKeepsTopicsAboveMinScoreSortedByScore() {
        val result = processed("good.json")
        assertEquals(3, result.validTopicCount)
        assertEquals(listOf("stellarator-record-run", "seabed-mining-moratorium-vote"), result.digest.topics.map { it.id })
        assertEquals("Three stories this week.", result.digest.opening)
        assertEquals(LocalDate(2026, 10, 18), result.digest.generatedAt)
        assertTrue(result.issues.isEmpty())

        val first = result.digest.topics[0]
        assertEquals(2, first.sources.size)
        assertEquals("fr", first.sources[1].language)
        assertEquals(ScoreParts(relevance = 45, attention = 37, repeatPenalty = 0), first.scoreParts)

        val second = result.digest.topics[1]
        assertEquals("seabed-mining-talks", second.continues)
        assertNull(second.detail)
        // Matched keywords are reported in the owner's own spelling.
        assertEquals(listOf("deep-sea mining"), second.keywords)
    }

    @Test
    fun keywordsSplitIntoGroupsInOrder() {
        val keywords = (1..12).map { "k$it" }
        assertEquals(listOf(5, 5, 2), KeywordGroups.split(keywords).map { it.size })
        assertEquals(listOf("k1", "k2", "k3", "k4", "k5"), KeywordGroups.split(keywords).first())
        assertEquals(12, KeywordGroups.split(keywords, 1).size)
        assertEquals(listOf(keywords), KeywordGroups.split(keywords, 25))
    }

    @Test
    fun splitAnswersAreMergedWithoutRepeatingAStory() {
        val result = assertIs<DigestPipeline.Result.Processed>(
            DigestPipeline.processAll(listOf(fixture("good.json"), fixture("free-neutral.json")), context),
        )
        val ids = result.digest.topics.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue(result.issues.any { it.kind == Issue.Kind.TOPIC_DUPLICATE_STORY && it.call == 1 })
        assertEquals("Three stories this week.", result.digest.opening)
    }

    @Test
    fun anUnreadableCallDoesNotSpoilTheOthers() {
        val result = assertIs<DigestPipeline.Result.Processed>(
            DigestPipeline.processAll(listOf(fixture("good.json"), fixture("malformed.txt"), null), context),
        )
        assertEquals(3, result.validTopicCount)
        assertEquals(listOf(1, 2), result.issues.filter { it.kind == Issue.Kind.CALL_UNREADABLE }.map { it.call })
        assertIs<DigestPipeline.Result.Unreadable>(DigestPipeline.processAll(listOf(fixture("malformed.txt"), null), context))
    }

    /** Real free-model answers (template v1 draft, synthetic search results), one neutral and one per persona. */
    @Test
    fun savedModelAnswersStayReadable() {
        for (name in listOf("free-neutral.json", "persona-roach-king.json", "persona-doctor-wife.json", "persona-dog-zapper.json")) {
            val result = processed(name)
            assertTrue(result.digest.topics.isNotEmpty(), name)
            assertTrue(result.issues.isEmpty(), "$name: ${result.issues}")
            val seabed = result.digest.topics.first { it.id == "seabed-mining-moratorium-vote" }
            assertEquals("seabed-mining-talks", seabed.continues, name)
        }
    }

    /** The first paid answer (sonar, template v1 draft, 2026-10-18). */
    @Test
    fun firstPaidAnswerStaysReadable() {
        val result = processed("sonar-call1.json")
        assertEquals(5, result.validTopicCount)
        assertEquals(3, result.digest.topics.size)
        assertTrue(result.issues.isEmpty(), "${result.issues}")
    }

    /** The second paid answer (15 fake keywords): sources outside the window are dropped, as in the real run. */
    @Test
    fun secondPaidAnswerDropsOutOfWindowSources() {
        val wide = context.copy(keywords = listOf("fusion energy", "deep-sea mining", "Mars exploration"))
        val result = assertIs<DigestPipeline.Result.Processed>(DigestPipeline.process(fixture("sonar-call2.json"), wide))
        // The source-type filter drops the NASA month archive and both event pages before the window check.
        assertEquals(2, result.validTopicCount)
        assertEquals(3, result.issues.count { it.kind == Issue.Kind.SOURCE_OUTSIDE_WINDOW })
        assertEquals(3, result.issues.count { it.kind == Issue.Kind.SOURCE_NOT_AN_ARTICLE })
    }

    @Test
    fun emptyAnswerIsValidWithNoTopics() {
        val result = processed("empty.json")
        assertEquals(0, result.digest.topics.size)
        assertNull(result.digest.opening)
    }

    @Test
    fun fencedAnswerIsRead() {
        assertEquals(1, processed("fenced.txt").digest.topics.size)
    }

    @Test
    fun proseIsUnreadable() {
        val result = assertIs<DigestPipeline.Result.Unreadable>(DigestPipeline.process(fixture("malformed.txt"), context))
        assertEquals(DigestParser.Failure.NOT_JSON, result.parseFailure)
    }

    @Test
    fun truncatedAnswerKeepsTheCompleteTopics() {
        val result = processed("truncated.json")
        assertEquals(listOf("stellarator-record-run", "seabed-mining-moratorium-vote"), result.digest.topics.map { it.id })
        assertEquals("Two complete stories, one cut off.", result.digest.opening)
        assertEquals(Issue.Kind.TRUNCATED, result.issues.first().kind)
    }

    @Test
    fun onlyHttpsSourcesInsideTheWindowSurvive() {
        val result = processed("bad-urls.json")
        val topic = result.digest.topics.single()
        assertEquals(listOf("https://e.example.com/story"), topic.sources.map { it.url })
        assertEquals(
            listOf(
                Issue.Kind.SOURCE_NOT_HTTPS,
                Issue.Kind.SOURCE_NOT_HTTPS,
                Issue.Kind.SOURCE_NOT_HTTPS,
                Issue.Kind.SOURCE_OUTSIDE_WINDOW,
                Issue.Kind.SOURCE_NOT_HTTPS,
                Issue.Kind.TOPIC_NO_VALID_SOURCE,
            ),
            result.issues.kinds(),
        )
    }

    @Test
    fun repeatsAndTopicsOutsideTheKeywordsAreDropped() {
        val result = processed("repeat-topics.json")
        val topic = result.digest.topics.single()
        assertEquals("Test stellarator holds plasma for a record 40 minutes", topic.title)
        assertEquals(listOf("fusion energy"), topic.keywords)
        assertEquals(1, topic.sources.size)
        assertEquals(
            listOf(Issue.Kind.SOURCE_DUPLICATE_URL, Issue.Kind.TOPIC_DUPLICATE_ID, Issue.Kind.TOPIC_NOT_LINKED_TO_KEYWORDS),
            result.issues.kinds(),
        )
    }
}
