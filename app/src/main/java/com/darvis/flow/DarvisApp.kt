package com.darvis.flow

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class DarvisApp : Application() {

    companion object {
        const val CHANNEL_OVERLAY = "darvis_overlay"
        const val CHANNEL_RESULT = "darvis_result"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val overlayChannel = NotificationChannel(
            CHANNEL_OVERLAY,
            "Recording Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Darvis Flow recording in the background"
        }

        val resultChannel = NotificationChannel(
            CHANNEL_RESULT,
            "Transcription Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows transcription results ready to paste"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(overlayChannel)
        manager.createNotificationChannel(resultChannel)
    }
}
