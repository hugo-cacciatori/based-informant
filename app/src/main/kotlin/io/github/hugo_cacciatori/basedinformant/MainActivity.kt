package io.github.hugo_cacciatori.basedinformant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.hugo_cacciatori.basedinformant.core.run.RunJob
import io.github.hugo_cacciatori.basedinformant.ui.App
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Denied, the app still works: the result shows in the app and every digest is in History (01-product.md section 7).
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as BasedInformantApp
        setContent { App(app.dependencies, askNotifications = ::askNotifications) }
        // Recovery at app start (01-product.md section 4.1): only when a run was left unfinished, and while the app is
        // visible, so Android allows the runner to start.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                if (app.runStore.unfinished().isNotEmpty()) RunService.start(this@MainActivity, app.runController, RunJob.Recover)
            }
        }
    }

    /** Asked in context, right after a confirmed Generate, never at first launch (01-product.md section 7). */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
