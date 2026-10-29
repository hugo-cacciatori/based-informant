package io.github.hugo_cacciatori.basedinformant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordsState
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsState
import io.github.hugo_cacciatori.basedinformant.core.prompt.DigestMode
import io.github.hugo_cacciatori.basedinformant.core.reminder.Reminder
import io.github.hugo_cacciatori.basedinformant.core.render.RenderedDigest
import io.github.hugo_cacciatori.basedinformant.core.rfab.Transaction
import io.github.hugo_cacciatori.basedinformant.core.run.GenerateModel
import io.github.hugo_cacciatori.basedinformant.core.run.GenerateState
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryModel
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryState
import io.github.hugo_cacciatori.basedinformant.core.run.RunController
import io.github.hugo_cacciatori.basedinformant.core.run.RunJob
import io.github.hugo_cacciatori.basedinformant.core.run.RunOutcome
import io.github.hugo_cacciatori.basedinformant.core.run.RunProgress
import io.github.hugo_cacciatori.basedinformant.core.settings.PresetsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.PresetsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Runs the platform-free [KeywordsModel] in the screen's lifecycle; the logic and its tests live in `:core`. */
class KeywordsViewModel(val model: KeywordsModel) : ViewModel(), KeywordsActions {
    val state: StateFlow<KeywordsState> = model.state

    init {
        reload()
    }

    /** On start and on return from Settings. */
    fun reload() {
        viewModelScope.launch { model.load() }
    }

    /** [onDone] runs only when the keyword was added, so the field keeps a refused text for fixing. */
    override fun add(text: String, onDone: () -> Unit) {
        viewModelScope.launch { if (model.add(text)) onDone() }
    }

    override fun edit(index: Int, text: String, onDone: () -> Unit) {
        viewModelScope.launch { if (model.edit(index, text)) onDone() }
    }

    override fun delete(index: Int) {
        viewModelScope.launch { model.delete(index) }
    }

    override fun undo() {
        viewModelScope.launch { model.undo() }
    }

    override fun dismissUndo() = model.dismissUndo()

    override fun dismissRefusal() = model.dismissRefusal()
}

/** Runs the platform-free [SettingsModel]; only free rfab calls happen here (the balance). */
class SettingsViewModel(private val model: SettingsModel) : ViewModel(), SettingsActions {
    val state: StateFlow<SettingsState> = model.state

    /** Each time Settings opens: the stored settings, then the balance. */
    override fun open() {
        viewModelScope.launch { model.open() }
    }

    override fun refreshBalance() {
        viewModelScope.launch { model.refreshBalance() }
    }

    override fun saveKey(pasted: String, onDone: () -> Unit) {
        viewModelScope.launch { if (model.saveKey(pasted)) onDone() }
    }

    override fun testKey() {
        viewModelScope.launch { model.testKey() }
    }

    override fun removeKey() {
        viewModelScope.launch { model.removeKey() }
    }

    override fun addTag(text: String, onDone: () -> Unit) {
        viewModelScope.launch { if (model.addTag(text)) onDone() }
    }

    override fun deleteTag(index: Int) {
        viewModelScope.launch { model.deleteTag(index) }
    }

    override fun undoTag() {
        viewModelScope.launch { model.undoTag() }
    }

    override fun dismissTagUndo() = model.dismissTagUndo()

    override fun setAttentionMode(mode: AttentionMode) {
        viewModelScope.launch { model.setAttentionMode(mode) }
    }

    override fun setPersona(persona: Persona) {
        viewModelScope.launch { model.setPersona(persona) }
    }

    override fun setTheme(id: String) {
        viewModelScope.launch { model.setTheme(id) }
    }

    override fun setAnimations(on: Boolean) {
        viewModelScope.launch { model.setAnimations(on) }
    }

    override fun setModel(id: String) {
        viewModelScope.launch { model.setModel(id) }
    }

    override fun setReminder(reminder: Reminder) {
        viewModelScope.launch { model.setReminder(reminder) }
    }
}

/**
 * Runs [GenerateModel] and follows the runner ([RunController]): Generate is disabled while a job runs, an interrupted
 * run gets the account's recent charges, and the topics are read again when a job ends (it may have stored a digest).
 */
class GenerateViewModel(
    private val model: GenerateModel,
    private val runs: RunController,
    private val startRun: (RunJob) -> Boolean,
    keywords: KeywordsModel,
    /** The recent charges on the account, read for free; empty when they cannot be read. */
    private val recentCharges: suspend () -> List<Transaction>,
    /** Asks for the notification permission when Android needs it (01-product.md section 7). */
    private val askNotifications: () -> Unit,
) : ViewModel(), GenerateActions {
    val state: StateFlow<GenerateState> = model.state
    val progress: StateFlow<RunProgress> = runs.progress
    private val _charges = MutableStateFlow<List<Transaction>?>(null)
    val charges: StateFlow<List<Transaction>?> = _charges.asStateFlow()
    private val _notStarted = MutableStateFlow(false)
    val notStarted: StateFlow<Boolean> = _notStarted.asStateFlow()

    init {
        viewModelScope.launch {
            runs.progress.collect { progress ->
                keywords.setRunActive(progress is RunProgress.Running)
                if (progress is RunProgress.Finished) {
                    model.loadTopics()
                    if (progress.outcomes.any { it is RunOutcome.Interrupted }) _charges.value = recentCharges()
                } else {
                    _charges.value = null
                }
            }
        }
    }

    /** Each time the main screen opens. */
    fun reload() {
        viewModelScope.launch { model.loadTopics() }
    }

    override fun generate() {
        viewModelScope.launch { model.generate() }
    }

    override fun confirm() {
        if (model.confirm()) askNotifications() else _notStarted.value = true
    }

    override fun dismiss() = model.dismiss()

    override fun setMode(mode: DigestMode) = model.setMode(mode)

    override fun toggleTopic(tag: String) = model.toggleTopic(tag)

    override fun readAgain(runId: Long) = again(RunJob.ReadAgain(runId))

    override fun writeAgain(runId: Long) = again(RunJob.WriteAgain(runId))

    private fun again(job: RunJob) {
        runs.acknowledge()
        if (!startRun(job)) _notStarted.value = true
    }

    override fun acknowledge() = runs.acknowledge()

    override fun dismissNotStarted() {
        _notStarted.value = false
    }
}

/** Runs the platform-free [PresetsModel]. */
class PresetsViewModel(private val model: PresetsModel) : ViewModel(), PresetActions {
    val state: StateFlow<PresetsState> = model.state

    override fun open() {
        viewModelScope.launch { model.load() }
    }

    override fun save() {
        viewModelScope.launch { model.saveCurrent() }
    }

    override fun use(id: Long) {
        viewModelScope.launch { model.use(id) }
    }

    override fun delete(id: Long) {
        viewModelScope.launch { model.delete(id) }
    }

    override fun dismissRefusal() = model.dismissRefusal()
}

/** Runs the platform-free [HistoryModel]. */
class HistoryViewModel(private val model: HistoryModel) : ViewModel(), HistoryActions {
    val state: StateFlow<HistoryState> = model.state

    override fun load() {
        viewModelScope.launch { model.load() }
    }

    override fun open(id: Long) {
        viewModelScope.launch { model.open(id) }
    }

    override fun close() = model.close()

    override fun delete(id: Long) {
        viewModelScope.launch { model.delete(id) }
    }

    override fun dismissMissing() = model.dismissMissing()

    /** The mail for Share → Email, built now from the stored digest. */
    suspend fun mail(id: Long): RenderedDigest? = model.mail(id)
}
