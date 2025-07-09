package com.example.camtellect

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class VideoService : Service() {
    override fun onCreate() {
        super.onCreate()
        // TODO: Init CameraX video capture here
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: Start/stop video recording here
        return START_STICKY
    }

    override fun onDestroy() {
        // TODO: Stop video recording and release resources
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "video_channel")
            .setContentTitle("Запись видео")
            .setContentText("Идёт запись видео")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()
    }
}
