package io.github.hugo_cacciatori.basedinformant.core.limits

import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules.EditResult
import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules.Refusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextListRulesTest {
    private val rules = TextListRules.KEYWORDS

    @Test
    fun addTrimsAndKeepsTheOrderOfAdding() {
        val list = (rules.add(listOf("zeta"), "  alpha  ") as EditResult.Done).list
        assertEquals(listOf("zeta", "alpha"), list)
    }

    @Test
    fun addRefusesBlankTooLongDuplicateAndFull() {
        assertEquals(EditResult.Refused(Refusal.BLANK), rules.add(emptyList(), "   "))
        assertEquals(EditResult.Refused(Refusal.TOO_LONG), rules.add(emptyList(), "a".repeat(51)))
        assertEquals(EditResult.Refused(Refusal.DUPLICATE), rules.add(listOf("Solar Power"), " solar power "))
        assertEquals(EditResult.Refused(Refusal.FULL), rules.add(List(5) { "k$it" }, "one more"))
        assertTrue(rules.add(emptyList(), "🚀".repeat(50)) is EditResult.Done)
    }

    @Test
    fun editMayChangeTheCaseOfItselfButNotCopyAnother() {
        val list = listOf("alpha", "beta")
        assertEquals(EditResult.Done(listOf("Alpha", "beta")), rules.edit(list, 0, "Alpha "))
        assertEquals(EditResult.Refused(Refusal.DUPLICATE), rules.edit(list, 0, "BETA"))
        assertEquals(EditResult.Refused(Refusal.BLANK), rules.edit(list, 1, ""))
    }

    @Test
    fun undoPutsTheEntryBackAtItsPlace() {
        val deleted = rules.delete(listOf("a", "b", "c"), 1)
        assertEquals(listOf("a", "c"), deleted.list)
        assertEquals(listOf("a", "b", "c"), rules.undo(deleted.list, deleted.removed))
    }

    @Test
    fun undoChangesNothingWhenAnEqualEntryWasAddedSince() {
        val deleted = rules.delete(listOf("a", "b"), 1)
        val readded = (rules.add(deleted.list, "B") as EditResult.Done).list
        assertEquals(readded, rules.undo(readded, deleted.removed))
    }

    @Test
    fun undoAfterLaterDeletesStillFits() {
        val first = rules.delete(listOf("a", "b", "c"), 2)
        val second = rules.delete(first.list, 1)
        assertEquals(listOf("a", "c"), rules.undo(second.list, first.removed))
    }

    @Test
    fun tagsUseTheirOwnLimits() {
        assertEquals(EditResult.Refused(Refusal.TOO_LONG), TextListRules.TAGS.add(emptyList(), "a".repeat(31)))
        assertEquals(EditResult.Refused(Refusal.FULL), TextListRules.TAGS.add(List(15) { "t$it" }, "x"))
    }

    @Test
    fun countersCountCharactersAfterTrimming() {
        assertEquals(FieldCounter(3, 30), FieldCounter.tag("  abc \n"))
        assertTrue(FieldCounter.keyword("a".repeat(50)).canSave)
        assertFalse(FieldCounter.keyword("a".repeat(51)).canSave)
        assertEquals(2, FieldCounter.tag("🚀🚀").count)
    }
}
