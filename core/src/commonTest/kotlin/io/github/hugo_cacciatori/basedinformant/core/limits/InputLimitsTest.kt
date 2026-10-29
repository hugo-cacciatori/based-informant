package io.github.hugo_cacciatori.basedinformant.core.limits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputLimitsTest {
    @Test
    fun keywordCountLimits() {
        assertEquals(listOf(LimitViolation.TooFewKeywords(0)), InputChecks.keywords(emptyList()))
        assertTrue(InputChecks.keywords(List(5) { "k$it" }).isEmpty())
        assertEquals(listOf(LimitViolation.TooManyKeywords(6)), InputChecks.keywords(List(6) { "k$it" }))
    }

    @Test
    fun keywordLengthCountsCharactersAfterTrimming() {
        assertTrue(InputChecks.keywords(listOf("  " + "a".repeat(50) + "  ")).isEmpty())
        assertEquals(listOf(LimitViolation.KeywordTooLong(0, 51)), InputChecks.keywords(listOf("a".repeat(51))))
        // An emoji is one character, though two UTF-16 units.
        assertTrue(InputChecks.keywords(listOf("🚀".repeat(50))).isEmpty())
    }

    @Test
    fun blankAndDuplicateKeywordsAreRefused() {
        assertEquals(
            listOf(LimitViolation.BlankKeyword(1), LimitViolation.DuplicateKeyword(2, 0)),
            InputChecks.keywords(listOf("Chess", "   ", " chess ")),
        )
        assertTrue(KeywordRules.isDuplicate(listOf("Chess"), "  CHESS"))
        assertFalse(KeywordRules.isDuplicate(listOf("Chess"), "chess openings"))
    }

    @Test
    fun tagLimits() {
        assertTrue(InputChecks.tags(List(15) { "t$it" }).isEmpty())
        assertEquals(listOf(LimitViolation.TooManyTags(16)), InputChecks.tags(List(16) { "t$it" }))
        assertEquals(listOf(LimitViolation.TagTooLong(0, 31)), InputChecks.tags(listOf("t".repeat(31))))
        assertEquals(listOf(LimitViolation.BlankTag(0)), InputChecks.tags(listOf(" ")))
    }
}
