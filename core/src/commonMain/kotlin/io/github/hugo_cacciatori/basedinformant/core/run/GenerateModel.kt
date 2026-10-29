package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.check.CheckOutcome
import io.github.hugo_cacciatori.basedinformant.core.check.CheckRecord
import io.github.hugo_cacciatori.basedinformant.core.check.InputCheck
import io.github.hugo_cacciatori.basedinformant.core.check.Refusal
import io.github.hugo_cacciatori.basedinformant.core.key.SecretStore
import io.github.hugo_cacciatori.basedinformant.core.limits.InputLimits
import io.github.hugo_cacciatori.basedinformant.core.prompt.DigestMode
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

/** Where Generate stands (01-product.md section 4 steps 1 to 3). */
sealed interface GenerateStep {
    data object Idle : GenerateStep

    /** The free input check, then the free balance read. */
    data object Checking : GenerateStep

    /** The input check refused entries: nothing paid is sent until they are fixed (01-product.md section 4.4). */
    data class Refused(val refusals: List<Refusal>) : GenerateStep

    /** The confirmation dialog, with the snapshot its confirm button sends. */
    data class Confirm(val confirmation: Confirmation, val snapshot: RunSnapshot, val lastDigestAt: Long?) : GenerateStep
}

data class GenerateState(
    val mode: DigestMode = DigestMode.KEYWORDS,
    /** The topic tags of stored digests, newest first: topic mode's choices. */
    val topics: List<String> = emptyList(),
    val chosen: List<String> = emptyList(),
    val step: GenerateStep = GenerateStep.Idle,
)

/**
 * Generate, platform-free (01-product.md sections 3 and 4): the free input check in keyword mode, the free balance
 * read, the confirmation, and on confirm the snapshot handed to the runner ([start]). Topic mode picks up to
 * [InputLimits.KEYWORDS_MAX] stored topic tags and skips the input check (the keyword rules apply to keyword mode only).
 */
class GenerateModel(
    private val keywords: () -> List<String>,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val clientFor: (String) -> RfabClient,
    /** The free input check for a key; `InputChecker` in the app. */
    private val inputCheck: suspend (key: String, keywords: List<String>, tags: List<String>) -> CheckOutcome,
    private val runs: RunStore,
    private val digests: DigestStore,
    private val calls: CallLog,
    /** Hands the job to the runner; false when another job holds it or the runner could not start. */
    private val start: (RunJob) -> Boolean,
    /** The persona's name for the email header, null for Off. */
    private val personaName: suspend (Persona) -> String?,
    private val now: () -> Long,
    private val today: () -> LocalDate,
) {
    private val _state = MutableStateFlow(GenerateState())
    val state: StateFlow<GenerateState> = _state.asStateFlow()

    /** Each time the screen opens: the stored topic tags; a chosen tag no longer stored is dropped. */
    suspend fun loadTopics() {
        val topics = digests.topicTags()
        _state.update { s ->
            val chosen = s.chosen.filter { c -> topics.any { it.equals(c, ignoreCase = true) } }
            s.copy(topics = topics, chosen = chosen, mode = if (topics.isEmpty()) DigestMode.KEYWORDS else s.mode)
        }
    }

    /** Topic mode cannot be used while no topic tag is stored. */
    fun setMode(mode: DigestMode) = _state.update { s ->
        if (mode == DigestMode.TOPICS && s.topics.isEmpty()) s else s.copy(mode = mode)
    }

    /** Picks or unpicks a topic; a sixth pick is ignored. */
    fun toggleTopic(tag: String) = _state.update { s ->
        when {
            tag in s.chosen -> s.copy(chosen = s.chosen - tag)
            s.chosen.size >= InputLimits.KEYWORDS_MAX -> s
            else -> s.copy(chosen = s.chosen + tag)
        }
    }

    /** Generate pressed: ends at [GenerateStep.Refused] or [GenerateStep.Confirm]; nothing paid is sent here. */
    suspend fun generate() {
        if (_state.value.step != GenerateStep.Idle) return
        val key = secrets.rfabKey() ?: return
        _state.update { it.copy(step = GenerateStep.Checking) }
        val current = settings.load()
        val mode = _state.value.mode
        val entries = if (mode == DigestMode.TOPICS) _state.value.chosen else keywords()
        if (mode == DigestMode.KEYWORDS) {
            val outcome = inputCheck(key, entries, current.tags)
            calls.recordCheck(CheckRecord(now(), InputCheck.entries(entries, current.tags).size, outcome))
            // No free model answered: the run goes on without a word (01-product.md section 4.4).
            if (outcome is CheckOutcome.Refused) {
                _state.update { it.copy(step = GenerateStep.Refused(outcome.refusals)) }
                return
            }
        }
        val confirmation = ConfirmationRules.build(
            clientFor(key).balance(),
            runs.recentCosts(current.model, ConfirmationRules.AVERAGE_OF),
            now(),
        )
        val snapshot = RunSnapshot(
            prompt = PromptInput(
                keywords = entries,
                interestTags = current.tags,
                attentionMode = current.attentionMode,
                persona = current.persona,
                today = today(),
                model = current.model,
                knownTopicTags = digests.topicTags(),
                mode = mode,
            ),
            personaDisplayName = personaName(current.persona),
        )
        _state.update { it.copy(step = GenerateStep.Confirm(confirmation, snapshot, digests.lastGeneratedAt())) }
    }

    /** The confirm button: acts once, then the runner owns the run. False when the runner refused it (nothing sent). */
    fun confirm(): Boolean {
        val current = _state.value
        val step = current.step as? GenerateStep.Confirm ?: return false
        if (!step.confirmation.canConfirm) return false
        if (!_state.compareAndSet(current, current.copy(step = GenerateStep.Idle))) return false
        return start(RunJob.Generate(step.snapshot))
    }

    /** Cancel in the dialog, or OK on the refusals. */
    fun dismiss() = _state.update { it.copy(step = GenerateStep.Idle) }
}
