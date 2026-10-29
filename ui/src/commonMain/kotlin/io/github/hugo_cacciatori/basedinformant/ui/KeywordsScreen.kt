package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.hugo_cacciatori.basedinformant.core.key.BalanceDisplay
import io.github.hugo_cacciatori.basedinformant.core.limits.FieldCounter
import io.github.hugo_cacciatori.basedinformant.core.limits.InputLimits
import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules
import io.github.hugo_cacciatori.basedinformant.core.prompt.DigestMode
import io.github.hugo_cacciatori.basedinformant.core.run.GenerateStep
import io.github.hugo_cacciatori.basedinformant.core.run.RunProgress
import io.github.hugo_cacciatori.basedinformant.core.settings.GenerateBlock
import io.github.hugo_cacciatori.basedinformant.core.settings.GenerateRules
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordsState
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.balance_line_known
import io.github.hugo_cacciatori.basedinformant.ui.resources.balance_line_not_known
import io.github.hugo_cacciatori.basedinformant.ui.resources.balance_reading
import io.github.hugo_cacciatori.basedinformant.ui.resources.cancel
import io.github.hugo_cacciatori.basedinformant.ui.resources.first_run_body
import io.github.hugo_cacciatori.basedinformant.ui.resources.first_run_get_key
import io.github.hugo_cacciatori.basedinformant.ui.resources.first_run_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.generate
import io.github.hugo_cacciatori.basedinformant.ui.resources.generate_block_no_key
import io.github.hugo_cacciatori.basedinformant.ui.resources.generate_block_too_few_keywords
import io.github.hugo_cacciatori.basedinformant.ui.resources.generate_block_run_active
import io.github.hugo_cacciatori.basedinformant.ui.resources.generate_preparing
import io.github.hugo_cacciatori.basedinformant.ui.resources.preset_loaded
import io.github.hugo_cacciatori.basedinformant.ui.resources.presets
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_add
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_add_label
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_count
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_deleted
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_edit
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_edit_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.keywords_delete_ticked
import io.github.hugo_cacciatori.basedinformant.ui.resources.keywords_deleted
import io.github.hugo_cacciatori.basedinformant.ui.resources.keywords_empty
import io.github.hugo_cacciatori.basedinformant.ui.resources.keywords_select_all
import io.github.hugo_cacciatori.basedinformant.ui.resources.topic_deleted
import io.github.hugo_cacciatori.basedinformant.ui.resources.topics_deleted
import io.github.hugo_cacciatori.basedinformant.ui.resources.open_settings
import io.github.hugo_cacciatori.basedinformant.ui.resources.save
import io.github.hugo_cacciatori.basedinformant.ui.resources.undo
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** What the Keywords screen can ask for; [KeywordsViewModel] does it, tests use a fake. */
interface KeywordsActions {
    fun add(text: String, onDone: () -> Unit)
    fun edit(index: Int, text: String, onDone: () -> Unit)
    fun delete(indices: Set<Int>)
    fun undo()
    fun dismissUndo()
    fun dismissRefusal()
}

/**
 * The main screen (01-product.md section 3): the Keywords | Topics switch, the keyword list with its presets or the
 * topic picker, and Generate with its dialogs; while a job runs, its progress instead of Generate.
 */
@Composable
fun KeywordsScreen(
    state: KeywordsState,
    actions: KeywordsActions,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    run: RunUi = RunUi(),
) {
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var presetsOpen by rememberSaveable { mutableStateOf(false) }
    // The ticked keywords, by their lowercase text (unique in the list), so a change elsewhere never shifts them.
    var ticked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val tickedIndices = state.keywords.indices.filter { state.keywords[it].lowercase() in ticked }.toSet()
    val mode = run.generate.mode

    // Deleted topics: Undo puts them back for as long as the snackbar shows.
    val topicUndo = run.generate.topicUndo
    LaunchedEffect(topicUndo) {
        if (topicUndo == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = if (topicUndo.size == 1) getString(Res.string.topic_deleted, topicUndo.single().tag)
            else getString(Res.string.topics_deleted, topicUndo.size),
            actionLabel = getString(Res.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) run.actions.undoTopics() else run.actions.dismissTopicUndo()
    }
    // After a delete or a loaded preset, Undo brings the previous list back for as long as the snackbar shows (a few
    // seconds), or until the next edit.
    val restore = state.restore
    LaunchedEffect(restore) {
        if (restore == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = when {
                restore.presetLoaded -> getString(Res.string.preset_loaded)
                restore.deleted.size == 1 -> getString(Res.string.keyword_deleted, restore.deleted.single())
                else -> getString(Res.string.keywords_deleted, restore.deleted.size)
            },
            actionLabel = getString(Res.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) actions.undo() else actions.dismissUndo()
    }

    Column(modifier.fillMaxSize()) {
        ModeSwitch(run.generate, run.actions, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        if (mode == DigestMode.TOPICS) TopicPicker(run.generate, run.actions, Modifier.weight(1f).fillMaxWidth())
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            if (state.loaded && !state.hasKey) {
                item { FirstRunCard(onOpenSettings, Modifier.padding(bottom = 16.dp)) }
            }
            item {
                // While the edit dialog is open, a refusal belongs to it.
                AddKeyword(state.refusal.takeIf { editing == null }, actions)
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    val selectAll = stringResource(Res.string.keywords_select_all)
                    TriStateCheckbox(
                        state = when {
                            tickedIndices.isEmpty() -> ToggleableState.Off
                            tickedIndices.size == state.keywords.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        },
                        onClick = {
                            ticked = if (tickedIndices.size == state.keywords.size) emptyList()
                            else state.keywords.map { it.lowercase() }
                        },
                        enabled = state.keywords.isNotEmpty(),
                        modifier = Modifier.semantics { contentDescription = selectAll }.testTag("select-all"),
                    )
                    Text(
                        stringResource(Res.string.keyword_count, state.keywords.size, InputLimits.KEYWORDS_MAX),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                    if (tickedIndices.isNotEmpty()) {
                        TextButton(
                            onClick = { actions.delete(tickedIndices); ticked = emptyList() },
                            modifier = Modifier.testTag("delete-ticked"),
                        ) { Text(stringResource(Res.string.keywords_delete_ticked, tickedIndices.size)) }
                    } else {
                        TextButton(
                            onClick = { run.presetActions.open(); presetsOpen = true },
                            modifier = Modifier.testTag("presets-open"),
                        ) { Text(stringResource(Res.string.presets)) }
                    }
                }
            }
            if (state.loaded && state.keywords.isEmpty()) {
                item { Text(stringResource(Res.string.keywords_empty), Modifier.padding(vertical = 8.dp)) }
            }
            itemsIndexed(state.keywords, key = { _, keyword -> keyword.lowercase() }) { index, keyword ->
                val key = keyword.lowercase()
                KeywordRow(
                    keyword,
                    ticked = key in ticked,
                    onTick = { on -> ticked = if (on) ticked + key else ticked - key },
                    onEdit = { editing = index },
                )
                HorizontalDivider()
            }
        }
        if (run.progress is RunProgress.Running) {
            ProgressCard(run.notificationsAllowed)
        } else {
            val blocks = if (mode == DigestMode.TOPICS) {
                GenerateRules.topicBlocks(state.hasKey, state.runActive)
            } else {
                state.generateBlocks
            }
            GenerateBar(
                state = state,
                blocks = blocks,
                preparing = run.generate.step == GenerateStep.Checking,
                onGenerate = run.actions::generate,
                onOpenSettings = onOpenSettings,
            )
        }
    }

    RunDialogs(run)
    if (presetsOpen) {
        PresetsDialog(
            run.presets,
            run.presetActions,
            canSave = state.keywords.isNotEmpty(),
            onClose = { presetsOpen = false; run.presetActions.dismissRefusal() },
        )
    }

    editing?.let { index ->
        val keyword = state.keywords.getOrNull(index)
        if (keyword == null) {
            editing = null
        } else {
            EditKeywordDialog(
                keyword = keyword,
                refusal = state.refusal,
                onSave = { text -> actions.edit(index, text) { editing = null } },
                onDismiss = {
                    editing = null
                    actions.dismissRefusal()
                },
            )
        }
    }
}

@Composable
private fun FirstRunCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    Card(modifier.fillMaxWidth().testTag("first-run")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.first_run_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.first_run_body))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uri.openUri(Links.GET_KEY) }) { Text(stringResource(Res.string.first_run_get_key)) }
                Button(onClick = onOpenSettings) { Text(stringResource(Res.string.open_settings)) }
            }
        }
    }
}

@Composable
private fun AddKeyword(refusal: TextListRules.Refusal?, actions: KeywordsActions) {
    var text by rememberSaveable { mutableStateOf("") }
    val counter = FieldCounter.keyword(text)
    val add = { actions.add(text) { text = "" } }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                if (refusal != null) actions.dismissRefusal()
            },
            label = { Text(stringResource(Res.string.keyword_add_label)) },
            singleLine = true,
            isError = refusal != null || !counter.canSave,
            supportingText = { FieldSupport(counter, refusal?.let { refusalText(it, ListKind.KEYWORDS) }) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f).testTag("keyword-input"),
        )
        FilledTonalButton(
            onClick = add,
            enabled = text.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
        ) { Text(stringResource(Res.string.keyword_add)) }
    }
}

/** A tap on the row ticks it for Delete; the pencil edits it (01-product.md section 3). */
@Composable
private fun KeywordRow(keyword: String, ticked: Boolean, onTick: (Boolean) -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .toggleable(value = ticked, role = Role.Checkbox, onValueChange = onTick)
                .testTag("keyword-row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = ticked, onCheckedChange = null)
            Text(keyword, Modifier.padding(start = 12.dp))
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.keyword_edit, keyword))
        }
    }
}

@Composable
private fun EditKeywordDialog(
    keyword: String,
    refusal: TextListRules.Refusal?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(keyword) { mutableStateOf(keyword) }
    val counter = FieldCounter.keyword(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.keyword_edit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = refusal != null || !counter.canSave,
                supportingText = { FieldSupport(counter, refusal?.let { refusalText(it, ListKind.KEYWORDS) }) },
                modifier = Modifier.testTag("keyword-edit-input"),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(Res.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * Generate, disabled with its reason and, when there is one, the action that fixes it (01-product.md section 11).
 * Above it, once a key is saved, the balance line; tapping it opens Settings (01-product.md section 3).
 */
@Composable
private fun GenerateBar(
    state: KeywordsState,
    blocks: List<GenerateBlock>,
    preparing: Boolean,
    onGenerate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val block = blocks.firstOrNull().takeIf { state.loaded }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.loaded && state.hasKey) {
            val balance = state.balance
            Text(
                when (balance) {
                    null -> stringResource(Res.string.balance_reading)
                    is BalanceDisplay.Known -> stringResource(Res.string.balance_line_known, groupThousands(balance.tokens))
                    is BalanceDisplay.NotKnown -> stringResource(Res.string.balance_line_not_known, reasonText(balance.reason))
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSettings).padding(vertical = 12.dp)
                    .testTag("balance-line"),
            )
        }
        Button(
            onClick = onGenerate,
            enabled = state.loaded && block == null && !preparing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("generate"),
        ) { Text(stringResource(if (preparing) Res.string.generate_preparing else Res.string.generate)) }
        if (block != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (block) {
                        GenerateBlock.RUN_ACTIVE -> stringResource(Res.string.generate_block_run_active)
                        GenerateBlock.NO_KEY -> stringResource(Res.string.generate_block_no_key)
                        GenerateBlock.TOO_FEW_KEYWORDS -> stringResource(Res.string.generate_block_too_few_keywords)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).testTag("generate-reason"),
                )
                if (block == GenerateBlock.NO_KEY) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(Res.string.open_settings)) }
                }
            }
        }
    }
}
