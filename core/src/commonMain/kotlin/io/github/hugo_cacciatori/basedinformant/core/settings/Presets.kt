package io.github.hugo_cacciatori.basedinformant.core.settings

import io.github.hugo_cacciatori.basedinformant.core.limits.InputLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A saved set of 5 keywords, shown as its keywords (no name, owner 2026-10-23). */
data class Preset(val id: Long, val keywords: List<String>)

/** The presets as stored (Room `presets`, 04-platforms.md section 7), oldest first. */
interface PresetStore {
    suspend fun all(): List<Preset>

    suspend fun add(keywords: List<String>): Long

    suspend fun delete(id: Long)
}

/** Keyword presets (01-product.md section 3, owner 2026-10-23 and 2026-10-29). */
object PresetRules {
    const val MAX_PRESETS = 20

    enum class Refusal {
        /** A preset is the current keywords: the list must hold at least one (owner, 2026-10-29). */
        EMPTY,

        /** The same keywords are already a preset, in any order and any case. */
        ALREADY_SAVED,
        FULL,
    }

    fun refusal(keywords: List<String>, presets: List<Preset>): Refusal? = when {
        keywords.isEmpty() -> Refusal.EMPTY
        presets.any { same(it.keywords, keywords) } -> Refusal.ALREADY_SAVED
        presets.size >= MAX_PRESETS -> Refusal.FULL
        else -> null
    }

    fun same(a: List<String>, b: List<String>): Boolean = key(a) == key(b)

    private fun key(keywords: List<String>) = keywords.map { it.trim().lowercase() }.toSet()
}

data class PresetsState(
    val loaded: Boolean = false,
    val presets: List<Preset> = emptyList(),
    /** The last refused save, shown as a message until the next action. */
    val refusal: PresetRules.Refusal? = null,
)

/**
 * The presets list's logic, platform-free. Saving takes the current keywords; loading replaces them through
 * [KeywordsModel.replace], which offers Undo; deleting is confirmed by the UI first.
 */
class PresetsModel(private val store: PresetStore, private val keywords: KeywordsModel) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(PresetsState())
    val state: StateFlow<PresetsState> = _state.asStateFlow()

    suspend fun load() = mutex.withLock {
        _state.update { it.copy(loaded = true, presets = store.all(), refusal = null) }
    }

    /** Saves the current keywords as a preset; false, with the reason in [state], when refused. */
    suspend fun saveCurrent(): Boolean = mutex.withLock {
        val current = keywords.state.value.keywords
        val presets = store.all()
        PresetRules.refusal(current, presets)?.let { reason ->
            _state.update { it.copy(presets = presets, refusal = reason) }
            return@withLock false
        }
        store.add(current)
        _state.update { it.copy(presets = store.all(), refusal = null) }
        true
    }

    suspend fun use(id: Long) {
        val preset = mutex.withLock { _state.value.presets.firstOrNull { it.id == id } } ?: return
        keywords.replace(preset.keywords)
    }

    suspend fun delete(id: Long) = mutex.withLock {
        store.delete(id)
        _state.update { it.copy(presets = store.all(), refusal = null) }
    }

    fun dismissRefusal() = _state.update { it.copy(refusal = null) }
}
