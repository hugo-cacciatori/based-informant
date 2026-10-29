package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hugo_cacciatori.basedinformant.core.key.SecretStore
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.reminder.Reminder
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.settings.AppSettings
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordStore
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

private class MemorySecrets(var key: String? = null) : SecretStore {
    override suspend fun rfabKey() = key
    override suspend fun setRfabKey(key: String) { this.key = key }
    override suspend fun removeRfabKey() { key = null }
}

private class MemoryKeywords(var keywords: List<String> = emptyList()) : KeywordStore {
    override suspend fun load() = keywords
    override suspend fun save(keywords: List<String>) { this.keywords = keywords }
}

private class MemorySettings(var settings: AppSettings = AppSettings()) : SettingsStore {
    override suspend fun load() = settings
    override suspend fun save(settings: AppSettings) { this.settings = settings }
}

/** Answers only the free balance call; anything that could spend tokens fails the test. */
private fun balanceOnly(tokens: Long): (String) -> RfabClient = {
    object : RfabClient {
        override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> = fail("no chat call from the UI")
        override suspend fun balance(): RfabResult<Balance> = RfabResult.Ok(Balance(tokens), raw = "{}")
        override suspend fun models(): RfabResult<ModelCatalog> = fail("no models call from the UI")
    }
}

// A synthetic key of the right shape, built in parts so the secret scan does not take it for a real one.
private val TEST_KEY = "rfab_" + "x".repeat(24) + "wxyz"

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ScreensTest {
    @BeforeTest
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun resetDispatcher() = Dispatchers.resetMain()

    @Test
    fun withoutKeyTheFirstRunCardShowsAndGenerateSaysWhy() = runComposeUiTest {
        val vm = KeywordsViewModel(KeywordsModel(MemoryKeywords(listOf("solar")), MemorySecrets(), balanceOnly(0)))
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                KeywordsScreen(state, vm, SnackbarHostState(), onOpenSettings = {})
            }
        }
        onNodeWithTag("first-run").assertExists()
        onNodeWithTag("balance-line").assertDoesNotExist()
        onNodeWithTag("generate").assertIsNotEnabled()
        onNodeWithTag("generate-reason").assertTextContains("Add your rfab API key in Settings.")
    }

    @Test
    fun addingKeywordsSavesThemAndRefusesADuplicate() = runComposeUiTest {
        val store = MemoryKeywords()
        val vm = KeywordsViewModel(KeywordsModel(store, MemorySecrets(TEST_KEY), balanceOnly(120_174)))
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                KeywordsScreen(state, vm, SnackbarHostState(), onOpenSettings = {})
            }
        }
        onNodeWithTag("first-run").assertDoesNotExist()
        onNodeWithTag("balance-line").assertTextContains("Balance: 120,174 tokens")
        onNodeWithTag("generate-reason").assertTextContains("A digest takes exactly 5 keywords: add 5 more.")

        onNodeWithTag("keyword-input").performTextInput("  Solar power ")
        onNodeWithText("Add").performClick()
        waitForIdle()
        assertEquals(listOf("Solar power"), store.keywords)
        onNodeWithTag("generate").assertIsNotEnabled()
        onNodeWithTag("generate-reason").assertTextContains("add 4 more.", substring = true)

        onNodeWithTag("keyword-input").performTextInput("SOLAR POWER")
        onNodeWithText("Add").performClick()
        waitForIdle()
        onNodeWithText("Already in the list (capitals don’t count).").assertExists()
        assertEquals(listOf("Solar power"), store.keywords)

        onNodeWithTag("keyword-input").performTextReplacement("")
        for (keyword in listOf("wind", "tides", "chess", "rail")) {
            onNodeWithTag("keyword-input").performTextInput(keyword)
            onNodeWithText("Add").performClick()
            waitForIdle()
        }
        assertEquals(listOf("Solar power", "wind", "tides", "chess", "rail"), store.keywords)
        onNodeWithTag("generate").assertIsEnabled()
    }

    @Test
    fun deletingAKeywordSavesTheShorterList() = runComposeUiTest {
        val store = MemoryKeywords(listOf("solar", "wind"))
        val vm = KeywordsViewModel(KeywordsModel(store, MemorySecrets(TEST_KEY), balanceOnly(120_174)))
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                KeywordsScreen(state, vm, SnackbarHostState(), onOpenSettings = {})
            }
        }
        // Presets shows until a keyword is ticked; then Delete (N) takes its place.
        onNodeWithTag("presets-open").assertExists()
        onNodeWithText("solar").performClick()
        onNodeWithTag("delete-ticked").assertTextContains("Delete (1)").performClick()
        waitForIdle()
        assertEquals(listOf("wind"), store.keywords)
        onNodeWithTag("presets-open").assertExists()
    }

    @Test
    fun selectAllClearsTheListAndUndoBringsItBack() = runComposeUiTest {
        val store = MemoryKeywords(listOf("solar", "wind", "tides"))
        val vm = KeywordsViewModel(KeywordsModel(store, MemorySecrets(TEST_KEY), balanceOnly(120_174)))
        val snackbar = SnackbarHostState()
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                androidx.compose.foundation.layout.Box {
                    KeywordsScreen(state, vm, snackbar, onOpenSettings = {})
                    androidx.compose.material3.SnackbarHost(snackbar)
                }
            }
        }
        onNodeWithContentDescription("Select all keywords").performClick()
        onNodeWithTag("delete-ticked").assertTextContains("Delete (3)").performClick()
        waitForIdle()
        assertEquals(emptyList(), store.keywords)
        onNodeWithText("Deleted 3 keywords").assertExists()
        onNodeWithText("Undo").performClick()
        waitForIdle()
        assertEquals(listOf("solar", "wind", "tides"), store.keywords)
    }

    @Test
    fun theEditButtonStaysAndTickingDoesNotEdit() = runComposeUiTest {
        val store = MemoryKeywords(listOf("solar", "wind"))
        val vm = KeywordsViewModel(KeywordsModel(store, MemorySecrets(TEST_KEY), balanceOnly(120_174)))
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                KeywordsScreen(state, vm, SnackbarHostState(), onOpenSettings = {})
            }
        }
        onNodeWithText("wind").performClick()
        onNodeWithTag("keyword-edit-input").assertDoesNotExist()
        onNodeWithContentDescription("Edit wind").performClick()
        onNodeWithTag("keyword-edit-input").assertExists()
    }

    @Test
    fun settingsRefusesABadKeyThenSavesAGoodOneAndShowsTheBalance() = runComposeUiTest {
        val secrets = MemorySecrets()
        val vm = SettingsViewModel(SettingsModel(MemorySettings(), secrets, balanceOnly(120_174)))
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                SettingsScreen(state, vm, SnackbarHostState())
            }
        }
        onNodeWithTag("key-status").assertTextContains("No key saved.")
        onNodeWithTag("balance").assertTextContains("Not known: no key is saved.")
        onNodeWithTag("privacy-note").assertExists()

        onNodeWithTag("key-input").performTextInput("sk-not-an-rfab-key-at-all")
        onNodeWithText("Save key").performClick()
        waitForIdle()
        onNodeWithText("This isn’t an rfab key: rfab keys start with rfab_.").assertExists()
        assertEquals(null, secrets.key)

        onNodeWithTag("key-input").performTextReplacement(TEST_KEY)
        onNodeWithText("Save key").performClick()
        waitForIdle()
        assertEquals(TEST_KEY, secrets.key)
        onNodeWithTag("key-status").assertTextContains("Saved key ending in wxyz")
        onNodeWithTag("balance").assertTextContains("120,174 tokens")
    }

    @Test
    fun aSavedKeyShowsOnlyItsLastFourAndTestKeyReportsIt() = runComposeUiTest {
        val vm = SettingsViewModel(SettingsModel(MemorySettings(), MemorySecrets(TEST_KEY), balanceOnly(120_174)))
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                SettingsScreen(state, vm, SnackbarHostState())
            }
        }
        onNodeWithTag("key-status").assertTextContains("Saved key ending in wxyz")
        onNodeWithTag("balance").assertTextContains("120,174 tokens")
        onNodeWithText("Test key").performClick()
        waitForIdle()
        onNodeWithTag("key-test-result").assertTextContains("The key works.")
    }

    @Test
    fun theNavigationBarReachesSettingsAndTheBalanceLineOpensIt() = runComposeUiTest {
        val clients = balanceOnly(120_174)
        val secrets = MemorySecrets(TEST_KEY)
        val keywords = KeywordsViewModel(KeywordsModel(MemoryKeywords(), secrets, clients))
        val settings = SettingsViewModel(SettingsModel(MemorySettings(), secrets, clients))
        val run = RunFixture()
        setContent { AppTheme { AppShell(keywords, settings, run.generateVm, run.presetsVm, run.historyVm, notificationsAllowed = { true }) } }
        onNodeWithText("Settings", useUnmergedTree = true).assertExists()
        onNodeWithTag("balance-line").performClick()
        waitForIdle()
        onNodeWithTag("key-status").assertTextContains("Saved key ending in wxyz")
        onNodeWithText("Keywords").performClick()
        waitForIdle()
        onNodeWithTag("balance-line").assertTextContains("Balance: 120,174 tokens")
    }

    @Test
    fun choosingAPersonaSavesIt() = runComposeUiTest {
        val store = MemorySettings()
        val vm = SettingsViewModel(SettingsModel(store, MemorySecrets(), balanceOnly(0)))
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                SettingsScreen(state, vm, SnackbarHostState())
            }
        }
        onNodeWithText("Dog Zapper").performScrollTo().performClick()
        waitForIdle()
        assertEquals(Persona.DOG_ZAPPER, store.settings.persona)
    }

    @Test
    fun choosingAThemeAndTurningAnimationsOffSavesThem() = runComposeUiTest {
        val store = MemorySettings()
        val vm = SettingsViewModel(SettingsModel(store, MemorySecrets(), balanceOnly(0)))
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                SettingsScreen(state, vm, SnackbarHostState())
            }
        }
        onNodeWithText("A twinkling starfield").assertExists()
        onNodeWithText("Drift").performScrollTo().performClick()
        onNodeWithTag("animations").performScrollTo().assertIsOn().performClick()
        waitForIdle()
        assertEquals("drift", store.settings.theme)
        assertEquals(false, store.settings.animations)
    }

    /** Thursday 29 Oct 2026, 10:00 UTC. */
    private val thursday = 1_793_268_000_000L

    private fun androidx.compose.ui.test.ComposeUiTest.reminderSettings(
        store: MemorySettings,
        armed: MutableList<Reminder>,
        notifications: Boolean = true,
        exact: Boolean = true,
    ) {
        val vm = SettingsViewModel(SettingsModel(store, MemorySecrets(), balanceOnly(0), onReminderChanged = { armed += it }))
        val platform = ReminderPlatform(askNotifications = { it(notifications) }, exactAlarmsAllowed = { exact }, nowMillis = { thursday })
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                SettingsScreen(state, vm, SnackbarHostState(), reminder = platform)
            }
        }
    }

    @Test
    fun theReminderFiresOnlyOnTheDaysPicked() = runComposeUiTest {
        val store = MemorySettings()
        val armed = mutableListOf<Reminder>()
        reminderSettings(store, armed)
        onNodeWithTag("reminder-day-monday").assertDoesNotExist()
        onNodeWithTag("reminder-switch").performScrollTo().performClick()
        waitForIdle()
        assertEquals(Reminder(on = true), store.settings.reminder)
        assertEquals(emptySet(), store.settings.reminder.days, "no day is preselected")
        onNodeWithTag("reminder-next").performScrollTo().assertTextContains("Pick at least one day: no reminder is set.")
        onNodeWithTag("reminder-day-thursday").performScrollTo().performClick()
        waitForIdle()
        assertEquals(setOf(DayOfWeek.THURSDAY), store.settings.reminder.days)
        assertEquals(Reminder(on = true, days = setOf(DayOfWeek.THURSDAY)), armed.last(), "the alarm is re-armed")
        // Thursday 29 Oct 10:00 UTC: today's 08:00 is past, so next Thursday.
        onNodeWithTag("reminder-next").performScrollTo().assertTextContains("Next reminder: Thu 5 Nov, 08:00")
        onNodeWithText("Reminders may come late", substring = true).assertDoesNotExist()
    }

    @Test
    fun withoutNotificationsTheReminderStaysOff() = runComposeUiTest {
        val store = MemorySettings()
        val armed = mutableListOf<Reminder>()
        reminderSettings(store, armed, notifications = false)
        onNodeWithTag("reminder-switch").performScrollTo().performClick()
        waitForIdle()
        assertEquals(Reminder(), store.settings.reminder)
        assertEquals(emptyList(), armed)
    }

    @Test
    fun withoutTheAlarmPermissionSettingsSaysRemindersMayBeLate() = runComposeUiTest {
        reminderSettings(MemorySettings(AppSettings(reminder = Reminder(on = true, days = setOf(DayOfWeek.MONDAY)))), mutableListOf(), exact = false)
        onNodeWithText("Reminders may come late while the phone sleeps.").performScrollTo().assertExists()
        onNodeWithText("Allow exact reminders").assertExists()
    }
}

class GroupThousandsTest {
    @Test
    fun groupsByThree() {
        assertEquals("0", groupThousands(0))
        assertEquals("999", groupThousands(999))
        assertEquals("1,440", groupThousands(1_440))
        assertEquals("120,174", groupThousands(120_174))
        assertEquals("-1,000,000", groupThousands(-1_000_000))
    }
}
