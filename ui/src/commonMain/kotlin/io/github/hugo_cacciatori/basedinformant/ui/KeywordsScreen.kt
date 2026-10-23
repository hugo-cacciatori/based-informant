package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.hugo_cacciatori.basedinformant.core.key.BalanceDisplay
import io.github.hugo_cacciatori.basedinformant.core.limits.FieldCounter
import io.github.hugo_cacciatori.basedinformant.core.limits.InputLimits
import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules
import io.github.hugo_cacciatori.basedinformant.core.settings.GenerateBlock
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
import io.github.hugo_cacciatori.basedinformant.ui.resources.generate_not_yet
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_add
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_add_label
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_count
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_delete
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_deleted
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_edit
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_edit_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.keywords_empty
import io.github.hugo_cacciatori.basedinformant.ui.resources.open_settings
import io.github.hugo_cacciatori.basedinformant.ui.resources.save
import io.github.hugo_cacciatori.basedinformant.ui.resources.undo
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** What the Keywords screen can ask for; [KeywordsViewModel] does it, tests use a fake. */
interface KeywordsActions {
    fun add(text: String, onDone: () -> Unit)
    fun edit(index: Int, text: String, onDone: () -> Unit)
    fun delete(index: Int)
    fun undo()
    fun dismissUndo()
    fun dismissRefusal()
}

/** The main screen (product.md section 3): the keyword list and Generate. */
@Composable
fun KeywordsScreen(
    state: KeywordsState,
    actions: KeywordsActions,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }

    // The Undo offer lasts as long as the snackbar (a few seconds), or until the next edit.
    val undo = state.undo
    LaunchedEffect(undo) {
        if (undo == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = getString(Res.string.keyword_deleted, undo.text),
            actionLabel = getString(Res.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) actions.undo() else actions.dismissUndo()
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            if (state.loaded && !state.hasKey) {
                item { FirstRunCard(onOpenSettings, Modifier.padding(bottom = 16.dp)) }
            }
            item {
                // While the edit dialog is open, a refusal belongs to it.
                AddKeyword(state.refusal.takeIf { editing == null }, actions)
                Text(
                    stringResource(Res.string.keyword_count, state.keywords.size, InputLimits.KEYWORDS_MAX),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (state.loaded && state.keywords.isEmpty()) {
                item { Text(stringResource(Res.string.keywords_empty), Modifier.padding(vertical = 8.dp)) }
            }
            itemsIndexed(state.keywords, key = { _, keyword -> keyword.lowercase() }) { index, keyword ->
                KeywordRow(keyword, onEdit = { editing = index }, onDelete = { actions.delete(index) })
                HorizontalDivider()
            }
        }
        GenerateBar(
            state = state,
            onGenerate = { scope.launch { snackbar.showSnackbar(getString(Res.string.generate_not_yet)) } },
            onOpenSettings = onOpenSettings,
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

@Composable
private fun KeywordRow(keyword: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(keyword, Modifier.weight(1f))
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.keyword_edit, keyword))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.keyword_delete, keyword))
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
 * Generate, disabled with its reason and, when there is one, the action that fixes it (product.md section 11).
 * Above it, once a key is saved, the balance line; tapping it opens Settings (product.md section 3).
 */
@Composable
private fun GenerateBar(state: KeywordsState, onGenerate: () -> Unit, onOpenSettings: () -> Unit) {
    val block = state.generateBlocks.firstOrNull().takeIf { state.loaded }
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
            enabled = state.loaded && block == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("generate"),
        ) { Text(stringResource(Res.string.generate)) }
        if (block != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (block) {
                        GenerateBlock.RUN_ACTIVE -> stringResource(Res.string.generate_block_run_active)
                        GenerateBlock.NO_KEY -> stringResource(Res.string.generate_block_no_key)
                        GenerateBlock.TOO_FEW_KEYWORDS -> stringResource(
                            Res.string.generate_block_too_few_keywords,
                            InputLimits.KEYWORDS_MAX,
                            InputLimits.KEYWORDS_MAX - state.keywords.size,
                        )
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
