package io.github.hugo_cacciatori.basedinformant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.hugo_cacciatori.basedinformant.core.run.NoticeIds
import io.github.hugo_cacciatori.basedinformant.core.run.RunController
import io.github.hugo_cacciatori.basedinformant.core.run.RunJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The background-safe runner (04-platforms.md section 3): a `dataSync` foreground service with the ongoing "Generating
 * your digest…" notification and a partial wake lock for the whole run. Not sticky: Android never restarts it, since a
 * restart would mean a second paid call. It runs the job [RunController] holds and stops.
 */
class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NoticeIds.PROGRESS, Notifications.progress(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        // One job per service: a second start while it runs has nothing new to do.
        if (started) return START_NOT_STICKY
        started = true
        val controller = (application as BasedInformantApp).runController
        scope.launch {
            val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            try {
                controller.runClaimed()
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                ServiceCompat.stopForeground(this@RunService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15's daily limit for `dataSync` (6 hours): far beyond a run, but the service must stop when told. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val WAKE_LOCK_TAG = "BasedInformant:run"

        /** The whole run: the paid call (11-minute timeout), the downloads and the free models (owner, 2026-10-29). */
        const val WAKE_LOCK_TIMEOUT_MILLIS = 25 * 60 * 1000L

        /**
         * Claims the runner for [job] and starts the service; false when another job holds it or Android refused the
         * start (then nothing was sent). Called from a visible screen, so Android allows the start.
         */
        fun start(context: Context, controller: RunController, job: RunJob): Boolean {
            if (!controller.claim(job)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, RunService::class.java))
                true
            } catch (_: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException and the background-start refusal of Android 8–11.
                controller.release()
                false
            }
        }
    }
}
