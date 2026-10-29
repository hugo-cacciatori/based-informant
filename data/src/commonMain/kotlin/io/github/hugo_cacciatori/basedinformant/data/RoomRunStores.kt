package io.github.hugo_cacciatori.basedinformant.data

import androidx.room3.withWriteTransaction
import io.github.hugo_cacciatori.basedinformant.core.check.CheckOutcome
import io.github.hugo_cacciatori.basedinformant.core.check.CheckRecord
import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.run.CallLog
import io.github.hugo_cacciatori.basedinformant.core.run.CallRecord
import io.github.hugo_cacciatori.basedinformant.core.run.DigestStore
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryCard
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryStore
import io.github.hugo_cacciatori.basedinformant.core.run.RunError
import io.github.hugo_cacciatori.basedinformant.core.run.RunRecord
import io.github.hugo_cacciatori.basedinformant.core.run.RunSnapshot
import io.github.hugo_cacciatori.basedinformant.core.run.RunState
import io.github.hugo_cacciatori.basedinformant.core.run.RunStore
import io.github.hugo_cacciatori.basedinformant.core.run.StoredDigest
import io.github.hugo_cacciatori.basedinformant.core.settings.Preset
import io.github.hugo_cacciatori.basedinformant.core.settings.PresetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** The runs in Room, their raw answers in [raw] (04-platforms.md section 7). */
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

    override suspend fun saveSearch(runId: Long, answer: String) = withContext(Dispatchers.IO) { raw.saveSearch(runId, answer) }

    override suspend fun search(runId: Long): String? = withContext(Dispatchers.IO) { raw.readSearch(runId) }

    override suspend fun recentCosts(model: String, limit: Int): List<Long> = db.runs().recentCosts(model, limit)

    override suspend fun pruneRaw(before: Long) {
        val ids = db.runs().prunable(before)
        withContext(Dispatchers.IO) { ids.forEach(raw::delete) }
    }

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

class RoomDigestStore(private val db: AppDatabase) : DigestStore, HistoryStore {
    override suspend fun save(digest: StoredDigest): Long = db.digests().insert(
        with(digest) {
            DigestEntity(
                runId = runId, generatedAt = generatedAt, subject = subject,
                summary = summary, topicCount = topicCount, digest = StoredJson.json.encodeToString(Digest.serializer(), this.digest),
                model = model, attentionMode = attentionMode, persona = persona, tokensBilled = tokensBilled,
                topicTags = StoredJson.strings(this.digest.topics.mapNotNull { it.topicTag }.distinct()),
            )
        },
    )

    override suspend fun topicTags(): List<String> {
        val seen = mutableSetOf<String>()
        return db.digests().topicTags().flatMap(StoredJson::stringList).filter { seen.add(it.lowercase()) }
    }

    override suspend fun lastGeneratedAt(): Long? = db.digests().lastGeneratedAt()

    override suspend fun cards(): List<HistoryCard> = db.digests().cards().map {
        HistoryCard(it.id, it.generatedAt, it.topicCount, it.summary)
    }

    override suspend fun delete(id: Long) = db.digests().delete(id)

    override suspend fun get(id: Long): StoredDigest? = db.digests().get(id)?.let {
        StoredDigest(
            runId = it.runId, generatedAt = it.generatedAt, subject = it.subject,
            summary = it.summary, topicCount = it.topicCount,
            digest = StoredJson.json.decodeFromString(Digest.serializer(), it.digest), model = it.model,
            attentionMode = it.attentionMode, persona = it.persona, tokensBilled = it.tokensBilled,
        )
    }
}

/** The call history (01-product.md section 4.5): written here, never shown in the app. */
class RoomCallLog(private val db: AppDatabase) : CallLog {
    override suspend fun record(call: CallRecord) {
        db.calls().insert(
            with(call) {
                CallEntity(
                    runId = runId, at = at, model = model, templateVersion = templateVersion,
                    keywords = StoredJson.strings(keywords), tags = StoredJson.strings(tags), attentionMode = attentionMode,
                    persona = persona, promptChars = promptChars, latencyMillis = latencyMillis,
                    tokensBilled = tokensBilled, error = error?.name, validTopics = validTopics, selectedTopics = selectedTopics,
                    issues = StoredJson.counts(issues.mapKeys { it.key.name }), mode = mode,
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

/** Keyword presets (01-product.md section 3), oldest first. */
class RoomPresetStore(private val db: AppDatabase, private val now: () -> Long) : PresetStore {
    override suspend fun all(): List<Preset> = db.presets().all().map { Preset(it.id, StoredJson.stringList(it.keywords)) }

    override suspend fun add(keywords: List<String>): Long = db.presets().insert(PresetEntity(keywords = StoredJson.strings(keywords), createdAtMillis = now()))

    override suspend fun delete(id: Long) = db.presets().delete(id)
}
