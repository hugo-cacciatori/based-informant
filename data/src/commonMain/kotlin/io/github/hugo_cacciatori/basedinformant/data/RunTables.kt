package io.github.hugo_cacciatori.basedinformant.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Update

// Schema v2 (platforms.md section 7). Enums are stored by name; lists, maps, the snapshot and the digest as JSON text
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
    @ColumnInfo(name = "wide_window") val wideWindow: Boolean,
    @ColumnInfo(name = "prompt_chars") val promptChars: Int,
    @ColumnInfo(name = "latency_ms") val latencyMillis: Long,
    @ColumnInfo(name = "tokens_billed") val tokensBilled: Long?,
    val error: String?,
    @ColumnInfo(name = "valid_topics") val validTopics: Int,
    @ColumnInfo(name = "selected_topics") val selectedTopics: Int,
    val repeats: Int,
    val issues: String,
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
    val status: String,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    val subject: String,
    val html: String,
    val text: String,
    val summary: String,
    @ColumnInfo(name = "topic_count") val topicCount: Int,
    val digest: String,
    val model: String,
    @ColumnInfo(name = "attention_mode") val attentionMode: String,
    val persona: String,
    val recipient: String?,
    @ColumnInfo(name = "tokens_billed") val tokensBilled: Long?,
)

@Dao
interface DigestDao {
    @Insert
    suspend fun insert(digest: DigestEntity): Long

    @Query("SELECT * FROM digests WHERE id = :id")
    suspend fun get(id: Long): DigestEntity?
}

@Entity(tableName = "curated_topics")
data class CuratedTopicEntity(
    @PrimaryKey val id: String,
    val title: String,
    val gist: String,
    @ColumnInfo(name = "first_featured") val firstFeatured: String,
    @ColumnInfo(name = "last_featured") val lastFeatured: String,
    @ColumnInfo(name = "times_featured") val timesFeatured: Int,
    @ColumnInfo(name = "last_score") val lastScore: Int,
)

@Dao
interface CuratedTopicDao {
    @Query("SELECT * FROM curated_topics ORDER BY last_featured DESC, id")
    suspend fun all(): List<CuratedTopicEntity>

    @Query("DELETE FROM curated_topics")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(topics: List<CuratedTopicEntity>)
}
