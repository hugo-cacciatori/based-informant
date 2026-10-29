package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hugo_cacciatori.basedinformant.core.key.SecretStore
import io.github.hugo_cacciatori.basedinformant.core.check.CheckOutcome
import io.github.hugo_cacciatori.basedinformant.core.reminder.Reminder
import io.github.hugo_cacciatori.basedinformant.core.render.RenderedDigest
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.run.CallLog
import io.github.hugo_cacciatori.basedinformant.core.run.ConfirmationRules
import io.github.hugo_cacciatori.basedinformant.core.run.DigestStore
import io.github.hugo_cacciatori.basedinformant.core.run.GenerateModel
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryModel
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryStore
import io.github.hugo_cacciatori.basedinformant.core.run.RunController
import io.github.hugo_cacciatori.basedinformant.core.run.RunJob
import io.github.hugo_cacciatori.basedinformant.core.run.RunStore
import io.github.hugo_cacciatori.basedinformant.core.settings.AppSettings
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import io.github.hugo_cacciatori.basedinformant.core.settings.PresetStore
import io.github.hugo_cacciatori.basedinformant.core.settings.PresetsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordStore
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsStore
import io.github.hugo_cacciatori.basedinformant.core.theme.BuiltInThemes
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.app_name
import io.github.hugo_cacciatori.basedinformant.ui.resources.history_missing
import io.github.hugo_cacciatori.basedinformant.ui.resources.share_no_mail_app
import io.github.hugo_cacciatori.basedinformant.ui.resources.settings
import io.github.hugo_cacciatori.basedinformant.ui.resources.tab_history
import io.github.hugo_cacciatori.basedinformant.ui.resources.tab_keywords
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** What each host (Android `:app`, later the Windows app) hands the UI; injection by hand (04-platforms.md section 1). */
class AppDependencies(
    val keywordStore: KeywordStore,
    val settingsStore: SettingsStore,
    val secrets: SecretStore,
    /** Makes an rfab client for a key; the UI only uses its free balance call. */
    val clientFor: (String) -> RfabClient,
    val presetStore: PresetStore,
    val runStore: RunStore,
    val digestStore: DigestStore,
    val historyStore: HistoryStore,
    val callLog: CallLog,
    /** The free input check with a key: `InputChecker` on a client that never retries. */
    val inputCheck: suspend (key: String, keywords: List<String>, tags: List<String>) -> CheckOutcome,
    val runController: RunController,
    /** Claims the runner for a job and starts the platform's background-safe host; false when nothing started. */
    val startRun: (RunJob) -> Boolean,
    val notificationsAllowed: () -> Boolean,
    /** Share → Email: opens the device's mail app with the mail filled in; false when there is none. */
    val shareEmail: (RenderedDigest) -> Boolean,
    /** Re-arms the reminder's alarm (04-platforms.md section 3). */
    val onReminderChanged: (Reminder) -> Unit,
    val exactAlarmsAllowed: () -> Boolean,
    val openExactAlarmSettings: () -> Unit,
    val today: () -> LocalDate,
    val now: () -> Long,
)

/** The three screens of the navigation bar (01-product.md section 3). */
enum class Screen { KEYWORDS, HISTORY, SETTINGS }

/** Pages the app opens in the browser. */
object Links {
    const val GET_KEY = "https://rfab.ai/api"
    const val CLAIM_TOKENS = "https://rfab.ai/tokens/dashboard"
}

@Composable
fun App(
    deps: AppDependencies,
    askNotifications: (onResult: (Boolean) -> Unit) -> Unit = { it(true) },
    /** A digest to open, from a new-digest notification; [onDigestOpened] clears it. */
    openDigest: StateFlow<Long?> = MutableStateFlow(null),
    onDigestOpened: () -> Unit = {},
) {
    val personaName: suspend (Persona) -> String? = { persona -> if (persona == Persona.OFF) null else getString(personaRes(persona)) }
    val history = viewModel { HistoryViewModel(HistoryModel(deps.historyStore, personaName)) }
    val keywords = viewModel { KeywordsViewModel(KeywordsModel(deps.keywordStore, deps.secrets, deps.clientFor)) }
    val settings = viewModel {
        SettingsViewModel(SettingsModel(deps.settingsStore, deps.secrets, deps.clientFor, onReminderChanged = deps.onReminderChanged))
    }
    val reminder = ReminderPlatform(askNotifications, deps.exactAlarmsAllowed, deps.openExactAlarmSettings, deps.now, { TimeZone.currentSystemDefault() })
    val presets = viewModel { PresetsViewModel(PresetsModel(deps.presetStore, keywords.model)) }
    val generate = viewModel {
        GenerateViewModel(
            GenerateModel(
                keywords = { keywords.model.state.value.keywords },
                settings = deps.settingsStore,
                secrets = deps.secrets,
                clientFor = deps.clientFor,
                inputCheck = deps.inputCheck,
                runs = deps.runStore,
                digests = deps.digestStore,
                calls = deps.callLog,
                start = deps.startRun,
                personaName = personaName,
                now = deps.now,
                today = deps.today,
            ),
            deps.runController,
            deps.startRun,
            keywords.model,
            recentCharges = {
                val balance = deps.secrets.rfabKey()?.let { (deps.clientFor(it).balance() as? RfabResult.Ok)?.value }
                balance?.let { ConfirmationRules.recentCharges(it, deps.now()) }.orEmpty()
            },
            askNotifications = { askNotifications {} },
        )
    }
    // The look: the stored settings at start, then Settings' own state once it has loaded them.
    val stored by produceState<AppSettings?>(null) { value = deps.settingsStore.load() }
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val look = if (settingsState.loaded) settingsState.settings else stored ?: AppSettings()
    AppTheme(BuiltInThemes.pack(look.theme), look.animations) {
        val requested by openDigest.collectAsStateWithLifecycle()
        AppShell(keywords, settings, generate, presets, history, deps.notificationsAllowed, requested, onDigestOpened, deps.shareEmail, reminder)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    keywords: KeywordsViewModel,
    settings: SettingsViewModel,
    generate: GenerateViewModel,
    presets: PresetsViewModel,
    history: HistoryViewModel,
    notificationsAllowed: () -> Boolean,
    openDigest: Long? = null,
    onDigestOpened: () -> Unit = {},
    shareEmail: (RenderedDigest) -> Boolean = { false },
    reminder: ReminderPlatform = ReminderPlatform(),
) {
    var screen by rememberSaveable { mutableStateOf(Screen.KEYWORDS) }
    val snackbar = remember { SnackbarHostState() }

    fun show(target: Screen) {
        if (target == screen) return
        screen = target
        // Each time the main screen opens: a key may have changed in Settings, and the balance is read again.
        if (target == Screen.KEYWORDS) {
            keywords.reload()
            generate.reload()
        }
    }

    // View in a run's outcome, or a new-digest notification: History, with that digest open.
    val scope = rememberCoroutineScope()

    // Share → Email, from the viewer or a run's outcome (01-product.md section 8).
    fun shareByEmail(id: Long) {
        scope.launch {
            val mail = history.mail(id)
            when {
                mail == null -> snackbar.showSnackbar(getString(Res.string.history_missing))
                !shareEmail(mail) -> snackbar.showSnackbar(getString(Res.string.share_no_mail_app))
            }
        }
    }

    fun view(id: Long) {
        screen = Screen.HISTORY
        history.open(id)
    }
    LaunchedEffect(openDigest) {
        openDigest?.let(::view)
        if (openDigest != null) onDigestOpened()
    }

    // Back from History or Settings returns to the main screen, as Material 3 navigation does.
    PlatformBackHandler(enabled = screen != Screen.KEYWORDS) { show(Screen.KEYWORDS) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(if (screen == Screen.SETTINGS) Res.string.settings else Res.string.app_name))
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavItem(Screen.KEYWORDS, screen, ::show, Icons.AutoMirrored.Filled.List, Res.string.tab_keywords)
                NavItem(Screen.HISTORY, screen, ::show, Icons.Filled.DateRange, Res.string.tab_history)
                NavItem(Screen.SETTINGS, screen, ::show, Icons.Filled.Settings, Res.string.settings)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // The theme's background shows around and through the screens (01-product.md section 10).
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { padding ->
        // Text sits on the theme's surface, never straight on the background.
        val modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surface)
        when (screen) {
            Screen.KEYWORDS -> {
                val state by keywords.state.collectAsStateWithLifecycle()
                val generateState by generate.state.collectAsStateWithLifecycle()
                val progress by generate.progress.collectAsStateWithLifecycle()
                val charges by generate.charges.collectAsStateWithLifecycle()
                val notStarted by generate.notStarted.collectAsStateWithLifecycle()
                val presetsState by presets.state.collectAsStateWithLifecycle()
                val run = RunUi(
                    generate = generateState,
                    progress = progress,
                    charges = charges,
                    notStarted = notStarted,
                    notificationsAllowed = notificationsAllowed(),
                    presets = presetsState,
                    actions = generate,
                    presetActions = presets,
                    onView = ::view,
                    onShareEmail = ::shareByEmail,
                )
                KeywordsScreen(state, keywords, snackbar, onOpenSettings = { show(Screen.SETTINGS) }, modifier = modifier, run = run)
            }
            Screen.HISTORY -> {
                val state by history.state.collectAsStateWithLifecycle()
                HistoryScreen(state, history, snackbar, modifier, onShareEmail = ::shareByEmail)
            }
            Screen.SETTINGS -> {
                val state by settings.state.collectAsStateWithLifecycle()
                SettingsScreen(state, settings, snackbar, modifier, reminder)
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    target: Screen,
    current: Screen,
    onSelect: (Screen) -> Unit,
    icon: ImageVector,
    label: StringResource,
) {
    NavigationBarItem(
        selected = current == target,
        onClick = { onSelect(target) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(label)) },
    )
}
