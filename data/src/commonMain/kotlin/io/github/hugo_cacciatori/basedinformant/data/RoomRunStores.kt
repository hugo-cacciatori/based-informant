package io.github.hugo_cacciatori.basedinformant.data

import androidx.room3.withWriteTransaction
import io.github.hugo_cacciatori.basedinformant.core.check.CheckOutcome
import io.github.hugo_cacciatori.basedinformant.core.check.CheckRecord
import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.memory.CuratedTopic
import io.github.hugo_cacciatori.basedinformant.core.run.CallLog
import io.github.hugo_cacciatori.basedinformant.core.run.CallRecord
import io.github.hugo_cacciatori.basedinformant.core.run.DigestStatus
import io.github.hugo_cacciatori.basedinformant.core.run.DigestStore
import io.github.hugo_cacciatori.basedinformant.core.run.MemoryStore
import io.github.hugo_cacciatori.basedinformant.core.run.RunError
import io.github.hugo_cacciatori.basedinformant.core.run.RunRecord
import io.github.hugo_cacciatori.basedinformant.core.run.RunSnapshot
import io.github.hugo_cacciatori.basedinformant.core.run.RunState
import io.github.hugo_cacciatori.basedinformant.core.run.RunStore
import io.github.hugo_cacciatori.basedinformant.core.run.StoredDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** The runs in Room, their raw answers in [raw] (platforms.md section 7). */
class RoomRunStore(private val db: AppDatabase, private val raw: RawFiles) : RunStore {
    override suspend fun start(snapshot: RunSnapshot, startedAt: Long): Long? = db.withWriteTransaction {
        if (db.runs().unfinished().isNotEmpty()) {
            null
        } else {
            db.runs().insert(
                RunEntity(
                    startedAt = startedAt, finishedAt = null, state = RunState.CALLING.name, model = snapshot.prompt.model,
                    snapshot = StoredJson.encode(snapshot), tokensBilled = null, error = null, digestId = null,
                ),
            )
        }
    }

    override suspend fun update(record: RunRecord) = db.runs().update(
        RunEntity(
            id = record.id, startedAt = record.startedAt, finishedAt = record.finishedAt, state = record.state.name,
            model = record.snapshot.prompt.model, snapshot = StoredJson.encode(record.snapshot),
            tokensBilled = record.tokensBilled, error = record.error?.name, digestId = record.digestId,
        ),
    )

    override suspend fun get(id: Long): RunRecord? = db.runs().get(id)?.toRecord()

    override suspend fun unfinished(): List<RunRecord> = db.runs().unfinished().map { it.toRecord() }

    override suspend fun saveRaw(runId: Long, content: String) = withContext(Dispatchers.IO) { raw.save(runId, content) }

    override suspend fun raw(runId: Long): String? = withContext(Dispatchers.IO) { raw.read(runId) }

    override suspend fun recentCosts(model: String, limit: Int): List<Long> = db.runs().recentCosts(model, limit)

    private fun RunEntity.toRecord() = RunRecord(
        id = id,
        startedAt = startedAt,
        state = RunState.valueOf(state),
        snapshot = StoredJson.decodeSnapshot(snapshot),
        finishedAt = finishedAt,
        tokensBilled = tokensBilled,
        error = error?.let(RunError::valueOf),
        digestId = digestId,
    )
}

class RoomDigestStore(private val db: AppDatabase) : DigestStore {
    override suspend fun save(digest: StoredDigest): Long = db.digests().insert(
        with(digest) {
            DigestEntity(
                runId = runId, status = status.name, generatedAt = generatedAt, subject = subject, html = html, text = text,
                summary = summary, topicCount = topicCount, digest = StoredJson.json.encodeToString(Digest.serializer(), this.digest),
                model = model, attentionMode = attentionMode, persona = persona, recipient = recipient, tokensBilled = tokensBilled,
            )
        },
    )

    suspend fun get(id: Long): StoredDigest? = db.digests().get(id)?.let {
        StoredDigest(
            runId = it.runId, status = DigestStatus.valueOf(it.status), generatedAt = it.generatedAt, subject = it.subject,
            html = it.html, text = it.text, summary = it.summary, topicCount = it.topicCount,
            digest = StoredJson.json.decodeFromString(Digest.serializer(), it.digest), model = it.model,
            attentionMode = it.attentionMode, persona = it.persona, recipient = it.recipient, tokensBilled = it.tokensBilled,
        )
    }
}

/** The curated memory: a save replaces every entry in one transaction. */
class RoomMemoryStore(private val db: AppDatabase) : MemoryStore {
    override suspend fun load(): List<CuratedTopic> = db.curatedTopics().all().map {
        CuratedTopic(
            it.id, it.title, it.gist, LocalDate.parse(it.firstFeatured), LocalDate.parse(it.lastFeatured), it.timesFeatured, it.lastScore,
        )
    }

    override suspend fun save(entries: List<CuratedTopic>) = db.withWriteTransaction {
        db.curatedTopics().deleteAll()
        db.curatedTopics().insertAll(
            entries.map {
                CuratedTopicEntity(
                    it.id, it.title, it.gist, it.firstFeatured.toString(), it.lastFeatured.toString(), it.timesFeatured, it.lastScore,
                )
            },
        )
    }
}

/** The call history (product.md section 4.5): written here, never shown in the app. */
class RoomCallLog(private val db: AppDatabase) : CallLog {
    override suspend fun record(call: CallRecord) {
        db.calls().insert(
            with(call) {
                CallEntity(
                    runId = runId, at = at, model = model, templateVersion = templateVersion,
                    keywords = StoredJson.strings(keywords), tags = StoredJson.strings(tags), attentionMode = attentionMode,
                    persona = persona, wideWindow = wideWindow, promptChars = promptChars, latencyMillis = latencyMillis,
                    tokensBilled = tokensBilled, error = error?.name, validTopics = validTopics, selectedTopics = selectedTopics,
                    repeats = repeats, issues = StoredJson.counts(issues.mapKeys { it.key.name }),
                )
            },
        )
    }

    override suspend fun recordCheck(check: CheckRecord) {
        val outcome = check.outcome
        db.checks().insert(
            CheckEntity(
                at = check.at,
                entries = check.entries,
                outcome = when (outcome) {
                    is CheckOutcome.Passed -> "PASSED"
                    is CheckOutcome.Refused -> "REFUSED"
                    is CheckOutcome.NoAnswer -> "NO_ANSWER"
                },
                modelsListRead = (outcome as? CheckOutcome.NoAnswer)?.modelsListRead ?: true,
                attempts = StoredJson.attempts(outcome),
                refusals = StoredJson.refusals(outcome),
            ),
        )
    }
}
