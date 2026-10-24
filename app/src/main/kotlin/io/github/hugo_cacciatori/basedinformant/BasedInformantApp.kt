package io.github.hugo_cacciatori.basedinformant

import android.app.Application
import io.github.hugo_cacciatori.basedinformant.core.rfab.KtorRfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.rfabHttpClient
import io.github.hugo_cacciatori.basedinformant.data.DataStoreSettingsStore
import io.github.hugo_cacciatori.basedinformant.data.RoomKeywordStore
import io.github.hugo_cacciatori.basedinformant.data.buildDatabase
import io.github.hugo_cacciatori.basedinformant.data.databaseBuilder
import io.github.hugo_cacciatori.basedinformant.data.settingsDataStore
import io.github.hugo_cacciatori.basedinformant.data.settingsPath
import io.github.hugo_cacciatori.basedinformant.ui.AppDependencies
import io.ktor.client.engine.okhttp.OkHttp

/** Builds the app's single instances by hand (04-platforms.md section 1), on first use. */
class BasedInformantApp : Application() {
    val dependencies: AppDependencies by lazy {
        val http = lazy { rfabHttpClient(OkHttp.create()) }
        AppDependencies(
            keywordStore = RoomKeywordStore(buildDatabase(databaseBuilder(this)), System::currentTimeMillis),
            settingsStore = DataStoreSettingsStore(settingsDataStore(settingsPath(this))),
            secrets = KeystoreSecretStore(this),
            clientFor = { key -> KtorRfabClient(http.value, key) },
        )
    }
}
