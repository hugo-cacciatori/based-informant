package io.github.hugo_cacciatori.basedinformant.core.run

import io.github.hugo_cacciatori.basedinformant.core.render.DigestView
import io.github.hugo_cacciatori.basedinformant.core.render.DigestViews
import io.github.hugo_cacciatori.basedinformant.core.render.RenderMeta
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One History card (01-product.md section 5): read without the HTML. */
data class HistoryCard(
    val id: Long,
    /** The generation time; the send time once email exists (stage 5). */
    val at: Long,
    val status: DigestStatus,
    val topicCount: Int,
    val summary: String,
) {
    /** The "Send failed" badge: only when the latest email attempt failed. */
    val sendFailed: Boolean get() = status == DigestStatus.SEND_FAILED
}

interface HistoryStore {
    /** Newest first. */
    suspend fun cards(): List<HistoryCard>

    suspend fun get(id: Long): StoredDigest?

    /** Its topic tags leave topic mode with it, unless another digest has them (they are read from the rows). */
    suspend fun delete(id: Long)
}

/** An opened digest: the stored row and the view both the email and the viewer are drawn from (03-rendering.md section 3). */
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
        val persona = Persona.entries.firstOrNull { it.name.equals(stored.persona, ignoreCase = true) } ?: Persona.OFF
        val view = DigestViews.build(stored.digest, RenderMeta(personaName(persona), stored.model))
        _state.update { it.copy(open = OpenDigest(id, stored, view), missing = false) }
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
