package com.tcam.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService

class TcamApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        container = AppContainer(this)
        container.startTdlibIfConfigured()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_AUTOMATION, getString(R.string.notif_channel_automation), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_RECORDING, getString(R.string.notif_channel_recording), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_OVERLAY, getString(R.string.notif_channel_overlay), NotificationManager.IMPORTANCE_MIN),
            ),
        )
    }

    companion object {
        const val CHANNEL_AUTOMATION = "automation"
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_OVERLAY = "overlay"
    }
}

val android.content.Context.container: AppContainer get() = (applicationContext as TcamApp).container
