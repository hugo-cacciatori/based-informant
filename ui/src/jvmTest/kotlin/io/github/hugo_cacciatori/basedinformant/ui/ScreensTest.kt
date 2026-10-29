package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
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
        onNodeWithContentDescription("Delete solar").performClick()
        waitForIdle()
        assertEquals(listOf("wind"), store.keywords)
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
        setContent { AppTheme { AppShell(keywords, settings, run.generateVm, run.presetsVm, notificationsAllowed = { true }) } }
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
