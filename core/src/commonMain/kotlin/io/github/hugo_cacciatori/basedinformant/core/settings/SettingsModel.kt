package io.github.hugo_cacciatori.basedinformant.core.settings

import io.github.hugo_cacciatori.basedinformant.core.key.ApiKeyRules
import io.github.hugo_cacciatori.basedinformant.core.key.BalanceDisplay
import io.github.hugo_cacciatori.basedinformant.core.key.KeyCheck
import io.github.hugo_cacciatori.basedinformant.core.key.KeyShape
import io.github.hugo_cacciatori.basedinformant.core.key.KeyTestResult
import io.github.hugo_cacciatori.basedinformant.core.key.SecretStore
import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules
import io.github.hugo_cacciatori.basedinformant.core.limits.TextListRules.EditResult
import io.github.hugo_cacciatori.basedinformant.core.prompt.TemplateConfig
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Settings shows (01-product.md section 3). The key itself never enters this state, only its last 4 characters. */
data class SettingsState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val keyLastFour: String? = null,
    /** Null while a balance read is in flight. */
    val balance: BalanceDisplay? = null,
    val keyTest: KeyTestResult? = null,
    /** A pasted key refused before saving. */
    val keyShapeRefusal: KeyShape? = null,
    val tagRefusal: TextListRules.Refusal? = null,
    val tagUndo: TextListRules.Removed? = null,
)

/**
 * Settings' logic, platform-free. Each change takes effect and is saved at once (01-product.md section 11);
 * one over a limit is not saved. Only free calls: the balance read, on open, on Refresh and after a key change.
 * [clientFor] makes an rfab client for a key.
 */
class SettingsModel(
    private val store: SettingsStore,
    private val secrets: SecretStore,
    private val clientFor: (String) -> RfabClient,
    private val tagRules: TextListRules = TextListRules.TAGS,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** When Settings opens: the stored settings, then the balance. */
    suspend fun open() {
        mutex.withLock {
            val settings = store.load().withValidModel()
            val key = secrets.rfabKey()
            _state.update { it.copy(loaded = true, settings = settings, keyLastFour = key?.let(ApiKeyRules::lastFour)) }
        }
        refreshBalance()
    }

    suspend fun refreshBalance() {
        _state.update { it.copy(balance = null) }
        val display = KeyCheck.balance(secrets.rfabKey()?.let(clientFor))
        _state.update { it.copy(balance = display) }
    }

    /** Saves a pasted key if its shape is right, replacing the old one, then reads the balance with it. */
    suspend fun saveKey(pasted: String): Boolean {
        val shape = ApiKeyRules.check(pasted)
        if (shape != KeyShape.OK) {
            _state.update { it.copy(keyShapeRefusal = shape) }
            return false
        }
        val key = ApiKeyRules.normalize(pasted)
        secrets.setRfabKey(key)
        _state.update { it.copy(keyLastFour = ApiKeyRules.lastFour(key), keyShapeRefusal = null, keyTest = null) }
        refreshBalance()
        return true
    }

    /** Test key on the saved key: the free balance call. */
    suspend fun testKey() {
        val key = secrets.rfabKey()
        val result = if (key == null) KeyTestResult.BadShape(KeyShape.EMPTY) else KeyCheck.test(key, clientFor)
        _state.update {
            it.copy(
                keyTest = result,
                balance = when (result) {
                    is KeyTestResult.Works -> BalanceDisplay.Known(result.tokens)
                    is KeyTestResult.Failed -> BalanceDisplay.NotKnown(result.reason)
                    is KeyTestResult.BadShape -> it.balance
                },
            )
        }
    }

    suspend fun removeKey() {
        secrets.removeRfabKey()
        _state.update { it.copy(keyLastFour = null, keyTest = null) }
        refreshBalance()
    }

    suspend fun setAttentionMode(mode: AttentionMode) = change { it.copy(attentionMode = mode) }

    suspend fun setPersona(persona: Persona) = change { it.copy(persona = persona) }

    /** Only models validated in the lab are offered (02-ai-call.md section 11). */
    suspend fun setModel(model: String): Boolean {
        if (model !in TemplateConfig.ALLOWED_MODELS) return false
        change { it.copy(model = model) }
        return true
    }

    suspend fun addTag(text: String): Boolean = mutex.withLock {
        when (val result = tagRules.add(_state.value.settings.tags, text)) {
            is EditResult.Done -> {
                save(_state.value.settings.copy(tags = result.list))
                _state.update { it.copy(tagRefusal = null, tagUndo = null) }
                true
            }
            is EditResult.Refused -> {
                _state.update { it.copy(tagRefusal = result.reason) }
                false
            }
        }
    }

    suspend fun deleteTag(index: Int) = mutex.withLock {
        val deleted = tagRules.delete(_state.value.settings.tags, index)
        save(_state.value.settings.copy(tags = deleted.list))
        _state.update { it.copy(tagRefusal = null, tagUndo = deleted.removed) }
    }

    suspend fun undoTag() = mutex.withLock {
        val removed = _state.value.tagUndo ?: return@withLock
        save(_state.value.settings.copy(tags = tagRules.undo(_state.value.settings.tags, removed)))
        _state.update { it.copy(tagUndo = null) }
    }

    fun dismissTagUndo() = _state.update { it.copy(tagUndo = null) }

    private suspend fun change(edit: (AppSettings) -> AppSettings) = mutex.withLock { save(edit(_state.value.settings)) }

    private suspend fun save(settings: AppSettings) {
        if (settings == _state.value.settings) return
        store.save(settings)
        _state.update { it.copy(settings = settings) }
    }
}
