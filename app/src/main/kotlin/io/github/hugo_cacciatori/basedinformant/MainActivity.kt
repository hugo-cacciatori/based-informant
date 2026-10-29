package io.github.hugo_cacciatori.basedinformant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.hugo_cacciatori.basedinformant.core.run.RunJob
import io.github.hugo_cacciatori.basedinformant.ui.App
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as BasedInformantApp
        setContent { App(app.dependencies) }
        // Recovery at app start (01-product.md section 4.1): only when a run was left unfinished, and while the app is
        // visible, so Android allows the runner to start.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                if (app.runStore.unfinished().isNotEmpty()) RunService.start(this@MainActivity, app.runController, RunJob.Recover)
            }
        }
    }
}
