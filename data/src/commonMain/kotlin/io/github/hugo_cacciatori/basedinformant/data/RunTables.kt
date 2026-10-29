package io.github.hugo_cacciatori.basedinformant.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert

// Schema v4 (04-platforms.md section 7). Enums are stored by name; lists, maps, the snapshot and the digest as JSON text
// (StoredJson.kt), since they are read whole and never queried inside.

@Entity(tableName = "runs", indices = [Index(value = ["state"]), Index(value = ["model"])])
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    val state: String,
    val model: String,
    val snapshot: String,
    @ColumnInfo(name = "tokens_billed") val tokensBilled: Long?,
    val error: String?,
    @ColumnInfo(name = "digest_id") val digestId: Long?,
)

@Dao
interface RunDao {
    @Insert
    suspend fun insert(run: RunEntity): Long

    @Update
    suspend fun update(run: RunEntity)

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun get(id: Long): RunEntity?

    @Query("SELECT * FROM runs WHERE state IN ('CALLING', 'RECEIVED') ORDER BY id")
    suspend fun unfinished(): List<RunEntity>

    @Query("SELECT tokens_billed FROM runs WHERE model = :model AND tokens_billed IS NOT NULL ORDER BY id DESC LIMIT :limit")
    suspend fun recentCosts(model: String, limit: Int): List<Long>

    /** Finished runs started before [before] whose raw files may go ([RawRetention]). */
    @Query(
        "SELECT id FROM runs WHERE started_at < :before AND state IN ('DONE', 'FAILED', 'INTERRUPTED') " +
            "AND (error IS NULL OR error NOT IN ('UNREADABLE', 'WRITE_FAILED'))",
    )
    suspend fun prunable(before: Long): List<Long>
}

@Entity(tableName = "calls", indices = [Index(value = ["run_id"])])
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "run_id") val runId: Long,
    val at: Long,
    val model: String,
    @ColumnInfo(name = "template_version") val templateVersion: Int,
    val keywords: String,
    val tags: String,
    @ColumnInfo(name = "attention_mode") val attentionMode: String,
    val persona: String,
    @ColumnInfo(name = "prompt_chars") val promptChars: Int,
    @ColumnInfo(name = "latency_ms") val latencyMillis: Long,
    @ColumnInfo(name = "tokens_billed") val tokensBilled: Long?,
    val error: String?,
    @ColumnInfo(name = "valid_topics") val validTopics: Int,
    @ColumnInfo(name = "selected_topics") val selectedTopics: Int,
    val issues: String,
    /** "keywords" or "topics" (schema v3; rows from v2 read "keywords"). */
    @ColumnInfo(defaultValue = "'keywords'") val mode: String,
    /** Right-wing sources only (schema v5; rows from v4 read off). */
    @ColumnInfo(defaultValue = "0") val based: Boolean,
)

@Dao
interface CallDao {
    @Insert
    suspend fun insert(call: CallEntity): Long

    @Query("SELECT * FROM calls ORDER BY id")
    suspend fun all(): List<CallEntity>
}

@Entity(tableName = "checks")
data class CheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val entries: Int,
    val outcome: String,
    @ColumnInfo(name = "models_list_read") val modelsListRead: Boolean,
    val attempts: String,
    val refusals: String,
)

@Dao
interface CheckDao {
    @Insert
    suspend fun insert(check: CheckEntity): Long

    @Query("SELECT * FROM checks ORDER BY id")
    suspend fun all(): List<CheckEntity>
}

@Entity(tableName = "digests", indices = [Index(value = ["generated_at"]), Index(value = ["run_id"])])
data class DigestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    val subject: String,
    val summary: String,
    @ColumnInfo(name = "topic_count") val topicCount: Int,
    val digest: String,
    val model: String,
    @ColumnInfo(name = "attention_mode") val attentionMode: String,
    val persona: String,
    @ColumnInfo(name = "tokens_billed") val tokensBilled: Long?,
    /** Right-wing sources only (schema v5; rows from v4 read off). */
    @ColumnInfo(defaultValue = "0") val based: Boolean,
)

@Dao
interface DigestDao {
    @Insert
    suspend fun insert(digest: DigestEntity): Long

    @Query("SELECT MAX(generated_at) FROM digests")
    suspend fun lastGeneratedAt(): Long?

    @Query("SELECT * FROM digests WHERE id = :id")
    suspend fun get(id: Long): DigestEntity?

    /** The History list: never the HTML (01-product.md section 5). */
    @Query("SELECT id, generated_at, topic_count, summary FROM digests ORDER BY generated_at DESC, id DESC")
    suspend fun cards(): List<DigestCardRow>

    @Query("DELETE FROM digests WHERE id = :id")
    suspend fun delete(id: Long)
}

data class DigestCardRow(
    val id: Long,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    @ColumnInfo(name = "topic_count") val topicCount: Int,
    val summary: String,
)

/** Keyword presets (01-product.md section 3, schema v3): the 5 keywords as a JSON list, oldest first. */
@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keywords: String,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY id")
    suspend fun all(): List<PresetEntity>

    @Insert
    suspend fun insert(preset: PresetEntity): Long

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * A saved topic (01-product.md section 3, schema v6): every stored digest's topic tags, kept until the owner deletes
 * them. The unique index is a backstop to the app's own rule (ignoring case); [savedAt] is when a digest last had it.
 */
@Entity(tableName = "topics", indices = [Index(value = ["tag"], unique = true)])
data class TopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val tag: String,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)

@Dao
interface TopicDao {
    @Query("SELECT * FROM topics ORDER BY saved_at DESC, tag ASC")
    suspend fun all(): List<TopicEntity>

    @Upsert
    suspend fun upsert(topic: TopicEntity)

    @Query("DELETE FROM topics WHERE id = :id")
    suspend fun delete(id: Long)
}
