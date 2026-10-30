package io.github.hugo_cacciatori.basedinformant.core.theme

import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.key.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The generated themes the owner kept (01-product.md section 10), newest first. */
interface ThemeStore {
    suspend fun all(): List<ThemePack>

    suspend fun save(pack: ThemePack)

    suspend fun delete(id: String)
}

/** No generated theme: the default where no store is given (tests, previews). */
object NoThemeStore : ThemeStore {
    override suspend fun all(): List<ThemePack> = emptyList()
    override suspend fun save(pack: ThemePack) = Unit
    override suspend fun delete(id: String) = Unit
}

data class StudioState(
    /** The kept generated themes, listed after the built-in ones. */
    val themes: List<ThemePack> = emptyList(),
    val step: StudioStep = StudioStep.Idle,
)

sealed interface StudioStep {
    data object Idle : StudioStep

    data class Working(val stage: ThemeGenerator.Stage) : StudioStep

    /** A design that passed every check, shown before the owner keeps or discards it. */
    data class Preview(val pack: ThemePack) : StudioStep

    data class Failed(val reason: Reason) : StudioStep

    enum class Reason { NO_KEY, NO_FREE_MODEL, UNREADABLE }
}

/**
 * Settings → Themes → Generate (01-product.md section 10, owner 2026-10-30), platform-free: free models only, a preview,
 * then Keep (saved and selected through [select]) or Discard. [newId] names a new theme; [generator] makes the chain
 * for a key.
 */
class ThemeStudio(
    private val store: ThemeStore,
    private val secrets: SecretStore,
    private val clientFor: (String) -> RfabClient,
    private val select: suspend (String) -> Unit,
    private val newId: () -> String,
    private val generator: (RfabClient) -> ThemeGenerator = { ThemeGenerator(it) },
) {
    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    suspend fun load() {
        val themes = store.all()
        _state.update { it.copy(themes = themes) }
    }

    suspend fun generate(idea: String) {
        if (_state.value.step is StudioStep.Working) return
        val key = secrets.rfabKey()
        if (key.isNullOrBlank()) return _state.update { it.copy(step = StudioStep.Failed(StudioStep.Reason.NO_KEY)) }
        _state.update { it.copy(step = StudioStep.Working(ThemeGenerator.Stage.DESIGNING)) }
        val outcome = generator(clientFor(key)).generate(idea, newId()) { stage -> _state.update { it.copy(step = StudioStep.Working(stage)) } }
        val step = when (outcome) {
            is ThemeGenerator.Outcome.Designed -> StudioStep.Preview(outcome.pack)
            ThemeGenerator.Outcome.NoFreeModel -> StudioStep.Failed(StudioStep.Reason.NO_FREE_MODEL)
            is ThemeGenerator.Outcome.Refused -> StudioStep.Failed(StudioStep.Reason.UNREADABLE)
        }
        _state.update { it.copy(step = step) }
    }

    /** Keeps the previewed theme under [name] (its own when blank), and makes it the app's theme. */
    suspend fun keep(name: String) {
        val preview = _state.value.step as? StudioStep.Preview ?: return
        val pack = preview.pack.copy(name = name.trim().take(NAME_MAX).ifEmpty { preview.pack.name })
        store.save(pack)
        select(pack.id)
        _state.update { it.copy(step = StudioStep.Idle) }
        load()
    }

    fun discard() = _state.update { it.copy(step = StudioStep.Idle) }

    /** Deletes a kept theme; the app falls back to the default when it was the one in use. */
    suspend fun delete(id: String) {
        store.delete(id)
        load()
    }

    companion object {
        const val NAME_MAX = 24
        const val ID_PREFIX = "gen-"
    }
}
