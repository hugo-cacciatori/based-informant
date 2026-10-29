package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: one job at a time and the notifications at its end (01-product.md sections 4.1 and 7). */
class RunControllerTest {
    private class RecordingNotifier : RunNotifier {
        val digests = mutableListOf<NewDigestNotice>()
        val notices = mutableListOf<RunOutcome>()
        override fun newDigest(notice: NewDigestNotice) { digests += notice }
        override fun runNotice(outcome: RunOutcome) { notices += outcome }
    }

    private val notifier = RecordingNotifier()
    private var visible = false
    private val today = LocalDate(2026, 10, 18)

    private val generate = RunJob.Generate(
        RunSnapshot(
            PromptInput(
                keywords = listOf("a", "b", "c", "d", "e"), interestTags = emptyList(),
                attentionMode = AttentionMode.BALANCED, persona = Persona.OFF, today = today,
            ),
            personaDisplayName = null,
        ),
    )

    private fun controller(perform: suspend (RunJob) -> List<RunOutcome>) = RunController(perform, notifier, { visible }, { today })

    @Test
    fun aSecondClaimIsRefusedUntilTheJobEnds() = runBlocking {
        val controller = controller { listOf(RunOutcome.Stored(1, 7, 3, 1_440)) }
        assertTrue(controller.claim(generate))
        assertFalse(controller.claim(generate), "a repeat tap is ignored")
        assertFalse(controller.claim(RunJob.ReadAgain(1)))
        controller.runClaimed()
        assertEquals(RunProgress.Finished(generate, listOf(RunOutcome.Stored(1, 7, 3, 1_440))), controller.progress.value)
        assertTrue(controller.claim(RunJob.ReadAgain(1)))
    }

    @Test
    fun nothingRunsWithoutAClaim() = runBlocking {
        var ran = false
        assertNull(controller { ran = true; emptyList() }.runClaimed())
        assertFalse(ran)
    }

    @Test
    fun aHostThatCouldNotStartFreesTheRunner() {
        val controller = controller { emptyList() }
        controller.claim(generate)
        controller.release()
        assertEquals(RunProgress.Idle, controller.progress.value)
    }

    @Test
    fun aStoredDigestIsNotifiedWithItsCountAndDateOnly() = runBlocking {
        val controller = controller { listOf(RunOutcome.Stored(1, 7, 3, 1_440)) }
        controller.claim(generate)
        controller.runClaimed()
        val notice = notifier.digests.single()
        assertEquals(NewDigestNotice(7, 3, today), notice)
        assertEquals(1_007, notice.notificationId)
        assertTrue(notifier.notices.isEmpty())
    }

    @Test
    fun withTheAppOpenNothingIsPosted() = runBlocking {
        visible = true
        val controller = controller { listOf(RunOutcome.Stored(1, 7, 3, 1_440), RunOutcome.Interrupted(2)) }
        controller.claim(RunJob.Recover)
        controller.runClaimed()
        assertTrue(notifier.digests.isEmpty() && notifier.notices.isEmpty())
    }

    @Test
    fun aRunWithoutADigestGetsARunNoticeButNothingSentGetsNone() = runBlocking {
        val outcomes = listOf(RunOutcome.Empty(1, 1_440), RunOutcome.Refused(2, RunError.KEY_REFUSED), RunOutcome.Busy, RunOutcome.NotBuilt)
        val controller = controller { outcomes }
        controller.claim(generate)
        controller.runClaimed()
        assertEquals(outcomes.take(2), notifier.notices)
    }

    @Test
    fun aCrashStillFreesTheRunner() {
        val controller = controller { error("boom") }
        controller.claim(generate)
        assertFailsWith<IllegalStateException> { runBlocking { controller.runClaimed() } }
        assertEquals(RunProgress.Finished(generate, emptyList()), controller.progress.value)
        controller.acknowledge()
        assertEquals(RunProgress.Idle, controller.progress.value)
    }

    @Test
    fun digestIdsGiveDistinctNotificationIds() {
        val ids = (1L..50L).map { NewDigestNotice(it, 1, today).notificationId }
        assertEquals(50, ids.toSet().size)
        assertTrue(ids.none { it == NoticeIds.PROGRESS || it == NoticeIds.RUN })
    }
}
