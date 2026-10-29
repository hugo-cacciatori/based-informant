package io.github.hugo_cacciatori.basedinformant.core.fakes

import io.github.hugo_cacciatori.basedinformant.core.check.CheckRecord
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkResult
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkStatus
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.prompt.TemplateConfig
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelRate
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.run.CallLog
import io.github.hugo_cacciatori.basedinformant.core.run.CallRecord
import io.github.hugo_cacciatori.basedinformant.core.run.DigestStore
import io.github.hugo_cacciatori.basedinformant.core.run.RunRecord
import io.github.hugo_cacciatori.basedinformant.core.run.RunSnapshot
import io.github.hugo_cacciatori.basedinformant.core.run.RunState
import io.github.hugo_cacciatori.basedinformant.core.run.RunStore
import io.github.hugo_cacciatori.basedinformant.core.run.StoredDigest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.fail

class FakeRunStore : RunStore {
    val runs = linkedMapOf<Long, RunRecord>()
    val raws = mutableMapOf<Long, String>()
    val searches = mutableMapOf<Long, String>()
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

    override suspend fun saveSearch(runId: Long, answer: String) {
        searches[runId] = answer
    }

    override suspend fun search(runId: Long) = searches[runId]

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

/** rfab's response body around [content], as the client receives it. */
fun responseBody(content: String?, tokens: Long = 0, model: String = TemplateConfig.DEFAULT_MODEL): String = buildJsonObject {
    put("success", JsonPrimitive(true))
    put("model", JsonPrimitive(model))
    content?.let { put("content", JsonPrimitive(it)) }
    put("tokensBilled", JsonPrimitive(tokens))
}.toString()

fun okResponse(content: String?, tokens: Long = 0): RfabResult<ChatResponse> {
    val raw = responseBody(content, tokens)
    return RfabResult.Ok(ChatResponse(success = true, content = content, tokensBilled = tokens), raw)
}

/**
 * The paid search answers [paid] once, and a second paid call fails the test (nothing is ever re-sent). Free models
 * answer through [free], by model and request: null means the model failed, as at its usage limit. The models list
 * shows every model at 0x except the paid one.
 */
class ScriptedRfab(
    private val paid: RfabResult<ChatResponse>,
    private val free: (model: String, request: ChatRequest) -> String? = { _, _ -> null },
) : RfabClient {
    var paidCalls = 0
    val freeCalls = mutableListOf<String>()

    override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> {
        if (request.model == TemplateConfig.DEFAULT_MODEL) {
            if (++paidCalls > 1) fail("a run sent a second paid call")
            return paid
        }
        freeCalls += request.model
        return free(request.model, request)?.let { okResponse(it) } ?: RfabResult.Error(RfabError.UNAVAILABLE, 429)
    }

    override suspend fun balance(): RfabResult<Balance> = fail("no balance call in a run")

    override suspend fun models(): RfabResult<ModelCatalog> = RfabResult.Ok(
        ModelCatalog(
            success = true,
            models = listOf(ModelRate(TemplateConfig.DEFAULT_MODEL, 0.36)) +
                listOf(
                    "openrouter:minimax/minimax-m3", "openrouter:moonshotai/kimi-k2.6", "openrouter:z-ai/glm-4.7-flash",
                    "openrouter:deepseek/deepseek-v4-flash", "openrouter:qwen/qwen3.8-27b",
                ).map { ModelRate(it, 0.0) },
        ),
        "{}",
    )
}

/** Every link alive, with no page date or text: the fixtures' URLs are made up. */
val allAlive = LinkChecker { LinkResult(LinkStatus.ALIVE) }
