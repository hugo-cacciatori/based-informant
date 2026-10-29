package io.github.hugo_cacciatori.basedinformant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.hugo_cacciatori.basedinformant.core.run.RawRetention
import io.github.hugo_cacciatori.basedinformant.core.run.RunJob
import io.github.hugo_cacciatori.basedinformant.ui.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Denied, the app still works: the result shows in the app and every digest is in History (01-product.md section 7).
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionResult?.invoke(granted)
        permissionResult = null
    }
    private var permissionResult: ((Boolean) -> Unit)? = null

    /** The digest a tapped new-digest notification asks to open; the screens clear it once opened. */
    private val openDigest = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as BasedInformantApp
        if (savedInstanceState == null) takeDigest(intent)
        setContent {
            App(app.dependencies, askNotifications = ::askNotifications, openDigest = openDigest, onDigestOpened = { openDigest.value = null })
        }
        // Recovery at app start (01-product.md section 4.1): only when a run was left unfinished, and while the app is
        // visible, so Android allows the runner to start.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                if (app.runStore.unfinished().isNotEmpty()) RunService.start(this@MainActivity, app.runController, RunJob.Recover)
                app.runStore.pruneRaw(before = System.currentTimeMillis() - RawRetention.MILLIS)
                // A reminder missed while the app was killed or the phone off, then the next one (04-platforms.md section 3).
                ReminderAlarm.wake(app, app.settingsStore.load().reminder)
            }
        }
    }

    // The app already open: the notification arrives here (single top).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeDigest(intent)
    }

    private fun takeDigest(intent: Intent?) {
        intent?.getLongExtra(Notifications.EXTRA_DIGEST_ID, -1L)?.takeIf { it > 0 }?.let { openDigest.value = it }
    }

    /**
     * Asked in context, right after a confirmed Generate or when the reminder is turned on, never at first launch
     * (01-product.md section 7). [onResult] gets whether notifications can be posted.
     */
    private fun askNotifications(onResult: (Boolean) -> Unit) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) return onResult(NotificationManagerCompat.from(this).areNotificationsEnabled())
        permissionResult = onResult
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
