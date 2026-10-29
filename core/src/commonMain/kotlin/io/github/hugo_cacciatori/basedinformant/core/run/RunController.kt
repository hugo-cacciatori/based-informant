package io.github.hugo_cacciatori.basedinformant.core.run

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate

/** What the background runner does (04-platforms.md section 3): every job of a run, paid or free. */
sealed interface RunJob {
    /** The run the owner just confirmed: the only paid job. */
    data class Generate(val snapshot: RunSnapshot) : RunJob

    data class ReadAgain(val runId: Long) : RunJob

    data class WriteAgain(val runId: Long) : RunJob

    /** At app start, the runs left unfinished (01-product.md section 4.1). */
    data object Recover : RunJob
}

/** What the screens show of the runner. */
sealed interface RunProgress {
    data object Idle : RunProgress

    /** Claimed by the screens and handed to the runner, or running. Generate is disabled meanwhile. */
    data class Running(val job: RunJob) : RunProgress

    /** [outcomes] is empty when there was nothing to do (Read again or Write again on a run that does not allow it). */
    data class Finished(val job: RunJob, val outcomes: List<RunOutcome>) : RunProgress
}

/** The new-digest notification (01-product.md section 7): "N topics · 18 Oct", never titles, keywords or tags. */
data class NewDigestNotice(val digestId: Long, val topicCount: Int, val date: LocalDate) {
    /** One notification per digest: an id derived from the digest's, clear of [NoticeIds.PROGRESS] and [NoticeIds.RUN]. */
    val notificationId: Int get() = NoticeIds.FIRST_DIGEST + (digestId % (Int.MAX_VALUE - NoticeIds.FIRST_DIGEST)).toInt()
}

object NoticeIds {
    /** The ongoing "Generating your digest…" notification. */
    const val PROGRESS = 1

    /** The run notice: one at a time, a newer one replaces it. */
    const val RUN = 2

    /** The reminder (01-product.md section 6): one at a time. */
    const val REMINDER = 3

    const val FIRST_DIGEST = 1_000
}

/** Posts the notifications of 01-product.md section 7. Android implements it; tests record. */
interface RunNotifier {
    fun newDigest(notice: NewDigestNotice)

    /** A run that ended without a digest, posted only when the owner has left the app. */
    fun runNotice(outcome: RunOutcome)
}

/**
 * One job at a time, and the notifications at its end (01-product.md sections 4.1 and 7). The screens [claim] a job;
 * the platform's runner then calls [runClaimed] inside its background-safe host, or [release] when that host could not
 * start (nothing was sent then). With the app open, no notification is posted: the result shows in the app only
 * (owner, 2026-10-29).
 */
class RunController(
    private val perform: suspend (RunJob) -> List<RunOutcome>,
    private val notifier: RunNotifier,
    private val appVisible: () -> Boolean,
    private val today: () -> LocalDate,
) {
    private val state = MutableStateFlow<RunProgress>(RunProgress.Idle)
    val progress: StateFlow<RunProgress> = state.asStateFlow()

    /** True when [job] got the runner; false while another job is claimed or running (repeat taps are ignored). */
    fun claim(job: RunJob): Boolean {
        while (true) {
            val current = state.value
            if (current is RunProgress.Running) return false
            if (state.compareAndSet(current, RunProgress.Running(job))) return true
        }
    }

    /** The claimed job could not be handed to the runner: nothing ran, the screens are free again. */
    fun release() {
        state.value = RunProgress.Idle
    }

    /** Runs the claimed job to its end and posts its notifications. Null when no job is claimed. */
    suspend fun runClaimed(): List<RunOutcome>? {
        val job = (state.value as? RunProgress.Running)?.job ?: return null
        var outcomes = emptyList<RunOutcome>()
        try {
            outcomes = perform(job)
        } finally {
            // A crash still frees the runner; the run record tells the rest at the next start.
            state.value = RunProgress.Finished(job, outcomes)
        }
        if (!appVisible()) outcomes.forEach(::notify)
        return outcomes
    }

    /** The screens read [RunProgress.Finished] and dismiss it. */
    fun acknowledge() {
        val current = state.value
        if (current is RunProgress.Finished) state.compareAndSet(current, RunProgress.Idle)
    }

    private fun notify(outcome: RunOutcome) = when (outcome) {
        is RunOutcome.Stored -> notifier.newDigest(NewDigestNotice(outcome.digestId, outcome.topicCount, today()))
        // Nothing was sent: the screens that asked say why.
        RunOutcome.Busy, RunOutcome.NotBuilt -> Unit
        else -> notifier.runNotice(outcome)
    }
}

/** The jobs on [DigestRun]. */
suspend fun DigestRun.perform(job: RunJob): List<RunOutcome> = when (job) {
    is RunJob.Generate -> listOf(execute(job.snapshot))
    is RunJob.ReadAgain -> listOfNotNull(readAgain(job.runId))
    is RunJob.WriteAgain -> listOfNotNull(writeAgain(job.runId))
    RunJob.Recover -> recover()
}
