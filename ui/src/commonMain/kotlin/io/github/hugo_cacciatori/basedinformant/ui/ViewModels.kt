package io.github.hugo_cacciatori.basedinformant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordsState
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsModel
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Runs the platform-free [KeywordsModel] in the screen's lifecycle; the logic and its tests live in `:core`. */
class KeywordsViewModel(private val model: KeywordsModel) : ViewModel(), KeywordsActions {
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

    override fun setModel(id: String) {
        viewModelScope.launch { model.setModel(id) }
    }
}
