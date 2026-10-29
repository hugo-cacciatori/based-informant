@file:OptIn(ExperimentalTime::class)

package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.hugo_cacciatori.basedinformant.core.check.CheckEntry
import io.github.hugo_cacciatori.basedinformant.core.check.Refusal
import io.github.hugo_cacciatori.basedinformant.core.limits.InputLimits
import io.github.hugo_cacciatori.basedinformant.core.prompt.DigestMode
import io.github.hugo_cacciatori.basedinformant.core.rfab.Transaction
import io.github.hugo_cacciatori.basedinformant.core.run.GenerateState
import io.github.hugo_cacciatori.basedinformant.core.run.GenerateStep
import io.github.hugo_cacciatori.basedinformant.core.run.RunError
import io.github.hugo_cacciatori.basedinformant.core.run.RunJob
import io.github.hugo_cacciatori.basedinformant.core.run.RunOutcome
import io.github.hugo_cacciatori.basedinformant.core.run.RunProgress
import io.github.hugo_cacciatori.basedinformant.core.settings.Preset
import io.github.hugo_cacciatori.basedinformant.core.settings.PresetRules
import io.github.hugo_cacciatori.basedinformant.core.settings.PresetsState
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.balance_claim
import io.github.hugo_cacciatori.basedinformant.ui.resources.cancel
import io.github.hugo_cacciatori.basedinformant.ui.resources.close
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_average
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_average_unknown
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_balance
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_balance_unknown
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_chat_hours
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_chat_recent
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_go
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_keywords
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_last
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_last_none
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_low
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_settings
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_tags
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_tags_none
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_tokens
import io.github.hugo_cacciatori.basedinformant.ui.resources.confirm_topics
import io.github.hugo_cacciatori.basedinformant.ui.resources.off
import io.github.hugo_cacciatori.basedinformant.ui.resources.on
import io.github.hugo_cacciatori.basedinformant.ui.resources.delete
import io.github.hugo_cacciatori.basedinformant.ui.resources.mode_keywords
import io.github.hugo_cacciatori.basedinformant.ui.resources.mode_topics
import io.github.hugo_cacciatori.basedinformant.ui.resources.ok
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_busy
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_charge
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_charges
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_empty
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_failed_billed
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_insufficient
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_interrupted
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_key_refused
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_not_built
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_not_started
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_not_written
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_nothing
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_refused
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_stored
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_title_done
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_title_none
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_tokens
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_unavailable
import io.github.hugo_cacciatori.basedinformant.ui.resources.outcome_unreadable
import io.github.hugo_cacciatori.basedinformant.ui.resources.preset_delete
import io.github.hugo_cacciatori.basedinformant.ui.resources.preset_delete_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.preset_refused_already_saved
import io.github.hugo_cacciatori.basedinformant.ui.resources.preset_refused_full
import io.github.hugo_cacciatori.basedinformant.ui.resources.preset_refused_empty
import io.github.hugo_cacciatori.basedinformant.ui.resources.presets_empty
import io.github.hugo_cacciatori.basedinformant.ui.resources.presets_save
import io.github.hugo_cacciatori.basedinformant.ui.resources.presets_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.progress_body
import io.github.hugo_cacciatori.basedinformant.ui.resources.progress_notifications_off
import io.github.hugo_cacciatori.basedinformant.ui.resources.progress_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.read_again
import io.github.hugo_cacciatori.basedinformant.ui.resources.refused_keyword
import io.github.hugo_cacciatori.basedinformant.ui.resources.refused_no_reason
import io.github.hugo_cacciatori.basedinformant.ui.resources.refused_tag
import io.github.hugo_cacciatori.basedinformant.ui.resources.refused_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.topics_count
import io.github.hugo_cacciatori.basedinformant.ui.resources.topics_none
import io.github.hugo_cacciatori.basedinformant.ui.resources.topics_note
import io.github.hugo_cacciatori.basedinformant.ui.resources.view
import io.github.hugo_cacciatori.basedinformant.ui.resources.write_again
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.hugo_cacciatori.basedinformant.ui.resources.keywords_delete_ticked
import io.github.hugo_cacciatori.basedinformant.ui.resources.topics_select_all
import io.github.hugo_cacciatori.basedinformant.ui.resources.topic_tick

/** What Generate and the runner can be asked for; [GenerateViewModel] does it, tests use [NoGenerate]. */
interface GenerateActions {
    fun generate()
    fun confirm()
    fun dismiss()
    fun setMode(mode: DigestMode)
    fun toggleTopic(tag: String)
    fun deleteTopics(tags: Collection<String>)
    fun undoTopics()
    fun dismissTopicUndo()
    fun readAgain(runId: Long)
    fun writeAgain(runId: Long)
    fun acknowledge()
    fun dismissNotStarted()
}

interface PresetActions {
    fun open()
    fun save()
    fun use(id: Long)
    fun delete(id: Long)
    fun dismissRefusal()
}

object NoGenerate : GenerateActions {
    override fun generate() = Unit
    override fun confirm() = Unit
    override fun dismiss() = Unit
    override fun setMode(mode: DigestMode) = Unit
    override fun toggleTopic(tag: String) = Unit
    override fun deleteTopics(tags: Collection<String>) = Unit
    override fun undoTopics() = Unit
    override fun dismissTopicUndo() = Unit
    override fun readAgain(runId: Long) = Unit
    override fun writeAgain(runId: Long) = Unit
    override fun acknowledge() = Unit
    override fun dismissNotStarted() = Unit
}

object NoPresets : PresetActions {
    override fun open() = Unit
    override fun save() = Unit
    override fun use(id: Long) = Unit
    override fun delete(id: Long) = Unit
    override fun dismissRefusal() = Unit
}

/** Everything of a run the Keywords screen shows (01-product.md sections 3 and 4). */
data class RunUi(
    val generate: GenerateState = GenerateState(),
    val progress: RunProgress = RunProgress.Idle,
    /** The account's recent charges, read for an interrupted run; null while not read. */
    val charges: List<Transaction>? = null,
    /** The runner could not start: nothing was sent. */
    val notStarted: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val presets: PresetsState = PresetsState(),
    val actions: GenerateActions = NoGenerate,
    val presetActions: PresetActions = NoPresets,
    /** View on a stored digest: opens it in History. */
    val onView: (Long) -> Unit = {},
    /** Share → Email on a stored digest (01-product.md section 8). */
    val onShareEmail: (Long) -> Unit = {},
)

/** The Keywords | Topics switch; Topics works with no saved topic (an all-trending digest). */
@Composable
fun ModeSwitch(state: GenerateState, actions: GenerateActions, modifier: Modifier = Modifier) {
    val modes = DigestMode.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = state.mode == mode,
                onClick = { actions.setMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                modifier = Modifier.testTag("mode-${mode.name.lowercase()}"),
            ) {
                Text(stringResource(if (mode == DigestMode.KEYWORDS) Res.string.mode_keywords else Res.string.mode_topics))
            }
        }
    }
}

/**
 * Topic mode (01-product.md section 3): the saved topics, 0 to [InputLimits.KEYWORDS_MAX] picked by tapping their chip.
 * The checkbox before each one ticks it for **Delete (N)**, with a select-all checkbox by the count, like keywords.
 */
@Composable
fun TopicPicker(state: GenerateState, actions: GenerateActions, modifier: Modifier = Modifier) {
    // Ticked for deletion, by lowercase tag, so a reload never shifts them.
    var ticked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val tickedTags = state.topics.filter { it.lowercase() in ticked }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.topics_note, InputLimits.KEYWORDS_MAX), Modifier.testTag("topics-note"))
        if (state.topics.isEmpty()) {
            Text(stringResource(Res.string.topics_none))
            return@Column
        }
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            val selectAll = stringResource(Res.string.topics_select_all)
            TriStateCheckbox(
                state = when (tickedTags.size) {
                    0 -> ToggleableState.Off
                    state.topics.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                },
                onClick = { ticked = if (tickedTags.size == state.topics.size) emptyList() else state.topics.map { it.lowercase() } },
                modifier = Modifier.semantics { contentDescription = selectAll }.testTag("topics-select-all"),
            )
            Text(
                stringResource(Res.string.topics_count, state.chosen.size, InputLimits.KEYWORDS_MAX),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (tickedTags.isNotEmpty()) {
                TextButton(
                    onClick = { actions.deleteTopics(tickedTags); ticked = emptyList() },
                    modifier = Modifier.testTag("topics-delete"),
                ) { Text(stringResource(Res.string.keywords_delete_ticked, tickedTags.size)) }
            }
        }
        state.topics.forEach { tag ->
            val key = tag.lowercase()
            val picked = tag in state.chosen
            val tick = stringResource(Res.string.topic_tick, tag)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = key in ticked,
                    onCheckedChange = { on -> ticked = if (on) ticked + key else ticked - key },
                    modifier = Modifier.semantics { contentDescription = tick },
                )
                FilterChip(
                    selected = picked,
                    onClick = { actions.toggleTopic(tag) },
                    label = { Text(tag) },
                    enabled = picked || state.chosen.size < InputLimits.KEYWORDS_MAX,
                )
            }
        }
    }
}

/** Shown instead of Generate while a job runs. There is no cancel button: once sent, the call is paid. */
@Composable
fun ProgressCard(notificationsAllowed: Boolean, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().padding(16.dp).testTag("progress")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.progress_title), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(Res.string.progress_body))
            if (!notificationsAllowed) Text(stringResource(Res.string.progress_notifications_off), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The dialogs of a run: refusals, confirmation, outcome, and a runner that could not start. */
@Composable
fun RunDialogs(run: RunUi) {
    val actions = run.actions
    when (val step = run.generate.step) {
        is GenerateStep.Refused -> RefusedDialog(step.refusals, actions::dismiss)
        is GenerateStep.Confirm -> ConfirmDialog(step, actions)
        else -> Unit
    }
    val progress = run.progress
    if (progress is RunProgress.Finished && (progress.outcomes.isNotEmpty() || progress.job !is RunJob.Recover)) {
        OutcomeDialog(progress.outcomes, run.charges, actions, run.onView, run.onShareEmail)
    }
    if (run.notStarted) {
        AlertDialog(
            onDismissRequest = actions::dismissNotStarted,
            text = { Text(stringResource(Res.string.outcome_not_started)) },
            confirmButton = { TextButton(onClick = actions::dismissNotStarted) { Text(stringResource(Res.string.ok)) } },
        )
    }
}

@Composable
private fun RefusedDialog(refusals: List<Refusal>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.refused_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("refusals")) {
                refusals.forEach { refusal ->
                    val reason = refusal.reason ?: stringResource(Res.string.refused_no_reason)
                    val res = if (refusal.entry.kind == CheckEntry.Kind.KEYWORD) Res.string.refused_keyword else Res.string.refused_tag
                    Text(stringResource(res, refusal.entry.text, reason))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ok)) } },
    )
}

/** 01-product.md section 4 step 1. Without a readable balance it says why and cannot be confirmed. */
@Composable
private fun ConfirmDialog(step: GenerateStep.Confirm, actions: GenerateActions) {
    val c = step.confirmation
    val prompt = step.snapshot.prompt
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = actions::dismiss,
        title = { Text(stringResource(Res.string.confirm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("confirmation"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.confirm_tokens))
                if (c.balance != null) {
                    Text(stringResource(Res.string.confirm_balance, groupThousands(c.balance!!)))
                } else {
                    c.balanceNotKnown?.let { Text(stringResource(Res.string.confirm_balance_unknown, reasonText(it))) }
                }
                Text(
                    c.averageCost?.let { stringResource(Res.string.confirm_average, groupThousands(it)) }
                        ?: stringResource(Res.string.confirm_average_unknown),
                )
                if (c.lowBalance) {
                    Text(stringResource(Res.string.confirm_low), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { uri.openUri(Links.CLAIM_TOKENS) }) { Text(stringResource(Res.string.balance_claim)) }
                }
                // Everything the owner configured that this call uses (01-product.md section 4).
                if (prompt.mode == DigestMode.TOPICS) {
                    Text(stringResource(Res.string.confirm_topics, prompt.keywords.joinToString(", ")))
                } else {
                    Text(stringResource(Res.string.confirm_keywords, prompt.keywords.joinToString(", ")))
                    Text(
                        if (prompt.interestTags.isEmpty()) stringResource(Res.string.confirm_tags_none)
                        else stringResource(Res.string.confirm_tags, prompt.interestTags.joinToString(", ")),
                    )
                }
                Text(
                    stringResource(
                        Res.string.confirm_settings,
                        attentionLabel(prompt.attentionMode),
                        personaLabel(prompt.persona),
                        stringResource(if (prompt.based) Res.string.on else Res.string.off),
                    ),
                    Modifier.testTag("confirm-settings"),
                )
                Text(
                    step.lastDigestAt?.let { stringResource(Res.string.confirm_last, formatMillis(it)) }
                        ?: stringResource(Res.string.confirm_last_none),
                )
                c.chatBilledHoursAgo?.let { hours ->
                    Text(
                        if (hours < 1) stringResource(Res.string.confirm_chat_recent) else stringResource(Res.string.confirm_chat_hours, hours.toInt()),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions::confirm, enabled = c.canConfirm, modifier = Modifier.testTag("confirm-go")) {
                Text(stringResource(Res.string.confirm_go))
            }
        },
        dismissButton = { TextButton(onClick = actions::dismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * 01-product.md section 4.3. Read again and Write again are offered here only until History exists (owner, 2026-10-29);
 * Send to support was dropped with the app's own email (owner, 2026-10-29). Share sits next to View.
 */
@Composable
private fun OutcomeDialog(outcomes: List<RunOutcome>, charges: List<Transaction>?, actions: GenerateActions, onView: (Long) -> Unit, onShareEmail: (Long) -> Unit) {
    val stored = outcomes.filterIsInstance<RunOutcome.Stored>().firstOrNull()
    val readAgain = outcomes.filterIsInstance<RunOutcome.Unreadable>().firstOrNull()
    val writeAgain = outcomes.filterIsInstance<RunOutcome.NotWritten>().firstOrNull()
    AlertDialog(
        onDismissRequest = actions::acknowledge,
        title = { Text(stringResource(if (stored != null) Res.string.outcome_title_done else Res.string.outcome_title_none)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("outcome"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (outcomes.isEmpty()) Text(stringResource(Res.string.outcome_nothing))
                outcomes.forEach { outcome ->
                    Text(outcomeText(outcome))
                    tokensOf(outcome)?.let { Text(stringResource(Res.string.outcome_tokens, groupThousands(it)), style = MaterialTheme.typography.bodySmall) }
                    if (outcome is RunOutcome.Interrupted && !charges.isNullOrEmpty()) {
                        Text(stringResource(Res.string.outcome_charges))
                        charges.forEach { row ->
                            Text(
                                stringResource(Res.string.outcome_charge, groupThousands(row.amount ?: 0), row.createdAt?.let(::formatIso).orEmpty()),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                stored?.let {
                    TextButton(onClick = { actions.acknowledge(); onView(it.digestId) }, modifier = Modifier.testTag("outcome-view")) {
                        Text(stringResource(Res.string.view))
                    }
                    ShareMenu(onEmail = { onShareEmail(it.digestId) }, asText = true)
                }
                readAgain?.let { TextButton(onClick = { actions.readAgain(it.runId) }) { Text(stringResource(Res.string.read_again)) } }
                writeAgain?.let { TextButton(onClick = { actions.writeAgain(it.runId) }) { Text(stringResource(Res.string.write_again)) } }
                TextButton(onClick = actions::acknowledge, modifier = Modifier.testTag("outcome-ok")) { Text(stringResource(Res.string.ok)) }
            }
        },
    )
}

@Composable
private fun outcomeText(outcome: RunOutcome): String = when (outcome) {
    is RunOutcome.Stored -> stringResource(Res.string.outcome_stored, outcome.topicCount)
    is RunOutcome.Empty -> stringResource(Res.string.outcome_empty)
    is RunOutcome.NotWritten -> stringResource(Res.string.outcome_not_written)
    is RunOutcome.Unreadable -> stringResource(Res.string.outcome_unreadable)
    is RunOutcome.FailedBilled -> stringResource(Res.string.outcome_failed_billed)
    is RunOutcome.Interrupted -> stringResource(Res.string.outcome_interrupted)
    is RunOutcome.Refused -> stringResource(
        when (outcome.error) {
            RunError.KEY_REFUSED -> Res.string.outcome_key_refused
            RunError.INSUFFICIENT_TOKENS -> Res.string.outcome_insufficient
            RunError.UNAVAILABLE -> Res.string.outcome_unavailable
            else -> Res.string.outcome_refused
        },
    )
    RunOutcome.Busy -> stringResource(Res.string.outcome_busy)
    RunOutcome.NotBuilt -> stringResource(Res.string.outcome_not_built)
}

private fun tokensOf(outcome: RunOutcome): Long? = when (outcome) {
    is RunOutcome.Stored -> outcome.tokensBilled
    is RunOutcome.Empty -> outcome.tokensBilled
    is RunOutcome.NotWritten -> outcome.tokensBilled
    is RunOutcome.Unreadable -> outcome.tokensBilled
    is RunOutcome.FailedBilled -> outcome.tokensBilled
    else -> null
}

/** The presets list: save the current 5, load one (Undo on the main screen), delete one after a confirmation. */
@Composable
fun PresetsDialog(state: PresetsState, actions: PresetActions, canSave: Boolean, onClose: () -> Unit) {
    var deleting by remember { mutableStateOf<Preset?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(Res.string.presets_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("presets"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.refusal?.let { Text(presetRefusalText(it), color = MaterialTheme.colorScheme.error) }
                if (state.loaded && state.presets.isEmpty()) Text(stringResource(Res.string.presets_empty))
                state.presets.forEach { preset ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { actions.use(preset.id); onClose() }, modifier = Modifier.weight(1f)) {
                            Text(preset.keywords.joinToString(" · "), Modifier.fillMaxWidth())
                        }
                        IconButton(onClick = { deleting = preset }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.preset_delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions::save, enabled = canSave, modifier = Modifier.testTag("preset-save")) {
                Text(stringResource(Res.string.presets_save))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.close)) } },
    )
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(Res.string.preset_delete_title)) },
            text = { Text(preset.keywords.joinToString(" · ")) },
            confirmButton = {
                TextButton(onClick = { actions.delete(preset.id); deleting = null }) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun presetRefusalText(refusal: PresetRules.Refusal) = when (refusal) {
    PresetRules.Refusal.EMPTY -> stringResource(Res.string.preset_refused_empty)
    PresetRules.Refusal.ALREADY_SAVED -> stringResource(Res.string.preset_refused_already_saved)
    PresetRules.Refusal.FULL -> stringResource(Res.string.preset_refused_full, PresetRules.MAX_PRESETS)
}

/** "2026-10-29 14:05" in the device's time zone. */
fun formatMillis(millis: Long): String = format(Instant.fromEpochMilliseconds(millis))

private fun formatIso(text: String): String = runCatching { format(Instant.parse(text)) }.getOrDefault(text)

private fun format(instant: Instant): String {
    val t = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${t.year}-${two(t.month.ordinal + 1)}-${two(t.day)} ${two(t.hour)}:${two(t.minute)}"
}
