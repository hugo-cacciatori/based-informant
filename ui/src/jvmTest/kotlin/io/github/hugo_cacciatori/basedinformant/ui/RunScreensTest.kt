package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hugo_cacciatori.basedinformant.core.check.CheckEntry
import io.github.hugo_cacciatori.basedinformant.core.check.CheckOutcome
import io.github.hugo_cacciatori.basedinformant.core.check.CheckRecord
import io.github.hugo_cacciatori.basedinformant.core.check.Refusal
import io.github.hugo_cacciatori.basedinformant.core.key.SecretStore
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.render.RenderedDigest
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.Source
import io.github.hugo_cacciatori.basedinformant.core.digest.Topic
import io.github.hugo_cacciatori.basedinformant.core.run.CallLog
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryCard
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryModel
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryStore
import io.github.hugo_cacciatori.basedinformant.core.run.CallRecord
import io.github.hugo_cacciatori.basedinformant.core.run.DigestStore
import io.github.hugo_cacciatori.basedinformant.core.run.SavedTopic
import io.github.hugo_cacciatori.basedinformant.core.run.TopicStore
import io.github.hugo_cacciatori.basedinformant.core.run.GenerateModel
import io.github.hugo_cacciatori.basedinformant.core.run.RunController
import io.github.hugo_cacciatori.basedinformant.core.run.RunJob
import io.github.hugo_cacciatori.basedinformant.core.run.RunNotifier
import io.github.hugo_cacciatori.basedinformant.core.run.RunOutcome
import io.github.hugo_cacciatori.basedinformant.core.run.RunRecord
import io.github.hugo_cacciatori.basedinformant.core.run.RunSnapshot
import io.github.hugo_cacciatori.basedinformant.core.run.RunStore
import io.github.hugo_cacciatori.basedinformant.core.run.StoredDigest
import io.github.hugo_cacciatori.basedinformant.core.settings.AppSettings
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordStore
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.Preset
import io.github.hugo_cacciatori.basedinformant.core.settings.PresetStore
import io.github.hugo_cacciatori.basedinformant.core.settings.PresetsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

private const val KEY = "rfab_abcdefghijklmnopqrstuvwxyz"
private val FIVE = listOf("fusion energy", "deep-sea mining", "chess", "rust", "volcanoes")

/** Offline stores and runner for the run screens; nothing here can spend tokens. */
internal class RunFixture(
    keywords: List<String> = FIVE,
    var check: CheckOutcome = CheckOutcome.Passed(emptyList()),
    topics: List<String> = emptyList(),
    /** What the runner returns; it waits for [release] first, so a test can see the progress. */
    var outcomes: List<RunOutcome> = emptyList(),
    /** The stored settings the confirmation reads. */
    private val stored: AppSettings = AppSettings(),
) {
    val started = mutableListOf<RunJob>()

    /** What Share → Email handed to the mail app; [mailApp] false plays a device without one. */
    val shared = mutableListOf<RenderedDigest>()
    var mailApp = true
    var asked = 0
    val release = CompletableDeferred<Unit>()

    private val secrets = object : SecretStore {
        override suspend fun rfabKey() = KEY
        override suspend fun setRfabKey(key: String) = Unit
        override suspend fun removeRfabKey() = Unit
    }
    private val keywordStore = object : KeywordStore {
        var list = keywords
        override suspend fun load() = list
        override suspend fun save(keywords: List<String>) { list = keywords }
    }
    private val settings = object : SettingsStore {
        override suspend fun load() = stored
        override suspend fun save(settings: AppSettings) = Unit
    }
    private val client: (String) -> RfabClient = {
        object : RfabClient {
            override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> = fail("no chat call from the UI")
            override suspend fun balance(): RfabResult<Balance> = RfabResult.Ok(Balance(42_000), raw = "{}")
            override suspend fun models(): RfabResult<ModelCatalog> = fail("no models call from the UI")
        }
    }
    private val runs = object : RunStore {
        override suspend fun start(snapshot: RunSnapshot, startedAt: Long): Long? = fail("the UI never creates a run")
        override suspend fun update(record: RunRecord) = Unit
        override suspend fun get(id: Long): RunRecord? = null
        override suspend fun unfinished(): List<RunRecord> = emptyList()
        override suspend fun saveRaw(runId: Long, content: String) = Unit
        override suspend fun raw(runId: Long): String? = null
        override suspend fun saveSearch(runId: Long, answer: String) = Unit
        override suspend fun search(runId: Long): String? = null
        override suspend fun recentCosts(model: String, limit: Int) = listOf(1_440L)
        override suspend fun pruneRaw(before: Long) = Unit
    }
    /** The saved topics; deleting and Undo change them. */
    var savedTopics = topics
    /** Recency by tag, for the Hot section (today is 2026-10-29). */
    var topicDates = mapOf<String, LocalDate>()
    private val digests = object : DigestStore, TopicStore {
        override suspend fun save(digest: StoredDigest) = 1L
        override suspend fun savedTopics() = savedTopics.map { SavedTopic(it, 1L, topicDates[it]) }
        override suspend fun lastGeneratedAt(): Long? = null
        override suspend fun delete(tags: Collection<String>): List<SavedTopic> {
            val gone = savedTopics.filter { t -> tags.any { it.equals(t, ignoreCase = true) } }
            savedTopics = savedTopics - gone.toSet()
            return gone.map { SavedTopic(it, 1L) }
        }
        override suspend fun restore(topics: List<SavedTopic>) { savedTopics = savedTopics + topics.map { it.tag } }
    }
    private val calls = object : CallLog {
        override suspend fun record(call: CallRecord) = Unit
        override suspend fun recordCheck(check: CheckRecord) = Unit
    }
    private val presetStore = object : PresetStore {
        val presets = mutableListOf<Preset>()
        override suspend fun all() = presets.toList()
        override suspend fun add(keywords: List<String>): Long = (presets.size + 1L).also { presets += Preset(it, keywords) }
        override suspend fun delete(id: Long) { presets.removeAll { it.id == id } }
    }

    val controller = RunController(
        perform = { release.await(); outcomes },
        notifier = object : RunNotifier {
            override fun newDigest(notice: io.github.hugo_cacciatori.basedinformant.core.run.NewDigestNotice) = Unit
            override fun runNotice(outcome: RunOutcome) = Unit
        },
        appVisible = { true },
        today = { LocalDate(2026, 10, 29) },
    )

    private val startRun: (RunJob) -> Boolean = { job -> controller.claim(job).also { if (it) started += job } }

    val settingsVm = SettingsViewModel(io.github.hugo_cacciatori.basedinformant.core.settings.SettingsModel(settings, secrets, client))
    val keywordsVm = KeywordsViewModel(KeywordsModel(keywordStore, secrets, client))
    val presetsVm = PresetsViewModel(PresetsModel(presetStore, keywordsVm.model))
    /** History rows by id; one stored digest unless a test changes it. */
    val history = mutableMapOf(7L to storedDigest())
    val historyVm = HistoryViewModel(
        HistoryModel(
            object : HistoryStore {
                override suspend fun cards() = history.entries.sortedByDescending { it.value.generatedAt }
                    .map { (id, d) -> HistoryCard(id, d.generatedAt, d.topicCount, d.summary) }
                override suspend fun get(id: Long) = history[id]
                override suspend fun delete(id: Long) { history.remove(id) }
            },
        ) { null },
    )
    val generateVm = GenerateViewModel(
        GenerateModel(
            keywords = { keywordsVm.model.state.value.keywords }, settings = settings, secrets = secrets, clientFor = client,
            inputCheck = { _, _, _ -> check }, runs = runs, digests = digests, topicStore = digests, calls = calls, start = startRun,
            personaName = { null }, now = { 1_000L }, today = { LocalDate(2026, 10, 29) },
        ),
        controller, startRun, keywordsVm.model, recentCharges = { emptyList() }, askNotifications = { asked++ },
    )
}

/** A stored digest with one card, synthetic. */
internal fun storedDigest() = StoredDigest(
    runId = 1, generatedAt = 1_000, subject = "Plasma record and a seabed vote", summary = "A plasma record and a mining vote.", topicCount = 1, model = "openrouter:perplexity/sonar",
    attentionMode = "balanced", persona = "off", tokensBilled = 1_440,
    digest = Digest(
        2, LocalDate(2026, 10, 29), "Hello.", "Bye.",
        listOf(
            Topic(
                "t1", "Stellarator research", "Stellarator holds plasma for 40 minutes", "A research stellarator held a plasma.",
                "The lab plans a one-hour campaign.", listOf("fusion energy"), 80, null,
                listOf(Source("Plasma record", "Science Example", "https://science.example.com/a", "en", LocalDate(2026, 10, 28))),
            ),
        ),
    ),
)

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class RunScreensTest {
    @BeforeTest
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun resetDispatcher() = Dispatchers.resetMain()

    private fun androidx.compose.ui.test.ComposeUiTest.show(f: RunFixture) = setContent {
        AppTheme {
            AppShell(
                f.keywordsVm, f.settingsVm, f.generateVm, f.presetsVm, f.historyVm, notificationsAllowed = { false },
                shareEmail = { f.shared += it; f.mailApp },
            )
        }
    }

    @Test
    fun shareInTheViewerOpensAMenuWhoseEmailHandsTheMailOver() = runComposeUiTest {
        val f = RunFixture()
        show(f)
        onNodeWithText("History").performClick()
        waitForIdle()
        onNodeWithText("A plasma record and a mining vote.").performClick()
        waitForIdle()
        onNodeWithTag("share").performClick()
        waitForIdle()
        assertEquals(emptyList(), f.shared, "Share opens a menu first")
        onNodeWithTag("share-email").performClick()
        waitForIdle()
        val mail = f.shared.single()
        assertEquals("Plasma record and a seabed vote", mail.subject)
        assertTrue("Stellarator holds plasma for 40 minutes" in mail.text)
        assertTrue("https://science.example.com/a" in mail.html)
    }

    @Test
    fun shareSitsNextToViewWhenARunEnds() = runComposeUiTest {
        val f = RunFixture(outcomes = listOf(RunOutcome.Stored(1, 7, 1, 1_440)))
        show(f)
        f.controller.claim(RunJob.Recover)
        runBlocking { f.release.complete(Unit); f.controller.runClaimed() }
        waitForIdle()
        // The run's dialog has its own Share, next to View; Today's latest digest has another.
        onNode(hasTestTag("share") and hasAnyAncestor(isDialog())).performClick()
        waitForIdle()
        onNodeWithTag("share-email").performClick()
        waitForIdle()
        assertEquals("Plasma record and a seabed vote", f.shared.single().subject)
    }

    @Test
    fun withoutAMailAppShareSaysSo() = runComposeUiTest {
        val f = RunFixture().apply { mailApp = false }
        show(f)
        onNodeWithText("History").performClick()
        waitForIdle()
        onNodeWithText("A plasma record and a mining vote.").performClick()
        waitForIdle()
        onNodeWithTag("share").performClick()
        waitForIdle()
        onNodeWithTag("share-email").performClick()
        waitForIdle()
        onNodeWithText("No mail app on this device.").assertExists()
    }

    @Test
    fun generateConfirmsThenRunsWithProgressAndNoCancel() = runComposeUiTest {
        val f = RunFixture(outcomes = listOf(RunOutcome.Stored(1, 7, 4, 1_440)))
        show(f)
        onNodeWithTag("generate").performClick()
        waitForIdle()
        onNodeWithText("rfab tokens will be spent", substring = true).assertExists()
        onNodeWithText("Average cost of the last calls: 1,440 tokens", substring = true).assertExists()
        // Everything configured that the call uses (owner, 2026-10-29).
        onNodeWithText("Keywords: ${FIVE.joinToString(", ")}").assertExists()
        onNodeWithText("Interest tags: none").assertExists()
        onNodeWithTag("confirm-settings").assertTextEquals("Attention mode: Balanced · Persona: Off · Based: off")
        onNodeWithTag("confirm-go").performClick()
        waitForIdle()
        assertIs<RunJob.Generate>(f.started.single())
        assertEquals(1, f.asked, "the notification permission is asked after confirm")
        onNodeWithTag("progress").assertExists()
        onNodeWithTag("generate").assertDoesNotExist()
        onNodeWithText("Notifications are off", substring = true).assertExists()

        runBlocking { f.release.complete(Unit); f.controller.runClaimed() }
        waitForIdle()
        onNodeWithText("Saved in History: 4 topics.", substring = true).assertExists()
        onNodeWithTag("outcome-ok").performClick()
        waitForIdle()
        onNodeWithTag("generate").assertExists()
    }

    @Test
    fun theConfirmationShowsTagsAndBasedOn() = runComposeUiTest {
        show(RunFixture(stored = AppSettings(tags = listOf("science", "policy"), based = true)))
        onNodeWithTag("generate").performClick()
        waitForIdle()
        onNodeWithText("Interest tags: science, policy").assertExists()
        onNodeWithTag("confirm-settings").assertTextContains("Based: on", substring = true)
    }

    @Test
    fun viewOpensTheStoredDigestInHistory() = runComposeUiTest {
        val f = RunFixture(outcomes = listOf(RunOutcome.Stored(1, 7, 1, 1_440)))
        show(f)
        f.controller.claim(RunJob.Recover)
        runBlocking { f.release.complete(Unit); f.controller.runClaimed() }
        waitForIdle()
        onNodeWithTag("outcome-view").performClick()
        waitForIdle()
        onNodeWithTag("viewer-subject").assertTextContains("Plasma record and a seabed vote")
        onNodeWithText("Stellarator holds plasma for 40 minutes").assertExists()
        onNodeWithText("Science Example, 28 Oct 2026, en").assertExists()
        onNodeWithText("The lab plans a one-hour campaign.").assertDoesNotExist()
        onNodeWithText("More").performClick()
        waitForIdle()
        onNodeWithText("The lab plans a one-hour campaign.").assertExists()
    }

    @Test
    fun historyListsCardsAndDeletesAfterAConfirmation() = runComposeUiTest {
        val f = RunFixture()
        show(f)
        onNodeWithText("History").performClick()
        waitForIdle()
        onNodeWithText("A plasma record and a mining vote.").performClick()
        waitForIdle()
        onNodeWithContentDescription("Delete digest").performClick()
        waitForIdle()
        onNodeWithTag("delete-confirm").performClick()
        waitForIdle()
        assertEquals(emptyMap(), f.history)
        onNodeWithText("Your digests will appear here.").assertExists()
    }

    @Test
    fun refusedEntriesAreListedAndNothingIsConfirmed() = runComposeUiTest {
        val f = RunFixture(check = CheckOutcome.Refused(listOf(Refusal(CheckEntry(CheckEntry.Kind.KEYWORD, 2, "chess"), "did you mean chess?")), emptyList()))
        show(f)
        onNodeWithTag("generate").performClick()
        waitForIdle()
        onNodeWithText("Keyword “chess”: did you mean chess?", substring = true).assertExists()
        onNodeWithTag("confirmation").assertDoesNotExist()
        assertEquals(emptyList(), f.started)
    }

    @Test
    fun anUnreadableAnswerOffersReadAgainButNoSendToSupportYet() = runComposeUiTest {
        val f = RunFixture(outcomes = listOf(RunOutcome.Unreadable(3, 1_440)))
        show(f)
        f.controller.claim(RunJob.ReadAgain(3))
        runBlocking { f.release.complete(Unit); f.controller.runClaimed() }
        waitForIdle()
        onNodeWithText("The answer could not be read.", substring = true).assertExists()
        onNodeWithText("Read again").assertExists()
        onNodeWithText("Send to support").assertDoesNotExist()
        onNodeWithText("Read again").performClick()
        waitForIdle()
        assertEquals(RunJob.ReadAgain(3), f.started.single())
    }

    @Test
    fun topicModeWithoutSavedTopicsOffersAnAllTrendingDigest() = runComposeUiTest {
        show(RunFixture())
        onNodeWithTag("inputs-open").performClick()
        waitForIdle()
        onNodeWithTag("mode-topics").assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithText("No saved topics yet", substring = true).assertExists()
        onNodeWithTag("generate").assertIsEnabled()
    }

    @Test
    fun topicModeSaysKeywordsAreNotUsedAndNeedsNoPick() = runComposeUiTest {
        val f = RunFixture(topics = listOf("Stellarator research"))
        show(f)
        f.generateVm.reload()
        waitForIdle()
        onNodeWithTag("inputs-open").performClick()
        waitForIdle()
        onNodeWithTag("mode-topics").performClick()
        waitForIdle()
        onNodeWithTag("topics-note").assertTextContains("Keywords are not used in this mode", substring = true)
        // No pick is fine: the free slots get trending news (owner, 2026-10-29).
        onNodeWithTag("generate-reason").assertDoesNotExist()
        onNodeWithTag("generate").assertIsEnabled()
    }

    @Test
    fun hotTopicsHaveTheirHeadingOnlyWhenThereAreAny() = runComposeUiTest {
        val f = RunFixture(topics = listOf("Stellarator research", "Club chess"))
        show(f)
        f.generateVm.reload()
        waitForIdle()
        onNodeWithTag("inputs-open").performClick()
        waitForIdle()
        onNodeWithTag("mode-topics").performClick()
        waitForIdle()
        onNodeWithTag("topics-hot").assertDoesNotExist()
        f.topicDates = mapOf("Stellarator research" to LocalDate(2026, 10, 28))
        f.generateVm.reload()
        waitForIdle()
        onNodeWithTag("topics-hot").assertTextContains("Hot")
        assertEquals(1, f.generateVm.state.value.hotCount)
    }

    @Test
    fun topicsAreTickedAndDeletedTogetherWithUndo() = runComposeUiTest {
        val f = RunFixture(topics = listOf("Stellarator research", "Club chess", "Seabed rules"))
        show(f)
        f.generateVm.reload()
        waitForIdle()
        onNodeWithTag("inputs-open").performClick()
        waitForIdle()
        onNodeWithTag("mode-topics").performClick()
        waitForIdle()
        // A tap picks; deleting needs Select first (owner, 2026-10-30).
        onNode(hasText("Club chess") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf("Club chess"), f.generateVm.state.value.chosen)
        onNodeWithTag("topics-select").performClick()
        onNodeWithTag("topics-delete").assertIsNotEnabled()
        onNodeWithTag("topics-cancel").performClick()
        onNodeWithTag("topics-delete").assertDoesNotExist()
        onNodeWithTag("topics-select").performClick()
        onNode(hasText("Club chess") and hasClickAction()).performClick()
        onNode(hasText("Seabed rules") and hasClickAction()).performClick()
        onNodeWithText("2 selected").assertExists()
        onNodeWithTag("topics-delete").assertTextContains("Delete (2)").performClick()
        waitForIdle()
        assertEquals(listOf("Stellarator research"), f.savedTopics)
        onNodeWithText("Deleted 2 topics").assertExists()
        onNodeWithText("Undo").performClick()
        waitForIdle()
        assertEquals(setOf("Stellarator research", "Club chess", "Seabed rules"), f.savedTopics.toSet())
    }

    @Test
    fun theCurrentFiveAreSavedAsAPresetOnce() = runComposeUiTest {
        val f = RunFixture()
        setContent {
            AppTheme {
                val state by f.keywordsVm.state.collectAsStateWithLifecycle()
                val presets by f.presetsVm.state.collectAsStateWithLifecycle()
                KeywordsScreen(state, f.keywordsVm, SnackbarHostState(), onOpenSettings = {}, run = RunUi(presets = presets, presetActions = f.presetsVm))
            }
        }
        onNodeWithTag("presets-open").performClick()
        waitForIdle()
        onNodeWithTag("preset-save").performClick()
        waitForIdle()
        onNodeWithText(FIVE.joinToString(" · ")).assertExists()
        onNodeWithTag("preset-save").performClick()
        waitForIdle()
        onNodeWithText("These keywords are already a preset.", substring = true).assertExists()
    }
}
