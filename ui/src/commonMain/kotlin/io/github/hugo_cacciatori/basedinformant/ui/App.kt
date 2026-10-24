package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hugo_cacciatori.basedinformant.core.key.SecretStore
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordStore
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsStore
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.app_name
import io.github.hugo_cacciatori.basedinformant.ui.resources.settings
import io.github.hugo_cacciatori.basedinformant.ui.resources.tab_history
import io.github.hugo_cacciatori.basedinformant.ui.resources.tab_keywords
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** What each host (Android `:app`, later the Windows app) hands the UI; injection by hand (04-platforms.md section 1). */
class AppDependencies(
    val keywordStore: KeywordStore,
    val settingsStore: SettingsStore,
    val secrets: SecretStore,
    /** Makes an rfab client for a key; the UI only uses its free balance call. */
    val clientFor: (String) -> RfabClient,
)

/** The three screens of the navigation bar (01-product.md section 3). */
enum class Screen { KEYWORDS, HISTORY, SETTINGS }

/** Pages the app opens in the browser. */
object Links {
    const val GET_KEY = "https://rfab.ai/api"
    const val CLAIM_TOKENS = "https://rfab.ai/tokens/dashboard"
}

@Composable
fun App(deps: AppDependencies) {
    val keywords = viewModel { KeywordsViewModel(KeywordsModel(deps.keywordStore, deps.secrets, deps.clientFor)) }
    val settings = viewModel { SettingsViewModel(SettingsModel(deps.settingsStore, deps.secrets, deps.clientFor)) }
    AppTheme {
        AppShell(keywords, settings)
    }
}

/** Material 3's default colors, following the system's light or dark mode, until the theme engine (stage 8). */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(keywords: KeywordsViewModel, settings: SettingsViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.KEYWORDS) }
    val snackbar = remember { SnackbarHostState() }

    fun show(target: Screen) {
        if (target == screen) return
        screen = target
        // Each time the main screen opens: a key may have changed in Settings, and the balance is read again.
        if (target == Screen.KEYWORDS) keywords.reload()
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
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (screen) {
            Screen.KEYWORDS -> {
                val state by keywords.state.collectAsStateWithLifecycle()
                KeywordsScreen(state, keywords, snackbar, onOpenSettings = { show(Screen.SETTINGS) }, modifier = modifier)
            }
            Screen.HISTORY -> HistoryScreen(modifier)
            Screen.SETTINGS -> {
                val state by settings.state.collectAsStateWithLifecycle()
                SettingsScreen(state, settings, snackbar, modifier)
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
