package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.DigestPipeline
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.ValidationContext
import io.github.hugo_cacciatori.basedinformant.core.limits.KeywordRules
import io.github.hugo_cacciatori.basedinformant.core.memory.CuratedMemory
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptBuilder
import io.github.hugo_cacciatori.basedinformant.core.prompt.TemplateConfig
import io.github.hugo_cacciatori.basedinformant.core.render.DigestRenderer
import io.github.hugo_cacciatori.basedinformant.core.render.RenderMeta
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import kotlinx.serialization.json.Json

/**
 * One digest run (product.md section 4, robustness rules of 4.1): one paid call, its raw answer saved before any
 * parsing, then the free checks, and a stored digest when at least one topic survives. Never re-sends a call: a
 * new attempt always needs a new confirmation. Email (stage 5) starts from a digest stored as `GENERATED`.
 */
class DigestRun(
    private val runs: RunStore,
    private val digests: DigestStore,
    private val memory: MemoryStore,
    private val calls: CallLog,
    private val client: RfabClient,
    private val linkChecker: LinkChecker,
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    /** Milliseconds since the epoch. */
    private val now: () -> Long,
) {
    /** The run the owner just confirmed. */
    suspend fun execute(snapshot: RunSnapshot): RunOutcome {
        // Built before the run exists: an input the builder refuses never reaches rfab.
        val request = (promptBuilder.build(snapshot.prompt) as? PromptBuilder.Result.Built)?.request
            ?: return RunOutcome.NotBuilt
        val startedAt = now()
        val runId = runs.start(snapshot, startedAt) ?: return RunOutcome.Busy
        val record = RunRecord(runId, startedAt, RunState.CALLING, snapshot)
        val result = client.chat(request)
        val latency = now() - startedAt

        val (outcome, processed) = when (result) {
            is RfabResult.Ok -> {
                runs.saveRaw(runId, result.raw)
                val received = record.copy(state = RunState.RECEIVED, tokensBilled = result.value.tokensBilled)
                runs.update(received)
                process(received, result.raw)
            }
            is RfabResult.Error -> failed(record, result) to null
        }
        calls.record(callRecord(record, request, latency, outcome, processed))
        return outcome
    }

    /**
     * At app start (product.md section 4.1): a run left `CALLING` was interrupted and is never re-sent; a run left
     * `RECEIVED` is processed from its saved answer, for free.
     */
    suspend fun recover(): List<RunOutcome> = runs.unfinished().map { record ->
        when (record.state) {
            RunState.RECEIVED -> runs.raw(record.id)?.let { process(record, it).first }
                ?: finish(record, RunState.FAILED, RunError.UNREADABLE).let { RunOutcome.Unreadable(record.id, record.tokensBilled) }
            else -> {
                finish(record, RunState.INTERRUPTED, RunError.INTERRUPTED)
                RunOutcome.Interrupted(record.id)
            }
        }
    }

    /** **Read again** on an unreadable run, after an update that fixes the reading: free. Null when not possible. */
    suspend fun readAgain(runId: Long): RunOutcome? {
        val record = runs.get(runId)?.takeIf { it.error == RunError.UNREADABLE } ?: return null
        val raw = runs.raw(runId) ?: return null
        return process(record, raw).first
    }

    private suspend fun process(record: RunRecord, raw: String): Pair<RunOutcome, DigestPipeline.Result.Processed?> {
        val snapshot = record.snapshot
        val content = contentOf(raw)
            ?: return unreadable(record) to null
        val context = ValidationContext(snapshot.prompt.keywords.map(KeywordRules::normalize), snapshot.prompt.window())
        val processed = when (val result = DigestPipeline.process(content, context, linkChecker)) {
            is DigestPipeline.Result.Unreadable -> return unreadable(record) to null
            is DigestPipeline.Result.Processed -> result
        }
        val digest = processed.digest
        if (digest.topics.isEmpty()) {
            finish(record, RunState.FAILED, RunError.EMPTY)
            return RunOutcome.Empty(record.id, record.tokensBilled, canWiden = !snapshot.prompt.wideWindow) to processed
        }
        val digestId = store(record, digest)
        finish(record, RunState.DONE, error = null, digestId = digestId)
        return RunOutcome.Stored(record.id, digestId, digest.topics.size, record.tokensBilled) to processed
    }

    private suspend fun store(record: RunRecord, digest: Digest): Long {
        val snapshot = record.snapshot
        val rendered = DigestRenderer.render(digest, RenderMeta(snapshot.personaDisplayName, snapshot.prompt.model))
        val id = digests.save(
            StoredDigest(
                runId = record.id,
                status = if (snapshot.emailOn) DigestStatus.GENERATED else DigestStatus.SAVED,
                generatedAt = now(),
                subject = rendered.subject,
                html = rendered.html,
                text = rendered.text,
                summary = DigestSummary.of(digest),
                topicCount = digest.topics.size,
                digest = digest,
                model = snapshot.prompt.model,
                attentionMode = snapshot.prompt.attentionMode.name.lowercase(),
                persona = snapshot.prompt.persona.name.lowercase(),
                recipient = snapshot.recipient.takeIf { snapshot.emailOn },
                tokensBilled = record.tokensBilled,
            ),
        )
        // Email off: the topics count as delivered now. Email on: on delivery (stage 5).
        if (!snapshot.emailOn) memory.save(CuratedMemory.record(memory.load(), digest.topics, snapshot.prompt.today))
        return id
    }

    private suspend fun unreadable(record: RunRecord): RunOutcome {
        finish(record, RunState.FAILED, RunError.UNREADABLE)
        return RunOutcome.Unreadable(record.id, record.tokensBilled)
    }

    private suspend fun failed(record: RunRecord, error: RfabResult.Error): RunOutcome {
        val runId = record.id
        return when (error.kind) {
            RfabError.FAILED_BILLED -> {
                finish(record.copy(tokensBilled = error.tokensBilled), RunState.FAILED, RunError.FAILED_BILLED)
                RunOutcome.FailedBilled(runId, error.tokensBilled)
            }
            RfabError.FAILED_MAYBE_BILLED, RfabError.NETWORK -> {
                finish(record, RunState.INTERRUPTED, RunError.INTERRUPTED)
                RunOutcome.Interrupted(runId)
            }
            // A 2xx with an unexpected body: billed, kept like any unreadable answer.
            RfabError.BAD_RESPONSE -> {
                error.raw?.let { runs.saveRaw(runId, it) }
                unreadable(record)
            }
            else -> {
                val kind = when (error.kind) {
                    RfabError.KEY_REFUSED -> RunError.KEY_REFUSED
                    RfabError.INSUFFICIENT_TOKENS -> RunError.INSUFFICIENT_TOKENS
                    RfabError.UNAVAILABLE -> RunError.UNAVAILABLE
                    RfabError.FORBIDDEN -> RunError.FORBIDDEN
                    else -> RunError.UNEXPECTED_STATUS
                }
                finish(record, RunState.FAILED, kind)
                RunOutcome.Refused(runId, kind)
            }
        }
    }

    private suspend fun finish(record: RunRecord, state: RunState, error: RunError?, digestId: Long? = null) =
        runs.update(record.copy(state = state, error = error, digestId = digestId, finishedAt = now()))

    private fun callRecord(
        record: RunRecord,
        request: ChatRequest,
        latency: Long,
        outcome: RunOutcome,
        processed: DigestPipeline.Result.Processed?,
    ): CallRecord {
        val prompt = record.snapshot.prompt
        val remembered = prompt.memory.mapTo(mutableSetOf()) { it.id }
        val selected = processed?.digest?.topics.orEmpty()
        return CallRecord(
            runId = record.id,
            at = record.startedAt,
            model = request.model,
            templateVersion = TemplateConfig.TEMPLATE_VERSION,
            keywords = prompt.keywords,
            tags = prompt.interestTags,
            attentionMode = prompt.attentionMode.name.lowercase(),
            persona = prompt.persona.name.lowercase(),
            wideWindow = prompt.wideWindow,
            promptChars = request.systemPrompt.length + request.messages.sumOf { it.content.length },
            latencyMillis = latency,
            tokensBilled = outcome.tokensBilled(),
            error = outcome.error(),
            validTopics = processed?.validTopicCount ?: 0,
            selectedTopics = selected.size,
            repeats = selected.count { it.continues != null || it.id in remembered },
            issues = processed?.issues.orEmpty().groupingBy { it.kind }.eachCount(),
        )
    }

    private fun RunOutcome.tokensBilled(): Long? = when (this) {
        is RunOutcome.Stored -> tokensBilled
        is RunOutcome.Empty -> tokensBilled
        is RunOutcome.Unreadable -> tokensBilled
        is RunOutcome.FailedBilled -> tokensBilled
        else -> null
    }

    private fun RunOutcome.error(): RunError? = when (this) {
        is RunOutcome.Stored -> null
        is RunOutcome.Empty -> RunError.EMPTY
        is RunOutcome.Unreadable -> RunError.UNREADABLE
        is RunOutcome.Refused -> error
        is RunOutcome.FailedBilled -> RunError.FAILED_BILLED
        is RunOutcome.Interrupted -> RunError.INTERRUPTED
        RunOutcome.Busy, RunOutcome.NotBuilt -> null
    }

    /** The model's text inside the saved response body, or null when the body is not rfab's documented shape. */
    private fun contentOf(raw: String): String? = try {
        json.decodeFromString(ChatResponse.serializer(), raw).content
    } catch (_: Exception) {
        null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** The History card's summary (product.md section 5, [PROPOSED]): the top 3 titles by score, then "+N more". */
object DigestSummary {
    const val SHOWN = 3
    const val TITLE_MAX_CHARS = 60

    fun of(digest: Digest): String {
        val titles = digest.topics.sortedByDescending { it.score }.map { ellipsize(it.title) }
        val shown = titles.take(SHOWN).joinToString(" · ")
        val more = titles.size - SHOWN
        return if (more > 0) "$shown +$more more" else shown
    }

    private fun ellipsize(title: String): String =
        if (title.length <= TITLE_MAX_CHARS) title else title.take(TITLE_MAX_CHARS - 1).trimEnd() + "…"
}
