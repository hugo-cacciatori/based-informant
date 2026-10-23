package io.github.hugo_cacciatori.basedinformant.data

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migration tests (platforms.md section 7): every schema version is exported to `data/schemas/` and committed; each
 * new version gets a test that fills the one before and runs `runMigrationsAndValidate`.
 */
class SchemaTest {
    private val schemas: Path = Path.of(System.getProperty("user.dir"), "schemas")
    private val folder = Files.createTempDirectory("bi-schema")
    private val helper = MigrationTestHelper(
        schemaDirectoryPath = schemas,
        databasePath = folder.resolve("schema.db"),
        driver = BundledSQLiteDriver(),
        databaseClass = AppDatabase::class,
    )

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun everyVersionIsExported() {
        for (version in 1..2) {
            assertTrue(Files.exists(schemas.resolve(AppDatabase::class.qualifiedName!!).resolve("$version.json")), "missing $version.json")
        }
    }

    @Test
    fun version1To2KeepsTheKeywordsAndAddsTheRunTables() = runBlocking {
        helper.createDatabase(1).use { connection ->
            connection.execSQL("INSERT INTO keywords (text, position, created_at) VALUES ('alpha', 0, 1)")
        }
        helper.runMigrationsAndValidate(2).use { connection ->
            connection.prepare("SELECT text FROM keywords").use { statement ->
                assertTrue(statement.step())
                assertEquals("alpha", statement.getText(0))
            }
            for (table in listOf("runs", "calls", "checks", "digests", "curated_topics")) {
                connection.prepare("SELECT COUNT(*) FROM $table").use { statement ->
                    assertTrue(statement.step())
                    assertEquals(0L, statement.getLong(0))
                }
            }
        }
        Unit
    }
}
