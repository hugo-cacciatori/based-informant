package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.render.DigestRenderer
import io.github.hugo_cacciatori.basedinformant.core.render.DigestView
import io.github.hugo_cacciatori.basedinformant.core.render.DigestViews
import io.github.hugo_cacciatori.basedinformant.core.render.RenderMeta
import io.github.hugo_cacciatori.basedinformant.core.render.RenderedDigest
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One History card (01-product.md section 5): read without the HTML. */
data class HistoryCard(
    val id: Long,
    /** The generation time. */
    val at: Long,
    val topicCount: Int,
    val summary: String,
)

interface HistoryStore {
    /** Newest first. */
    suspend fun cards(): List<HistoryCard>

    suspend fun get(id: Long): StoredDigest?

    /** Its topic tags leave topic mode with it, unless another digest has them (they are read from the rows). */
    suspend fun delete(id: Long)
}

/** An opened digest: the stored row and the view both the shared mail and the viewer are drawn from (03-rendering.md section 3). */
data class OpenDigest(val id: Long, val stored: StoredDigest, val view: DigestView)

data class HistoryState(
    val loaded: Boolean = false,
    val cards: List<HistoryCard> = emptyList(),
    val open: OpenDigest? = null,
    /** A notification or View pointed at a digest that was deleted meanwhile. */
    val missing: Boolean = false,
)

/** The History tab's logic, platform-free. [personaName] gives the persona's display name, null for Off. */
class HistoryModel(private val store: HistoryStore, private val personaName: suspend (Persona) -> String?) {
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    suspend fun load() {
        val cards = store.cards()
        _state.update { it.copy(loaded = true, cards = cards) }
    }

    /** Opens the viewer, or says the digest is no longer available and shows the list. */
    suspend fun open(id: Long) {
        val stored = store.get(id)
        if (stored == null) {
            load()
            _state.update { it.copy(open = null, missing = true) }
            return
        }
        val view = DigestViews.build(stored.digest, meta(stored))
        _state.update { it.copy(open = OpenDigest(id, stored, view), missing = false) }
    }

    /**
     * Share → Email (01-product.md section 8): the mail built now from the stored digest, the styled HTML and the plain
     * text; null when the digest is gone.
     */
    suspend fun mail(id: Long): RenderedDigest? {
        val stored = store.get(id) ?: return null
        return DigestRenderer.render(stored.digest, meta(stored)).copy(subject = stored.subject)
    }

    private suspend fun meta(stored: StoredDigest): RenderMeta {
        val persona = Persona.entries.firstOrNull { it.name.equals(stored.persona, ignoreCase = true) } ?: Persona.OFF
        return RenderMeta(personaName(persona), stored.model)
    }

    /** Back from the viewer: the list. */
    fun close() = _state.update { it.copy(open = null) }

    /** After the owner confirmed: the viewer closes and the list is read again. */
    suspend fun delete(id: Long) {
        store.delete(id)
        _state.update { it.copy(open = null) }
        load()
    }

    fun dismissMissing() = _state.update { it.copy(missing = false) }
}

/** How long a run's raw answer and checked search are kept (owner, 2026-10-29; 01-product.md section 5). */
object RawRetention {
    const val DAYS = 14
    const val MILLIS = DAYS * 24L * 3_600_000

    /** Runs that wait for Read again or Write again keep theirs until it succeeds. */
    val KEPT_ERRORS = setOf(RunError.UNREADABLE, RunError.WRITE_FAILED)
}
