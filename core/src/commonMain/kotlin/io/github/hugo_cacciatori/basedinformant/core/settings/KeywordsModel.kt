package io.github.hugo_cacciatori.basedinformant.core.settings

import io.github.hugo_cacciatori.basedinformant.core.key.BalanceDisplay
import io.github.hugo_cacciatori.basedinformant.core.key.KeyCheck
import io.github.hugo_cacciatori.basedinformant.core.key.SecretStore
import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules
import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules.EditResult
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the Keywords screen shows (01-product.md section 3). */
data class KeywordsState(
    val loaded: Boolean = false,
    val keywords: List<String> = emptyList(),
    /** The last refused edit, shown as a message until the next edit. */
    val refusal: TextListRules.Refusal? = null,
    /** The last deleted keyword, while its Undo is offered. */
    val undo: TextListRules.Removed? = null,
    val hasKey: Boolean = false,
    val runActive: Boolean = false,
    /** The balance line above Generate; null while it is read. */
    val balance: BalanceDisplay? = null,
) {
    val generateBlocks: List<GenerateBlock> get() = GenerateRules.blocks(hasKey, keywords.size, runActive)
}

/**
 * The Keywords screen's logic, platform-free: the UI's ViewModel calls it and shows [state].
 * Every change is saved at once; a refused one changes nothing. Its only rfab call is the free balance read,
 * each time the screen opens; [clientFor] makes an rfab client for a key.
 */
class KeywordsModel(
    private val store: KeywordStore,
    private val secrets: SecretStore,
    private val clientFor: (String) -> RfabClient,
    private val rules: TextListRules = TextListRules.KEYWORDS,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(KeywordsState())
    val state: StateFlow<KeywordsState> = _state.asStateFlow()

    /** Each time the screen opens (a key may have been saved or removed in Settings): the list, then the balance. */
    suspend fun load() {
        val key = mutex.withLock {
            val keywords = store.load()
            val key = secrets.rfabKey()
            _state.update { it.copy(loaded = true, keywords = keywords, hasKey = key != null, balance = null) }
            key
        }
        val balance = KeyCheck.balance(key?.let(clientFor))
        _state.update { it.copy(balance = balance) }
    }

    suspend fun add(text: String): Boolean = apply { rules.add(it, text) }

    suspend fun edit(index: Int, text: String): Boolean = apply { rules.edit(it, index, text) }

    suspend fun delete(index: Int) = mutex.withLock {
        val deleted = rules.delete(_state.value.keywords, index)
        store.save(deleted.list)
        _state.update { it.copy(keywords = deleted.list, refusal = null, undo = deleted.removed) }
    }

    suspend fun undo() = mutex.withLock {
        val removed = _state.value.undo ?: return@withLock
        val list = rules.undo(_state.value.keywords, removed)
        if (list != _state.value.keywords) store.save(list)
        _state.update { it.copy(keywords = list, undo = null) }
    }

    /** The Undo offer ends after a few seconds (the UI's timer) or on the next edit. */
    fun dismissUndo() = _state.update { it.copy(undo = null) }

    fun dismissRefusal() = _state.update { it.copy(refusal = null) }

    /** Set by the run use case (stage 4); until then nothing runs. */
    fun setRunActive(active: Boolean) = _state.update { it.copy(runActive = active) }

    private suspend fun apply(edit: (List<String>) -> EditResult): Boolean = mutex.withLock {
        when (val result = edit(_state.value.keywords)) {
            is EditResult.Done -> {
                store.save(result.list)
                _state.update { it.copy(keywords = result.list, refusal = null, undo = null) }
                true
            }
            is EditResult.Refused -> {
                _state.update { it.copy(refusal = result.reason) }
                false
            }
        }
    }
}
