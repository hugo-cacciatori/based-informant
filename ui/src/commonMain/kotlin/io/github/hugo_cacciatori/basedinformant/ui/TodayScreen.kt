package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hugo_cacciatori.basedinformant.core.prompt.DigestMode
import io.github.hugo_cacciatori.basedinformant.core.run.GenerateStep
import io.github.hugo_cacciatori.basedinformant.core.run.OpenDigest
import io.github.hugo_cacciatori.basedinformant.core.run.RunProgress
import io.github.hugo_cacciatori.basedinformant.core.settings.GenerateRules
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordsState
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.inputs_done
import io.github.hugo_cacciatori.basedinformant.ui.resources.inputs_edit
import io.github.hugo_cacciatori.basedinformant.ui.resources.inputs_keywords
import io.github.hugo_cacciatori.basedinformant.ui.resources.inputs_keywords_none
import io.github.hugo_cacciatori.basedinformant.ui.resources.inputs_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.inputs_topics
import io.github.hugo_cacciatori.basedinformant.ui.resources.inputs_topics_none
import io.github.hugo_cacciatori.basedinformant.ui.resources.today_empty_body
import io.github.hugo_cacciatori.basedinformant.ui.resources.today_empty_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.today_title
import org.jetbrains.compose.resources.stringResource

/**
 * Today, the home (01-product.md section 3, owner 2026-10-30): the latest digest to read, and under it one panel that
 * says what the next digest will search, with **Edit** (keywords, topics and presets in a sheet) and **Generate**.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: KeywordsState,
    actions: KeywordsActions,
    latest: OpenDigest?,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onShareEmail: (Long) -> Unit,
    modifier: Modifier = Modifier,
    run: RunUi = RunUi(),
) {
    var inputsOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Spacer(Modifier.height(Space.s))
            Text(
                stringResource(Res.string.today_title),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("today-title"),
            )
            if (state.loaded && !state.hasKey) FirstRunCard(onOpenSettings)
            if (latest != null) {
                DigestBody(latest.view, trailing = { ShareMenu(onEmail = { onShareEmail(latest.id) }) })
            } else {
                GlassCard(Modifier.fillMaxWidth().testTag("today-empty")) {
                    Text(stringResource(Res.string.today_empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(Res.string.today_empty_body), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(Space.s))
        }
        InputsPanel(state, run, onEdit = { inputsOpen = true }, onOpenSettings)
    }

    RunDialogs(run)
    if (inputsOpen) {
        ModalBottomSheet(
            onDismissRequest = { inputsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                stringResource(Res.string.inputs_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = Space.l),
            )
            // The panel's own snackbar: Undo must be reachable above the app's (device check, 2026-10-30).
            val panelSnackbar = remember { SnackbarHostState() }
            KeywordsScreen(state, actions, panelSnackbar, onOpenSettings, Modifier.fillMaxWidth().height(520.dp), run, standalone = false)
            SnackbarHost(panelSnackbar)
            GlowButton(stringResource(Res.string.inputs_done), onClick = { inputsOpen = false }, modifier = Modifier.padding(Space.l).testTag("inputs-done"))
        }
    }
}

/** What the next digest will search, with Edit, then Generate (or the running job's progress). */
@Composable
private fun InputsPanel(state: KeywordsState, run: RunUi, onEdit: () -> Unit, onOpenSettings: () -> Unit) {
    val mode = run.generate.mode
    val summary = when {
        mode == DigestMode.TOPICS -> run.generate.chosen.joinToString(" · ").ifEmpty { stringResource(Res.string.inputs_topics_none) }
        else -> state.keywords.joinToString(" · ").ifEmpty { stringResource(Res.string.inputs_keywords_none) }
    }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = Space.xs), padding = PaddingValues(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Column(Modifier.weight(1f)) {
                SectionLabel(stringResource(if (mode == DigestMode.TOPICS) Res.string.inputs_topics else Res.string.inputs_keywords))
                Text(summary, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("inputs-summary"))
            }
            FilledTonalButton(onClick = onEdit, modifier = Modifier.testTag("inputs-open")) { Text(stringResource(Res.string.inputs_edit)) }
        }
        if (run.progress is RunProgress.Running) {
            ProgressCard(run.notificationsAllowed)
        } else {
            val blocks = if (mode == DigestMode.TOPICS) GenerateRules.topicBlocks(state.hasKey, state.runActive) else state.generateBlocks
            GenerateBar(
                state = state,
                padding = 0.dp,
                blocks = blocks,
                preparing = run.generate.step == GenerateStep.Checking,
                onGenerate = run.actions::generate,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}
