package io.github.hugo_cacciatori.basedinformant.data

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoomKeywordStoreTest {
    private val folder = Files.createTempDirectory("bi-data").toFile()
    private val db = buildDatabase(databaseBuilder(folder))
    private var now = 1_000L
    private val store = RoomKeywordStore(db) { now }

    @AfterTest
    fun close() {
        db.close()
        folder.deleteRecursively()
    }

    @Test
    fun savesAndLoadsInOrderTrimmed() = runBlocking {
        store.save(listOf(" zeta ", "alpha"))
        assertEquals(listOf("zeta", "alpha"), store.load())
        store.save(emptyList())
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun aKeptEntryKeepsItsCreationTime() = runBlocking {
        store.save(listOf("alpha"))
        now = 2_000L
        store.save(listOf("beta", "alpha"))
        val rows = db.keywords().all()
        assertEquals(listOf("beta" to 2_000L, "alpha" to 1_000L), rows.map { it.text to it.createdAtMillis })
        assertEquals(listOf(0, 1), rows.map { it.position })
    }

    @Test
    fun theDataLayerRefusesListsOverTheLimits() = runBlocking {
        store.save(listOf("kept"))
        assertFailsWith<IllegalArgumentException> { store.save(List(26) { "k$it" }) }
        assertFailsWith<IllegalArgumentException> { store.save(listOf("a".repeat(51))) }
        assertFailsWith<IllegalArgumentException> { store.save(listOf("Same", "same")) }
        assertFailsWith<IllegalArgumentException> { store.save(listOf(" ")) }
        assertEquals(listOf("kept"), store.load())
    }

    @Test
    fun theListSurvivesReopening() = runBlocking {
        store.save(listOf("alpha", "beta"))
        db.close()
        val reopened = buildDatabase(databaseBuilder(folder))
        try {
            assertEquals(listOf("alpha", "beta"), RoomKeywordStore(reopened) { now }.load())
        } finally {
            reopened.close()
        }
    }
}
