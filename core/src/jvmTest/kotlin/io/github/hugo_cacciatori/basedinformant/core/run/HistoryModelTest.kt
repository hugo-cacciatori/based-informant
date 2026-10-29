package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.Source
import io.github.hugo_cacciatori.basedinformant.core.digest.Topic
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: the History tab's list, viewer and deletion (01-product.md section 5). */
class HistoryModelTest {
    private fun stored(at: Long, persona: String = "roach_king") = StoredDigest(
        runId = at, generatedAt = at, subject = "Subject $at",
        summary = "Summary $at", topicCount = 1, model = "openrouter:perplexity/sonar", attentionMode = "balanced", persona = persona,
        tokensBilled = 1_440,
        digest = Digest(
            2, LocalDate(2026, 10, 29), "Hello.", "Bye.",
            listOf(
                Topic(
                    "t1", "Stellarator research", "Stellarator holds plasma", "A long plasma.", "More text.", listOf("fusion energy"), 80, null,
                    listOf(Source("Plasma record", "Science Example", "https://science.example.com/a", "en", LocalDate(2026, 10, 28))),
                ),
            ),
        ),
    )

    private class MemoryHistory(val rows: MutableMap<Long, StoredDigest>) : HistoryStore {
        override suspend fun cards() = rows.entries.sortedByDescending { it.value.generatedAt }
            .map { (id, d) -> HistoryCard(id, d.generatedAt, d.topicCount, d.summary) }
        override suspend fun get(id: Long) = rows[id]
        override suspend fun delete(id: Long) { rows.remove(id) }
    }

    private val store = MemoryHistory(mutableMapOf(1L to stored(10), 2L to stored(20, persona = "off")))
    private val model = HistoryModel(store) { if (it == Persona.ROACH_KING) "Roach King" else null }

    @Test
    fun theListIsNewestFirst() = runBlocking {
        model.load()
        assertEquals(listOf(2L, 1L), model.state.value.cards.map { it.id })
        assertEquals("Summary 20", model.state.value.cards.first().summary)
    }

    @Test
    fun openingBuildsTheSameViewAsTheEmailWithThePersonaName() = runBlocking {
        model.open(1)
        val open = model.state.value.open!!
        assertEquals("Subject 10", open.stored.subject, "the top bar shows the stored subject")
        assertEquals("29 Oct 2026 · 1 topic · Roach King", open.view.headerLine)
        assertEquals("Stellarator holds plasma", open.view.cards.single().title)
        assertEquals("https://science.example.com/a", open.view.cards.single().sources.single().url)
        model.close()
        assertNull(model.state.value.open)
    }

    @Test
    fun theSharedMailIsBuiltFromTheStoredDigest() = runBlocking {
        val mail = model.mail(1)!!
        assertEquals("Subject 10", mail.subject)
        assertTrue("Stellarator holds plasma" in mail.text)
        assertTrue("https://science.example.com/a" in mail.text)
        assertTrue("Roach King" in mail.html)
        assertNull(model.mail(99))
    }

    @Test
    fun aDeletedDigestSaysSoAndShowsTheList() = runBlocking {
        model.open(99)
        assertNull(model.state.value.open)
        assertTrue(model.state.value.missing)
        assertEquals(2, model.state.value.cards.size)
        model.dismissMissing()
        assertFalse(model.state.value.missing)
    }

    @Test
    fun deletingClosesTheViewerAndRefreshesTheList() = runBlocking {
        model.open(1)
        model.delete(1)
        assertNull(model.state.value.open)
        assertEquals(listOf(2L), model.state.value.cards.map { it.id })
    }
}
