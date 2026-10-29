package io.github.hugo_cacciatori.basedinformant.core.settings

import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeKeywordStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeRfab
import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeSecretStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePresetStore : PresetStore {
    val presets = mutableListOf<Preset>()
    private var next = 1L

    override suspend fun all() = presets.toList()

    override suspend fun add(keywords: List<String>): Long = next++.also { presets += Preset(it, keywords) }

    override suspend fun delete(id: Long) {
        presets.removeAll { it.id == id }
    }
}

/** Offline: keyword presets (01-product.md section 3, owner 2026-10-23 and 2026-10-29). */
class PresetsTest {
    private val five = listOf("fusion energy", "deep-sea mining", "chess", "Mars exploration", "quantum computing")
    private val keywordStore = FakeKeywordStore(five)
    private val keywords = KeywordsModel(keywordStore, FakeSecretStore(), FakeRfab(emptyMap())::clientFor)
    private val store = FakePresetStore()
    private val presets = PresetsModel(store, keywords)

    @Test
    fun theCurrentFiveKeywordsAreSavedOnceInAnyOrderOrCase() = runBlocking {
        keywords.load()
        presets.load()
        assertTrue(presets.saveCurrent())
        assertEquals(listOf(five), presets.state.value.presets.map { it.keywords })

        keywords.replace(five.reversed().map { it.uppercase() })
        assertFalse(presets.saveCurrent())
        assertEquals(PresetRules.Refusal.ALREADY_SAVED, presets.state.value.refusal)
        assertEquals(1, store.presets.size)
    }

    @Test
    fun onlyFiveKeywordsAndAtMostTwentyPresets() = runBlocking {
        keywordStore.keywords = five.take(4)
        keywords.load()
        assertFalse(presets.saveCurrent())
        assertEquals(PresetRules.Refusal.NOT_FIVE_KEYWORDS, presets.state.value.refusal)

        repeat(PresetRules.MAX_PRESETS) { i -> store.add(five.take(4) + "k$i") }
        keywords.replace(five)
        assertFalse(presets.saveCurrent())
        assertEquals(PresetRules.Refusal.FULL, presets.state.value.refusal)
    }

    @Test
    fun usingAPresetReplacesTheKeywordsWithUndo() = runBlocking {
        keywords.load()
        val id = store.add(listOf("a", "b", "c", "d", "e"))
        presets.load()
        presets.use(id)
        assertEquals(listOf("a", "b", "c", "d", "e"), keywords.state.value.keywords)
        assertEquals(listOf("a", "b", "c", "d", "e"), keywordStore.keywords, "saved at once")
        keywords.undo()
        assertEquals(five, keywords.state.value.keywords)
        assertEquals(five, keywordStore.keywords)
        assertNull(keywords.state.value.restore)
    }

    @Test
    fun aPresetCanBeDeleted() = runBlocking {
        val id = store.add(five)
        presets.load()
        presets.delete(id)
        assertTrue(presets.state.value.presets.isEmpty())
    }
}
