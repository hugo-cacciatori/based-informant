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
    /** The last delete or loaded preset, while its Undo is offered (01-product.md section 3). */
    val restore: Restore? = null,
    val hasKey: Boolean = false,
    val runActive: Boolean = false,
    /** The balance line above Generate; null while it is read. */
    val balance: BalanceDisplay? = null,
) {
    val generateBlocks: List<GenerateBlock> get() = GenerateRules.blocks(hasKey, keywords.size, runActive)
}

/** A change Undo takes back: [previous] is the list before it; [deleted] the keywords removed, empty for a preset. */
data class Restore(val previous: List<String>, val deleted: List<String>) {
    val presetLoaded: Boolean get() = deleted.isEmpty()
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

    /** Deletes the keywords at [indices] at once (owner, 2026-10-29); Undo brings the previous list back. */
    suspend fun delete(indices: Set<Int>) = mutex.withLock {
        val previous = _state.value.keywords
        val deleted = previous.filterIndexed { i, _ -> i in indices }
        if (deleted.isEmpty()) return@withLock
        val list = previous.filterIndexed { i, _ -> i !in indices }
        store.save(list)
        _state.update { it.copy(keywords = list, refusal = null, restore = Restore(previous, deleted)) }
    }

    /** A preset replaces the whole list (owner, 2026-10-23); Undo brings the previous one back. */
    suspend fun replace(keywords: List<String>) = mutex.withLock {
        val previous = _state.value.keywords
        store.save(keywords)
        _state.update { it.copy(keywords = keywords, refusal = null, restore = Restore(previous, emptyList())) }
    }

    suspend fun undo() = mutex.withLock {
        val restore = _state.value.restore ?: return@withLock
        store.save(restore.previous)
        _state.update { it.copy(keywords = restore.previous, restore = null) }
    }

    /** The Undo offer ends after a few seconds (the UI's timer) or on the next edit. */
    fun dismissUndo() = _state.update { it.copy(restore = null) }

    fun dismissRefusal() = _state.update { it.copy(refusal = null) }

    /** Set by the run use case (stage 4); until then nothing runs. */
    fun setRunActive(active: Boolean) = _state.update { it.copy(runActive = active) }

    private suspend fun apply(edit: (List<String>) -> EditResult): Boolean = mutex.withLock {
        when (val result = edit(_state.value.keywords)) {
            is EditResult.Done -> {
                store.save(result.list)
                _state.update { it.copy(keywords = result.list, refusal = null, restore = null) }
                true
            }
            is EditResult.Refused -> {
                _state.update { it.copy(refusal = result.reason) }
                false
            }
        }
    }
}
