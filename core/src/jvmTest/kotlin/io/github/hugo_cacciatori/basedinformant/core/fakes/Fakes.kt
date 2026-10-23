package io.github.hugo_cacciatori.basedinformant.core.fakes

import io.github.hugo_cacciatori.basedinformant.core.key.SecretStore
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.settings.AppSettings
import io.github.hugo_cacciatori.basedinformant.core.settings.KeywordStore
import io.github.hugo_cacciatori.basedinformant.core.settings.SettingsStore
import kotlin.test.fail

class FakeSecretStore(var key: String? = null) : SecretStore {
    override suspend fun rfabKey() = key
    override suspend fun setRfabKey(key: String) { this.key = key }
    override suspend fun removeRfabKey() { key = null }
}

class FakeKeywordStore(var keywords: List<String> = emptyList()) : KeywordStore {
    var saves = 0
    override suspend fun load() = keywords
    override suspend fun save(keywords: List<String>) { this.keywords = keywords; saves++ }
}

class FakeSettingsStore(var settings: AppSettings = AppSettings()) : SettingsStore {
    var saves = 0
    override suspend fun load() = settings
    override suspend fun save(settings: AppSettings) { this.settings = settings; saves++ }
}

/** Answers the free balance call by key; any paid or other call fails the test. */
class FakeRfab(private val balances: Map<String, RfabResult<Balance>>) {
    val keysUsed = mutableListOf<String>()

    fun clientFor(key: String): RfabClient = object : RfabClient {
        override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> = fail("no chat call in this test")
        override suspend fun balance(): RfabResult<Balance> {
            keysUsed += key
            return balances[key] ?: fail("unexpected key")
        }
        override suspend fun models(): RfabResult<ModelCatalog> = fail("no models call in this test")
    }
}
