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
 * Migration tests (04-platforms.md section 7): every schema version is exported to `data/schemas/` and committed; each
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
        for (version in 1..7) {
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
            for (table in listOf("runs", "calls", "checks", "digests")) {
                connection.prepare("SELECT COUNT(*) FROM $table").use { statement ->
                    assertTrue(statement.step())
                    assertEquals(0L, statement.getLong(0))
                }
            }
        }
        Unit
    }

    @Test
    fun version2To3KeepsRunsCallsAndDigestsAndDropsTheMemory() = runBlocking {
        helper.createDatabase(2).use { connection ->
            connection.execSQL("INSERT INTO keywords (text, position, created_at) VALUES ('alpha', 0, 1)")
            connection.execSQL(
                "INSERT INTO calls (run_id, at, model, template_version, keywords, tags, attention_mode, persona, wide_window, " +
                    "prompt_chars, latency_ms, tokens_billed, error, valid_topics, selected_topics, repeats, issues) " +
                    "VALUES (1, 2, 'm', 1, '[]', '[]', 'BALANCED', 'OFF', 0, 900, 7000, 1440, NULL, 3, 2, 1, '{}')",
            )
            connection.execSQL(
                "INSERT INTO digests (run_id, status, generated_at, subject, html, text, summary, topic_count, digest, model, " +
                    "attention_mode, persona, recipient, tokens_billed) VALUES (1, 'SAVED', 5, 'S', '<p>x</p>', 'x', 's', 1, '{}', 'm', 'BALANCED', 'OFF', NULL, 1440)",
            )
            connection.execSQL(
                "INSERT INTO curated_topics (id, title, gist, first_featured, last_featured, times_featured, last_score) " +
                    "VALUES ('t', 'T', 'G', '2026-10-01', '2026-10-18', 2, 70)",
            )
        }
        helper.runMigrationsAndValidate(3).use { connection ->
            connection.prepare("SELECT mode, tokens_billed FROM calls").use { statement ->
                assertTrue(statement.step())
                assertEquals("keywords", statement.getText(0))
                assertEquals(1440L, statement.getLong(1))
            }
            connection.prepare("SELECT topic_tags, subject FROM digests").use { statement ->
                assertTrue(statement.step())
                assertEquals("[]", statement.getText(0))
                assertEquals("S", statement.getText(1))
            }
            connection.prepare("SELECT COUNT(*) FROM presets").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
            connection.prepare("SELECT COUNT(*) FROM sqlite_master WHERE name = 'curated_topics'").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
        Unit
    }

    @Test
    fun version3To4KeepsTheDigestAndDropsItsStoredMail() = runBlocking {
        helper.createDatabase(3).use { connection ->
            connection.execSQL(
                "INSERT INTO digests (run_id, status, generated_at, subject, html, text, summary, topic_count, digest, model, " +
                    "attention_mode, persona, recipient, tokens_billed, topic_tags) VALUES " +
                    "(1, 'SAVED', 5, 'S', '<p>x</p>', 'x', 's', 1, '{}', 'm', 'BALANCED', 'OFF', NULL, 1440, '[\"Club chess\"]')",
            )
        }
        helper.runMigrationsAndValidate(4).use { connection ->
            connection.prepare("SELECT subject, summary, digest, topic_tags, tokens_billed FROM digests").use { statement ->
                assertTrue(statement.step())
                assertEquals("S", statement.getText(0))
                assertEquals("s", statement.getText(1))
                assertEquals("{}", statement.getText(2))
                assertEquals("[\"Club chess\"]", statement.getText(3))
                assertEquals(1440L, statement.getLong(4))
            }
            connection.prepare("SELECT COUNT(*) FROM pragma_table_info('digests') WHERE name IN ('status', 'html', 'text', 'recipient')").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
        }
        Unit
    }

    @Test
    fun versions5To7MoveTheDigestsTagsIntoTheSavedTopics() = runBlocking {
        helper.createDatabase(5).use { connection ->
            fun digest(at: Int, tags: String) = connection.execSQL(
                "INSERT INTO digests (run_id, generated_at, subject, summary, topic_count, digest, model, attention_mode, persona, " +
                    "tokens_billed, topic_tags, based) VALUES ($at, $at, 'S', 's', 2, '{}', 'm', 'BALANCED', 'OFF', 1440, '$tags', 0)",
            )
            digest(1, "[\"Club chess\", \"Seabed rules\"]")
            digest(2, "[\"club CHESS\", \"Fusion records\", \" \"]")
            digest(3, "[]")
        }
        helper.runMigrationsAndValidate(7).use { connection ->
            // Each tag once (ignoring case), at its newest digest; blanks skipped.
            connection.prepare("SELECT tag, saved_at FROM topics ORDER BY saved_at DESC, id ASC").use { statement ->
                val rows = buildList { while (statement.step()) add(statement.getText(0) to statement.getLong(1)) }
                assertEquals(listOf("club CHESS" to 2L, "Fusion records" to 2L, "Seabed rules" to 1L), rows)
            }
            connection.prepare("SELECT COUNT(*) FROM pragma_table_info('digests') WHERE name = 'topic_tags'").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
            }
            connection.prepare("SELECT COUNT(*) FROM digests").use { statement ->
                assertTrue(statement.step())
                assertEquals(3L, statement.getLong(0))
            }
        }
        Unit
    }

    @Test
    fun version4To5KeepsCallsAndDigestsAndReadsThemAsBasedOff() = runBlocking {
        helper.createDatabase(4).use { connection ->
            connection.execSQL(
                "INSERT INTO digests (run_id, generated_at, subject, summary, topic_count, digest, model, attention_mode, persona, " +
                    "tokens_billed, topic_tags) VALUES (1, 5, 'S', 's', 1, '{}', 'm', 'BALANCED', 'OFF', 1440, '[]')",
            )
            connection.execSQL(
                "INSERT INTO calls (run_id, at, model, template_version, keywords, tags, attention_mode, persona, prompt_chars, " +
                    "latency_ms, tokens_billed, error, valid_topics, selected_topics, issues, mode) VALUES " +
                    "(1, 2, 'm', 2, '[]', '[]', 'balanced', 'off', 900, 7000, 1440, NULL, 3, 2, '{}', 'keywords')",
            )
        }
        helper.runMigrationsAndValidate(5).use { connection ->
            for (table in listOf("digests", "calls")) {
                connection.prepare("SELECT based, tokens_billed FROM $table").use { statement ->
                    assertTrue(statement.step(), table)
                    assertEquals(0L, statement.getLong(0), table)
                    assertEquals(1440L, statement.getLong(1), table)
                }
            }
        }
        Unit
    }
}
