package io.github.hugo_cacciatori.basedinformant.core.fakes

import io.github.hugo_cacciatori.basedinformant.core.check.CheckRecord
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkResult
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkStatus
import io.github.hugo_cacciatori.basedinformant.core.memory.CuratedTopic
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.run.CallLog
import io.github.hugo_cacciatori.basedinformant.core.run.CallRecord
import io.github.hugo_cacciatori.basedinformant.core.run.DigestStore
import io.github.hugo_cacciatori.basedinformant.core.run.MemoryStore
import io.github.hugo_cacciatori.basedinformant.core.run.RunRecord
import io.github.hugo_cacciatori.basedinformant.core.run.RunSnapshot
import io.github.hugo_cacciatori.basedinformant.core.run.RunState
import io.github.hugo_cacciatori.basedinformant.core.run.RunStore
import io.github.hugo_cacciatori.basedinformant.core.run.StoredDigest
import kotlin.test.fail

class FakeRunStore : RunStore {
    val runs = linkedMapOf<Long, RunRecord>()
    val raws = mutableMapOf<Long, String>()
    private var nextId = 1L

    override suspend fun start(snapshot: RunSnapshot, startedAt: Long): Long? {
        if (runs.values.any { it.state == RunState.CALLING || it.state == RunState.RECEIVED }) return null
        val id = nextId++
        runs[id] = RunRecord(id, startedAt, RunState.CALLING, snapshot)
        return id
    }

    override suspend fun update(record: RunRecord) {
        runs[record.id] = record
    }

    override suspend fun get(id: Long) = runs[id]

    override suspend fun unfinished() = runs.values.filter { it.state == RunState.CALLING || it.state == RunState.RECEIVED }

    override suspend fun saveRaw(runId: Long, content: String) {
        raws[runId] = content
    }

    override suspend fun raw(runId: Long) = raws[runId]

    override suspend fun recentCosts(model: String, limit: Int) =
        runs.values.reversed().filter { it.snapshot.prompt.model == model }.mapNotNull { it.tokensBilled }.take(limit)
}

class FakeDigestStore : DigestStore {
    val saved = mutableListOf<StoredDigest>()

    override suspend fun save(digest: StoredDigest): Long {
        saved += digest
        return saved.size.toLong()
    }
}

class FakeMemoryStore(var entries: List<CuratedTopic> = emptyList()) : MemoryStore {
    override suspend fun load() = entries

    override suspend fun save(entries: List<CuratedTopic>) {
        this.entries = entries
    }
}

class FakeCallLog : CallLog {
    val calls = mutableListOf<CallRecord>()

    val checks = mutableListOf<CheckRecord>()

    override suspend fun record(call: CallRecord) {
        calls += call
    }

    override suspend fun recordCheck(check: CheckRecord) {
        checks += check
    }
}

/** Answers the paid call with [answer] once; a second paid call fails the test (nothing is ever re-sent). */
class OneAnswerRfab(private val answer: RfabResult<ChatResponse>) : RfabClient {
    var chats = 0

    override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> {
        if (++chats > 1) fail("a run sent a second paid call")
        return answer
    }

    override suspend fun balance(): RfabResult<Balance> = fail("no balance call in a run")

    override suspend fun models(): RfabResult<ModelCatalog> = fail("no models call in a run")
}

/** Every link alive, with no page date: the fixtures' URLs are made up. */
val allAlive = LinkChecker { LinkResult(LinkStatus.ALIVE) }
