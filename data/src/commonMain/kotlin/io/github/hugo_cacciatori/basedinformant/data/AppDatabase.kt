package io.github.hugo_cacciatori.basedinformant.data

import androidx.room3.AutoMigration
import androidx.room3.ColumnInfo
import androidx.room3.ConstructedBy
import androidx.room3.DeleteColumn
import androidx.room3.DeleteTable
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The app's database (04-platforms.md section 7). Every schema change bumps [version] with a migration and a
 * migration test, never a destructive fallback: History matters.
 */
@Database(
    entities = [
        KeywordEntity::class, RunEntity::class, CallEntity::class, CheckEntity::class, DigestEntity::class,
        PresetEntity::class, TopicEntity::class,
    ],
    version = 7,
    // v2 only adds tables (2026-10-20); v3 (2026-10-29, template v2) drops the curated memory and two unused call
    // columns, and adds presets, the digests' topic tags and the call mode; v4 (2026-10-29, Share) drops the digests'
    // stored mail and email state; v5 (2026-10-29, Based) adds the Based flag to calls and digests; v6 (2026-10-30,
    // stage 10) adds the saved topics, filled from the digests' tags; v7 drops those per-digest tags. Room writes them;
    // SchemaTest checks them.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = Migration2To3::class),
        AutoMigration(from = 3, to = 4, spec = Migration3To4::class),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = Migration5To6::class),
        AutoMigration(from = 6, to = 7, spec = Migration6To7::class),
    ],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun keywords(): KeywordDao

    abstract fun runs(): RunDao

    abstract fun calls(): CallDao

    abstract fun checks(): CheckDao

    abstract fun digests(): DigestDao

    abstract fun presets(): PresetDao

    abstract fun topics(): TopicDao

    companion object {
        const val FILE_NAME = "based-informant.db"
    }
}

/** What Room cannot infer for v3: what goes. Data in the dropped table and columns has no use since template v2. */
@DeleteTable(tableName = "curated_topics")
@DeleteColumn(tableName = "calls", columnName = "wide_window")
@DeleteColumn(tableName = "calls", columnName = "repeats")
class Migration2To3 : AutoMigrationSpec

/** v4: the app no longer sends mail; Share builds the mail from the digest JSON (01-product.md section 8). */
@DeleteColumn(tableName = "digests", columnName = "status")
@DeleteColumn(tableName = "digests", columnName = "html")
@DeleteColumn(tableName = "digests", columnName = "text")
@DeleteColumn(tableName = "digests", columnName = "recipient")
class Migration3To4 : AutoMigrationSpec

/**
 * v6: the saved topics start with every tag of the stored digests, a tag once (ignoring case), seen at its newest
 * digest. Two steps (v6, v7) because the tags must be copied before their column goes.
 */
class Migration5To6 : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL(
            "INSERT OR IGNORE INTO topics (tag, saved_at) " +
                "SELECT TRIM(j.value), d.generated_at FROM digests d, json_each(d.topic_tags) j " +
                "WHERE TRIM(j.value) <> '' ORDER BY d.generated_at DESC, d.id DESC",
        )
    }
}

/** v7: the saved topics replace the digests' own tag lists (one source of truth). */
@DeleteColumn(tableName = "digests", columnName = "topic_tags")
class Migration6To7 : AutoMigrationSpec

// The Room compiler generates the `actual` implementations.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Each platform makes the builder with its file path; this finishes it the same way everywhere. */
fun buildDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase =
    builder.setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()

/**
 * One keyword. The unique index is a backstop to the app's own rule (ignoring case, `KeywordRules`):
 * SQLite's NOCASE folds ASCII only, so it never refuses what the app allows.
 */
@Entity(tableName = "keywords", indices = [Index(value = ["text"], unique = true)])
data class KeywordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val text: String,
    val position: Int,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

@Dao
interface KeywordDao {
    @Query("SELECT * FROM keywords ORDER BY position")
    suspend fun all(): List<KeywordEntity>

    @Query("DELETE FROM keywords")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(keywords: List<KeywordEntity>)
}
