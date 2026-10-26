package com.tcam.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tcam.app.R
import com.tcam.app.TcamApp
import com.tcam.app.container
import com.tcam.app.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while a batch runs, so the
 * work continues when Telegram (screen mode) or another app is in front.
 * Its notification shows progress and has an emergency Stop button.
 */
class AutomationService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_BATCH) {
            container.controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(null, 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        lifecycleScope.launch {
            container.controller.state.collect { s ->
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(s.current?.input?.displayText, s.finishedCount, s.tasks.size))
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(current: String?, done: Int, total: Int) =
        NotificationCompat.Builder(this, TcamApp.CHANNEL_AUTOMATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(current ?: "")
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .addAction(
                0, getString(R.string.stop),
                PendingIntent.getService(
                    this, 1, Intent(this, AutomationService::class.java).setAction(ACTION_STOP_BATCH),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val ACTION_STOP_BATCH = "com.tcam.app.STOP_BATCH"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AutomationService::class.java))
            } catch (e: Exception) {
                // Android blocks starting foreground services from the background; the batch still runs.
                Log.w("AutomationService", "Could not start foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutomationService::class.java))
        }
    }
}
