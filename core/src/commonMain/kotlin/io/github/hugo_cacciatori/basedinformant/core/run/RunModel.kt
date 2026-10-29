package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.check.CheckRecord
import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput

/**
 * Everything one run uses, taken when the owner confirms (01-product.md section 4 step 3), so later changes in
 * Settings never affect it. The keywords, tags and known topic tags are in [prompt].
 */
data class RunSnapshot(
    val prompt: PromptInput,
    /** The persona's display name for the digest's header, or null when Persona is Off. */
    val personaDisplayName: String?,
)

/** 04-platforms.md section 7, `runs`. */
enum class RunState { CALLING, RECEIVED, DONE, FAILED, INTERRUPTED }

/** Why a run ended without a stored digest. */
enum class RunError {
    KEY_REFUSED,
    INSUFFICIENT_TOKENS,
    UNAVAILABLE,
    FORBIDDEN,
    UNEXPECTED_STATUS,

    /** rfab charged for a call its provider failed (a 500 or 502 the balance check found billed). */
    FAILED_BILLED,

    /** The answer could not be read: its raw text is kept for **Read again**. */
    UNREADABLE,

    /** The answer was read, but no topic survived the checks. */
    EMPTY,

    /** The search was read, but no free model wrote the digest: the search is kept for **Write again**, for free. */
    WRITE_FAILED,

    /** The app lost the answer after the request may have left: maybe billed, never re-sent. */
    INTERRUPTED,
}

data class RunRecord(
    val id: Long,
    val startedAt: Long,
    val state: RunState,
    val snapshot: RunSnapshot,
    val finishedAt: Long? = null,
    val tokensBilled: Long? = null,
    val error: RunError? = null,
    val digestId: Long? = null,
)

/** One History entry (01-product.md section 5). The mail is built from [digest] when it is shared (section 8). */
data class StoredDigest(
    val runId: Long,
    val generatedAt: Long,
    val subject: String,
    /** The card summary: the writer's line in the voice, else the top 3 titles by score, then "+N more". */
    val summary: String,
    val topicCount: Int,
    val digest: Digest,
    val model: String,
    val attentionMode: String,
    val persona: String,
    val tokensBilled: Long?,
    val based: Boolean = false,
)

/** One paid call, for the call history (01-product.md section 4.5). Never shown in the app. */
data class CallRecord(
    val runId: Long,
    val at: Long,
    val model: String,
    val templateVersion: Int,
    val keywords: List<String>,
    val tags: List<String>,
    val attentionMode: String,
    val persona: String,
    /** "keywords" or "topics". */
    val mode: String,
    val based: Boolean = false,
    /** Characters of the system prompt and the user message together. */
    val promptChars: Int,
    val latencyMillis: Long,
    val tokensBilled: Long?,
    /** The run's error, or null when the answer was read. */
    val error: RunError?,
    val validTopics: Int = 0,
    val selectedTopics: Int = 0,
    val issues: Map<Issue.Kind, Int> = emptyMap(),
)

/** What the owner is told at the end of a run (01-product.md section 4.3). */
sealed interface RunOutcome {
    /** Another run is active: nothing was sent. */
    data object Busy : RunOutcome

    /** The prompt builder refused the input (a limit, a missing prompt file): nothing was sent. */
    data object NotBuilt : RunOutcome

    data class Stored(val runId: Long, val digestId: Long, val topicCount: Int, val tokensBilled: Long?) : RunOutcome

    /** Nothing survived the checks. No second call is offered (the date window and its wider retry were dropped, owner 2026-10-29). */
    data class Empty(val runId: Long, val tokensBilled: Long?) : RunOutcome

    /** No free model wrote the digest; the search is kept and **Write again** runs the free cycle on it, for free. */
    data class NotWritten(val runId: Long, val tokensBilled: Long?) : RunOutcome

    /** The answer could not be read; it is kept, and **Read again** can process it after an update. */
    data class Unreadable(val runId: Long, val tokensBilled: Long?) : RunOutcome

    /** Nothing was billed. */
    data class Refused(val runId: Long, val error: RunError) : RunOutcome

    /** rfab charged for a call that gave no answer. */
    data class FailedBilled(val runId: Long, val tokensBilled: Long?) : RunOutcome

    /** The tokens may have been billed with no answer. */
    data class Interrupted(val runId: Long) : RunOutcome
}

/** The run's storage. `:data` implements it with Room and files; tests use fakes. */
interface RunStore {
    /** Creates the run in [RunState.CALLING] in one transaction; null when another run is still active. */
    suspend fun start(snapshot: RunSnapshot, startedAt: Long): Long?

    suspend fun update(record: RunRecord)

    suspend fun get(id: Long): RunRecord?

    /** Runs left [RunState.CALLING] or [RunState.RECEIVED], for recovery at app start. */
    suspend fun unfinished(): List<RunRecord>

    /** Written before any parsing (04-platforms.md section 7, raw responses). */
    suspend fun saveRaw(runId: Long, content: String)

    suspend fun raw(runId: Long): String?

    /** The checked search answer with the pages' text, kept for **Write again** (02-ai-call.md section 12). */
    suspend fun saveSearch(runId: Long, answer: String)

    suspend fun search(runId: Long): String?

    /** Tokens billed by the latest finished calls with [model], newest first, for the average cost. */
    suspend fun recentCosts(model: String, limit: Int): List<Long>

    /**
     * Deletes the raw answers and checked searches of finished runs started before [before], except those of runs
     * waiting for Read again or Write again ([RawRetention]).
     */
    suspend fun pruneRaw(before: Long)
}

interface DigestStore {
    suspend fun save(digest: StoredDigest): Long

    /**
     * The saved topics, most recently seen first, each once (ignoring case): the choices of topic mode. Every stored
     * digest's topic tags are saved, and stay until the owner deletes them (01-product.md section 3, owner 2026-10-29).
     */
    suspend fun topicTags(): List<String>

    /** When the newest digest was stored on this device, for the confirmation dialog; null before the first. */
    suspend fun lastGeneratedAt(): Long?
}

/** Deleting saved topics (01-product.md section 3): one or several at once, with Undo. */
interface TopicStore {
    /** Deletes [tags] (ignoring case) and returns them as they were, for [restore]. */
    suspend fun delete(tags: Collection<String>): List<SavedTopic>

    suspend fun restore(topics: List<SavedTopic>)
}

/** A saved topic and when a digest last had it. */
data class SavedTopic(val tag: String, val savedAt: Long)

interface CallLog {
    suspend fun record(call: CallRecord)

    /** The free input check, every time it runs (01-product.md section 4.4). */
    suspend fun recordCheck(check: CheckRecord)
}
