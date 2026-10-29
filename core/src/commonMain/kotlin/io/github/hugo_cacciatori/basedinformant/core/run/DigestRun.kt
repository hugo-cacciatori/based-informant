package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.check.FreeModels
import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.Selection
import io.github.hugo_cacciatori.basedinformant.core.digest.ValidationContext
import io.github.hugo_cacciatori.basedinformant.core.limits.KeywordRules
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.prompt.DigestMode
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.prompt.TemplateConfig
import io.github.hugo_cacciatori.basedinformant.core.render.DigestViews
import io.github.hugo_cacciatori.basedinformant.core.render.RenderMeta
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.search.Repair
import io.github.hugo_cacciatori.basedinformant.core.search.SearchAnswer
import io.github.hugo_cacciatori.basedinformant.core.search.SearchCheck
import io.github.hugo_cacciatori.basedinformant.core.search.SearchLinks
import io.github.hugo_cacciatori.basedinformant.core.search.SearchParser
import io.github.hugo_cacciatori.basedinformant.core.search.SearchRequest
import io.github.hugo_cacciatori.basedinformant.core.writer.FactCheck
import io.github.hugo_cacciatori.basedinformant.core.writer.Writer
import kotlinx.serialization.json.Json

/**
 * One digest run (01-product.md section 4, robustness rules of 4.1; 02-ai-call.md section 12): one paid search, its raw
 * answer saved before any parsing, then everything else for free: the repair of an unreadable answer, the source and
 * link checks with the pages' text, the free writer, the copied-quote removal, the fact check, selection, and a stored
 * digest when at least one topic survives. Never re-sends the paid call: a new search always needs a new confirmation.
 * [client] is used for the free models too and must not retry (`KtorRfabClient(maxAttempts = 1)`).
 */
class DigestRun(
    private val runs: RunStore,
    private val digests: DigestStore,
    private val calls: CallLog,
    private val client: RfabClient,
    private val linkChecker: LinkChecker,
    /** Milliseconds since the epoch. */
    private val now: () -> Long,
) {
    /** The run the owner just confirmed. */
    suspend fun execute(snapshot: RunSnapshot): RunOutcome {
        // Built before the run exists: an input over the limits never reaches rfab.
        val request = (SearchRequest.build(snapshot.prompt) as? SearchRequest.Result.Built)?.request ?: return RunOutcome.NotBuilt
        val startedAt = now()
        val runId = runs.start(snapshot, startedAt) ?: return RunOutcome.Busy
        val record = RunRecord(runId, startedAt, RunState.CALLING, snapshot)
        val result = client.chat(request)
        val latency = now() - startedAt

        val (outcome, report) = when (result) {
            is RfabResult.Ok -> {
                runs.saveRaw(runId, result.raw)
                val received = record.copy(state = RunState.RECEIVED, tokensBilled = result.value.tokensBilled)
                runs.update(received)
                process(received, result.raw)
            }
            is RfabResult.Error -> failed(record, result) to Report()
        }
        calls.record(callRecord(record, request, latency, outcome, report))
        return outcome
    }

    /**
     * At app start (01-product.md section 4.1): a run left `CALLING` was interrupted and is never re-sent; a run left
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

    /** **Write again** on a run whose search no free model wrote (02-ai-call.md section 12): free. Null when not possible. */
    suspend fun writeAgain(runId: Long): RunOutcome? {
        val record = runs.get(runId)?.takeIf { it.error == RunError.WRITE_FAILED } ?: return null
        val answer = runs.search(runId)?.let(SearchAnswer::decode) ?: return null
        val catalog = (client.models() as? RfabResult.Ok)?.value ?: return notWritten(record)
        return write(record, answer, catalog, Report()).first
    }

    /** What the free stages found, for the call history. */
    private data class Report(val issues: List<Issue> = emptyList(), val validTopics: Int = 0, val selectedTopics: Int = 0)

    private suspend fun process(record: RunRecord, raw: String): Pair<RunOutcome, Report> {
        val snapshot = record.snapshot
        val content = contentOf(raw) ?: return unreadable(record) to Report()
        val catalog = (client.models() as? RfabResult.Ok)?.value
        val issues = mutableListOf<Issue>()
        val parsed = SearchParser.parse(content)
            ?: catalog?.let { repair(it, content) }?.also { issues += Issue(Issue.Kind.ANSWER_REPAIRED) }
            ?: return unreadable(record) to Report(issues)
        val context = ValidationContext(snapshot.prompt.keywords.map(KeywordRules::normalize), snapshot.prompt.today)
        val (checked, checkIssues) = SearchCheck.apply(parsed, context)
        val linked = SearchLinks.apply(checked, linkChecker, context.today)
        issues += checkIssues + linked.issues
        val answer = linked.answer
        if (answer.stories.isEmpty()) return empty(record) to Report(issues)
        runs.saveSearch(record.id, answer.encode())
        if (catalog == null) return notWritten(record) to Report(issues)
        return write(record, answer, catalog, Report(issues))
    }

    /** The free writer, then the copied-quote removal, the fact check and selection. */
    private suspend fun write(record: RunRecord, answer: SearchAnswer, catalog: ModelCatalog, before: Report): Pair<RunOutcome, Report> {
        val input = record.snapshot.prompt
        val written = FreeModels.first(client, catalog, FreeModels.WRITER, { Writer.request(it, input, answer) }, Writer::parse).value
            ?: return notWritten(record) to before
        val (clean, copies) = Writer.removeCopies(written, answer)
        // No free model for the fact check: the cards stay as written (owner, 2026-10-29).
        val check = FreeModels.first(client, catalog, FreeModels.CHECKER, { FactCheck.request(it, clean, answer) }, FactCheck::parse).value
        val (checked, facts) = check?.let { FactCheck.apply(clean, answer, it) } ?: (clean to emptyList())
        val (digest, joined) = Writer.toDigest(checked, answer, input)
        val selected = select(digest, input)
        val report = Report(
            before.issues + (copies + facts + joined).map { it.issue } + listOfNotNull(Issue(Issue.Kind.FACT_NOT_CHECKED).takeIf { check == null }),
            digest.topics.size,
            selected.topics.size,
        )
        if (selected.topics.isEmpty()) return empty(record) to report
        val digestId = store(record, selected)
        finish(record, RunState.DONE, error = null, digestId = digestId)
        return RunOutcome.Stored(record.id, digestId, selected.topics.size, record.tokensBilled) to report
    }

    /**
     * Keyword mode: [Selection] as usual. Topic mode: every chosen topic counts, so no score cutoff, and the chosen topics
     * left without a card are listed in the digest (owner, 2026-10-29).
     */
    private fun select(digest: Digest, input: PromptInput): Digest {
        if (input.mode == DigestMode.KEYWORDS) return digest.copy(topics = Selection.select(digest.topics))
        val topics = Selection.select(digest.topics, minScore = 0)
        val missing = input.keywords.filter { tag -> topics.none { t -> t.keywords.any { it.equals(tag.trim(), ignoreCase = true) } } }
        return digest.copy(topics = topics, missingTopics = missing.takeIf { topics.isNotEmpty() }.orEmpty())
    }

    private suspend fun repair(catalog: ModelCatalog, broken: String): SearchAnswer? =
        FreeModels.first(client, catalog, FreeModels.CHECKER, { Repair.request(it, broken) }, { Repair.accept(broken, it) }).value

    private suspend fun store(record: RunRecord, digest: Digest): Long {
        val snapshot = record.snapshot
        val view = DigestViews.build(digest, RenderMeta(snapshot.personaDisplayName, snapshot.prompt.model))
        return digests.save(
            StoredDigest(
                runId = record.id,
                generatedAt = now(),
                subject = view.subject,
                summary = DigestSummary.of(digest),
                topicCount = digest.topics.size,
                digest = digest,
                model = snapshot.prompt.model,
                attentionMode = snapshot.prompt.attentionMode.name.lowercase(),
                persona = snapshot.prompt.persona.name.lowercase(),
                tokensBilled = record.tokensBilled,
            ),
        )
    }

    private suspend fun empty(record: RunRecord): RunOutcome {
        finish(record, RunState.FAILED, RunError.EMPTY)
        return RunOutcome.Empty(record.id, record.tokensBilled)
    }

    private suspend fun notWritten(record: RunRecord): RunOutcome {
        finish(record, RunState.FAILED, RunError.WRITE_FAILED)
        return RunOutcome.NotWritten(record.id, record.tokensBilled)
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

    private fun callRecord(record: RunRecord, request: ChatRequest, latency: Long, outcome: RunOutcome, report: Report): CallRecord {
        val prompt = record.snapshot.prompt
        return CallRecord(
            runId = record.id,
            at = record.startedAt,
            model = request.model,
            templateVersion = TemplateConfig.TEMPLATE_VERSION,
            keywords = prompt.keywords,
            tags = prompt.interestTags,
            attentionMode = prompt.attentionMode.name.lowercase(),
            persona = prompt.persona.name.lowercase(),
            mode = prompt.mode.name.lowercase(),
            promptChars = request.systemPrompt.length + request.messages.sumOf { it.content.length },
            latencyMillis = latency,
            tokensBilled = outcome.tokensBilled(),
            error = outcome.error(),
            validTopics = report.validTopics,
            selectedTopics = report.selectedTopics,
            issues = report.issues.groupingBy { it.kind }.eachCount(),
        )
    }

    private fun RunOutcome.tokensBilled(): Long? = when (this) {
        is RunOutcome.Stored -> tokensBilled
        is RunOutcome.Empty -> tokensBilled
        is RunOutcome.NotWritten -> tokensBilled
        is RunOutcome.Unreadable -> tokensBilled
        is RunOutcome.FailedBilled -> tokensBilled
        else -> null
    }

    private fun RunOutcome.error(): RunError? = when (this) {
        is RunOutcome.Stored -> null
        is RunOutcome.Empty -> RunError.EMPTY
        is RunOutcome.NotWritten -> RunError.WRITE_FAILED
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

/** The History card's summary (01-product.md section 5): the writer's line in the voice, else the top 3 titles and "+N more". */
object DigestSummary {
    const val SHOWN = 3
    const val TITLE_MAX_CHARS = 60

    fun of(digest: Digest): String {
        digest.cardSummary?.let { return it }
        val titles = digest.topics.sortedByDescending { it.score }.map { ellipsize(it.title) }
        val shown = titles.take(SHOWN).joinToString(" · ")
        val more = titles.size - SHOWN
        return if (more > 0) "$shown +$more more" else shown
    }

    private fun ellipsize(title: String): String =
        if (title.length <= TITLE_MAX_CHARS) title else title.take(TITLE_MAX_CHARS - 1).trimEnd() + "…"
}
