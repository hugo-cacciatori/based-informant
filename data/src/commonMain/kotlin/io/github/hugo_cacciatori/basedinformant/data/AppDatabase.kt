package io.github.hugo_cacciatori.basedinformant.data

import androidx.room3.AutoMigration
import androidx.room3.ColumnInfo
import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
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
        CuratedTopicEntity::class,
    ],
    version = 2,
    // v2 only adds tables (2026-10-20): Room writes that migration; MigrationTest checks it on a filled v1.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun keywords(): KeywordDao

    abstract fun runs(): RunDao

    abstract fun calls(): CallDao

    abstract fun checks(): CheckDao

    abstract fun digests(): DigestDao

    abstract fun curatedTopics(): CuratedTopicDao

    companion object {
        const val FILE_NAME = "based-informant.db"
    }
}

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
