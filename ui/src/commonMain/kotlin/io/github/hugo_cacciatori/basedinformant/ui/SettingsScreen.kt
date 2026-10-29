package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.hugo_cacciatori.basedinformant.core.key.BalanceDisplay
import io.github.hugo_cacciatori.basedinformant.core.key.KeyShape
import io.github.hugo_cacciatori.basedinformant.core.key.KeyTestResult
import io.github.hugo_cacciatori.basedinformant.core.key.NotKnownReason
import io.github.hugo_cacciatori.basedinformant.core.limits.FieldCounter
import io.github.hugo_cacciatori.basedinformant.core.limits.InputLimits
import io.github.hugo_cacciatori.basedinformant.core.prompt.TemplateConfig
import io.github.hugo_cacciatori.basedinformant.core.reminder.Reminder
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import io.github.hugo_cacciatori.basedinformant.core.theme.BuiltInThemes
import io.github.hugo_cacciatori.basedinformant.ui.resources.animations_hint
import io.github.hugo_cacciatori.basedinformant.ui.resources.animations_switch
import io.github.hugo_cacciatori.basedinformant.ui.resources.theme_drift
import io.github.hugo_cacciatori.basedinformant.ui.resources.theme_drift_hint
import io.github.hugo_cacciatori.basedinformant.ui.resources.theme_night
import io.github.hugo_cacciatori.basedinformant.ui.resources.theme_night_hint
import io.github.hugo_cacciatori.basedinformant.ui.resources.theme_plain
import io.github.hugo_cacciatori.basedinformant.ui.resources.theme_plain_hint
import io.github.hugo_cacciatori.basedinformant.ui.resources.theme_section
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsState
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.attention_balanced
import io.github.hugo_cacciatori.basedinformant.ui.resources.attention_balanced_hint
import io.github.hugo_cacciatori.basedinformant.ui.resources.attention_hottest
import io.github.hugo_cacciatori.basedinformant.ui.resources.attention_hottest_hint
import io.github.hugo_cacciatori.basedinformant.ui.resources.attention_niche
import io.github.hugo_cacciatori.basedinformant.ui.resources.attention_niche_hint
import io.github.hugo_cacciatori.basedinformant.ui.resources.attention_section
import io.github.hugo_cacciatori.basedinformant.ui.resources.balance_claim
import io.github.hugo_cacciatori.basedinformant.ui.resources.balance_known
import io.github.hugo_cacciatori.basedinformant.ui.resources.balance_not_known
import io.github.hugo_cacciatori.basedinformant.ui.resources.balance_reading
import io.github.hugo_cacciatori.basedinformant.ui.resources.balance_refresh
import io.github.hugo_cacciatori.basedinformant.ui.resources.balance_section
import io.github.hugo_cacciatori.basedinformant.ui.resources.cancel
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_get
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_none
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_paste_label
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_remove
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_remove_body
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_remove_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_replace_label
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_save
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_saved
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_section
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_shape_bad_characters
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_shape_empty
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_shape_not_rfab
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_shape_too_short
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_test
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_test_failed
import io.github.hugo_cacciatori.basedinformant.ui.resources.key_test_works
import io.github.hugo_cacciatori.basedinformant.ui.resources.model_hint
import io.github.hugo_cacciatori.basedinformant.ui.resources.model_section
import io.github.hugo_cacciatori.basedinformant.ui.resources.persona_doctor_wife
import io.github.hugo_cacciatori.basedinformant.ui.resources.persona_dog_zapper
import io.github.hugo_cacciatori.basedinformant.ui.resources.persona_off
import io.github.hugo_cacciatori.basedinformant.ui.resources.persona_roach_king
import io.github.hugo_cacciatori.basedinformant.ui.resources.persona_section
import io.github.hugo_cacciatori.basedinformant.ui.resources.privacy_note
import io.github.hugo_cacciatori.basedinformant.ui.resources.privacy_section
import io.github.hugo_cacciatori.basedinformant.ui.resources.reason_key_refused
import io.github.hugo_cacciatori.basedinformant.ui.resources.reason_network
import io.github.hugo_cacciatori.basedinformant.ui.resources.reason_no_key
import io.github.hugo_cacciatori.basedinformant.ui.resources.reason_other
import io.github.hugo_cacciatori.basedinformant.ui.resources.reason_unavailable
import io.github.hugo_cacciatori.basedinformant.ui.resources.keyword_add
import io.github.hugo_cacciatori.basedinformant.ui.resources.tag_add_label
import io.github.hugo_cacciatori.basedinformant.ui.resources.tag_deleted
import io.github.hugo_cacciatori.basedinformant.ui.resources.tag_remove
import io.github.hugo_cacciatori.basedinformant.ui.resources.tags_section
import io.github.hugo_cacciatori.basedinformant.ui.resources.undo
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** What Settings can ask for; [SettingsViewModel] does it, tests use a fake. */
interface SettingsActions {
    fun open()
    fun refreshBalance()
    fun saveKey(pasted: String, onDone: () -> Unit)
    fun testKey()
    fun removeKey()
    fun addTag(text: String, onDone: () -> Unit)
    fun deleteTag(index: Int)
    fun undoTag()
    fun dismissTagUndo()
    fun setAttentionMode(mode: AttentionMode)
    fun setPersona(persona: Persona)
    fun setTheme(id: String)
    fun setAnimations(on: Boolean)
    fun setModel(id: String)
    fun setReminder(reminder: Reminder)
}

/**
 * Settings (01-product.md section 3): the key (paste + Test key only), the balance, what feeds the call, the reminder,
 * the look and the privacy note. Every change is saved at once.
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    reminder: ReminderPlatform = ReminderPlatform(),
) {
    LaunchedEffect(Unit) { actions.open() }

    val tagUndo = state.tagUndo
    LaunchedEffect(tagUndo) {
        if (tagUndo == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = getString(Res.string.tag_deleted, tagUndo.text),
            actionLabel = getString(Res.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) actions.undoTag() else actions.dismissTagUndo()
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KeySection(state, actions)
        HorizontalDivider()
        BalanceSection(state.balance, actions)
        HorizontalDivider()
        if (state.loaded) {
            TagsSection(state, actions)
            HorizontalDivider()
        }
        Choice(
            title = Res.string.attention_section,
            options = AttentionMode.entries,
            selected = state.settings.attentionMode,
            label = { attentionLabel(it) },
            hint = { attentionHint(it) },
            onSelect = actions::setAttentionMode,
        )
        HorizontalDivider()
        Choice(
            title = Res.string.persona_section,
            options = Persona.entries,
            selected = state.settings.persona,
            label = { personaLabel(it) },
            onSelect = actions::setPersona,
        )
        HorizontalDivider()
        Choice(
            title = Res.string.model_section,
            options = TemplateConfig.ALLOWED_MODELS,
            selected = state.settings.model,
            label = { it },
            hint = { stringResource(Res.string.model_hint) },
            onSelect = actions::setModel,
        )
        HorizontalDivider()
        if (state.loaded) {
            ReminderSection(state.settings.reminder, actions::setReminder, reminder, snackbar)
            HorizontalDivider()
        }
        Choice(
            title = Res.string.theme_section,
            options = BuiltInThemes.IDS,
            selected = state.settings.theme,
            label = { stringResource(themeRes(it).first) },
            hint = { stringResource(themeRes(it).second) },
            onSelect = actions::setTheme,
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.animations_switch))
                Text(stringResource(Res.string.animations_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = state.settings.animations,
                onCheckedChange = actions::setAnimations,
                modifier = Modifier.testTag("animations"),
            )
        }
        HorizontalDivider()
        SectionTitle(Res.string.privacy_section)
        Text(stringResource(Res.string.privacy_note), Modifier.testTag("privacy-note"))
    }
}

@Composable
private fun KeySection(state: SettingsState, actions: SettingsActions) {
    val uri = LocalUriHandler.current
    var pasted by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    val save = { actions.saveKey(pasted) { pasted = "" } }

    SectionTitle(Res.string.key_section)
    val lastFour = state.keyLastFour
    Text(
        if (lastFour != null) stringResource(Res.string.key_saved, lastFour) else stringResource(Res.string.key_none),
        Modifier.testTag("key-status"),
    )
    if (lastFour != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = actions::testKey, Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.key_test)) }
            OutlinedButton(onClick = { confirmRemove = true }, Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.key_remove))
            }
        }
    }
    state.keyTest?.let { Text(keyTestText(it), Modifier.testTag("key-test-result")) }

    // The key is never shown: the field masks it and is emptied once the key is saved.
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        label = { Text(stringResource(if (lastFour == null) Res.string.key_paste_label else Res.string.key_replace_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        isError = state.keyShapeRefusal != null,
        supportingText = state.keyShapeRefusal?.let { shape -> { Text(keyShapeText(shape)) } },
        modifier = Modifier.fillMaxWidth().testTag("key-input"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = save, enabled = pasted.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.key_save))
        }
        TextButton(onClick = { uri.openUri(Links.GET_KEY) }) { Text(stringResource(Res.string.key_get)) }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(Res.string.key_remove_title)) },
            text = { Text(stringResource(Res.string.key_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    actions.removeKey()
                }) { Text(stringResource(Res.string.key_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun BalanceSection(balance: BalanceDisplay?, actions: SettingsActions) {
    val uri = LocalUriHandler.current
    SectionTitle(Res.string.balance_section)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (balance) {
                null -> stringResource(Res.string.balance_reading)
                is BalanceDisplay.Known -> stringResource(Res.string.balance_known, groupThousands(balance.tokens))
                is BalanceDisplay.NotKnown -> stringResource(Res.string.balance_not_known, reasonText(balance.reason))
            },
            Modifier.weight(1f).testTag("balance"),
        )
        TextButton(onClick = actions::refreshBalance, enabled = balance != null) { Text(stringResource(Res.string.balance_refresh)) }
    }
    TextButton(onClick = { uri.openUri(Links.CLAIM_TOKENS) }) { Text(stringResource(Res.string.balance_claim)) }
}

@Composable
private fun TagsSection(state: SettingsState, actions: SettingsActions) {
    var text by rememberSaveable { mutableStateOf("") }
    val counter = FieldCounter.tag(text)
    val add = { actions.addTag(text) { text = "" } }
    SectionTitle(Res.string.tags_section)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.settings.tags.forEachIndexed { index, tag ->
            InputChip(
                selected = false,
                onClick = { actions.deleteTag(index) },
                label = { Text(tag) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.tag_remove, tag)) },
            )
        }
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.tag_add_label)) },
            singleLine = true,
            isError = state.tagRefusal != null || !counter.canSave,
            supportingText = { FieldSupport(counter, state.tagRefusal?.let { refusalText(it, ListKind.TAGS) }) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f).testTag("tag-input"),
        )
        FilledTonalButton(onClick = add, enabled = text.isNotBlank(), modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.keyword_add))
        }
    }
}

/** A single choice as radio rows, each a 48 dp touch target read as one radio button. */
@Composable
private fun <T> Choice(
    title: StringResource,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    hint: (@Composable (T) -> String)? = null,
    onSelect: (T) -> Unit,
) {
    SectionTitle(title)
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(label(option))
                    hint?.let { Text(it(option), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

/** A built-in theme's name and mood; every id in [BuiltInThemes.IDS] has both. */
private fun themeRes(id: String): Pair<StringResource, StringResource> = when (id) {
    "night" -> Res.string.theme_night to Res.string.theme_night_hint
    "drift" -> Res.string.theme_drift to Res.string.theme_drift_hint
    else -> Res.string.theme_plain to Res.string.theme_plain_hint
}

@Composable
private fun SectionTitle(title: StringResource) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun attentionLabel(mode: AttentionMode) = stringResource(
    when (mode) {
        AttentionMode.HOTTEST -> Res.string.attention_hottest
        AttentionMode.BALANCED -> Res.string.attention_balanced
        AttentionMode.NICHE -> Res.string.attention_niche
    },
)

@Composable
private fun attentionHint(mode: AttentionMode) = stringResource(
    when (mode) {
        AttentionMode.HOTTEST -> Res.string.attention_hottest_hint
        AttentionMode.BALANCED -> Res.string.attention_balanced_hint
        AttentionMode.NICHE -> Res.string.attention_niche_hint
    },
)

@Composable
internal fun personaLabel(persona: Persona) = stringResource(personaRes(persona))

/** Also the name in a digest's header (RunSnapshot.personaDisplayName). */
internal fun personaRes(persona: Persona) = when (persona) {
    Persona.OFF -> Res.string.persona_off
    Persona.ROACH_KING -> Res.string.persona_roach_king
    Persona.DOCTOR_WIFE -> Res.string.persona_doctor_wife
    Persona.DOG_ZAPPER -> Res.string.persona_dog_zapper
}

@Composable
private fun keyShapeText(shape: KeyShape) = stringResource(
    when (shape) {
        KeyShape.OK, KeyShape.EMPTY -> Res.string.key_shape_empty
        KeyShape.NOT_RFAB -> Res.string.key_shape_not_rfab
        KeyShape.BAD_CHARACTERS -> Res.string.key_shape_bad_characters
        KeyShape.TOO_SHORT -> Res.string.key_shape_too_short
    },
)

@Composable
private fun keyTestText(result: KeyTestResult) = when (result) {
    is KeyTestResult.Works -> stringResource(Res.string.key_test_works)
    is KeyTestResult.BadShape -> keyShapeText(result.shape)
    is KeyTestResult.Failed -> stringResource(Res.string.key_test_failed, reasonText(result.reason))
}

/** Why the balance is not known, in words; also used by the balance line on the main screen. */
@Composable
internal fun reasonText(reason: NotKnownReason) = stringResource(
    when (reason) {
        NotKnownReason.NO_KEY -> Res.string.reason_no_key
        NotKnownReason.KEY_REFUSED -> Res.string.reason_key_refused
        NotKnownReason.UNAVAILABLE -> Res.string.reason_unavailable
        NotKnownReason.NETWORK -> Res.string.reason_network
        NotKnownReason.OTHER -> Res.string.reason_other
    },
)
