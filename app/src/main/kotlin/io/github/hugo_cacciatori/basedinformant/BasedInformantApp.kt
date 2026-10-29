package io.github.hugo_cacciatori.basedinformant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.hugo_cacciatori.basedinformant.core.digest.HttpLinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.linkCheckHttpClient
import io.github.hugo_cacciatori.basedinformant.core.run.DigestRun
import io.github.hugo_cacciatori.basedinformant.core.run.RunController
import io.github.hugo_cacciatori.basedinformant.core.run.perform
import io.github.hugo_cacciatori.basedinformant.core.rfab.KtorRfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.rfabHttpClient
import io.github.hugo_cacciatori.basedinformant.data.DataStoreSettingsStore
import io.github.hugo_cacciatori.basedinformant.data.RoomCallLog
import io.github.hugo_cacciatori.basedinformant.data.RoomDigestStore
import io.github.hugo_cacciatori.basedinformant.data.RoomKeywordStore
import io.github.hugo_cacciatori.basedinformant.data.RoomRunStore
import io.github.hugo_cacciatori.basedinformant.data.buildDatabase
import io.github.hugo_cacciatori.basedinformant.data.databaseBuilder
import io.github.hugo_cacciatori.basedinformant.data.rawFiles
import io.github.hugo_cacciatori.basedinformant.data.settingsDataStore
import io.github.hugo_cacciatori.basedinformant.data.settingsPath
import io.github.hugo_cacciatori.basedinformant.ui.AppDependencies
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.datetime.toKotlinLocalDate

/** Builds the app's single instances by hand (04-platforms.md section 1), on first use. */
class BasedInformantApp : Application() {
    private val database by lazy { buildDatabase(databaseBuilder(this)) }
    private val rfabHttp by lazy { rfabHttpClient(OkHttp.create()) }
    private val secrets by lazy { KeystoreSecretStore(this) }

    /** Activities between onStart and onStop: with one, the app is in the foreground. */
    @Volatile private var startedActivities = 0

    val dependencies: AppDependencies by lazy {
        AppDependencies(
            keywordStore = RoomKeywordStore(database, System::currentTimeMillis),
            settingsStore = DataStoreSettingsStore(settingsDataStore(settingsPath(this))),
            secrets = secrets,
            clientFor = { key -> KtorRfabClient(rfabHttp, key) },
        )
    }

    val runStore by lazy { RoomRunStore(database, rawFiles(this)) }

    /** The one runner of this device (01-product.md section 4.1); [RunService] hosts its jobs. */
    val runController: RunController by lazy {
        val linkChecker = HttpLinkChecker(linkCheckHttpClient(OkHttp.create()))
        RunController(
            perform = { job ->
                // Read at run time; a Generate cannot be confirmed without a key, and recovery of an interrupted run
                // needs none. The free models must not retry (DigestRun).
                val client = KtorRfabClient(rfabHttp, secrets.rfabKey().orEmpty(), maxAttempts = 1)
                DigestRun(runStore, RoomDigestStore(database), RoomCallLog(database), client, linkChecker, System::currentTimeMillis)
                    .perform(job)
            },
            notifier = Notifications.Poster(this),
            appVisible = { startedActivities > 0 },
            today = { java.time.LocalDate.now().toKotlinLocalDate() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
