package com.example.camtellect

import android.app.*
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import androidx.core.content.edit

class AudioRecordService : Service() {

    private var recorder: MediaRecorder? = null
    private var outputFile: String? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopRecording()
            stopSelf()
        } else {
            startRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "prompt_${System.currentTimeMillis()}.m4a")
        outputFile = file.absolutePath

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile)
            prepare()
            start()
        }

        getSharedPreferences("audio", MODE_PRIVATE).edit {
            putString("last_audio", outputFile)
        }
        android.util.Log.i("AUDIO", "Audio path saved: $outputFile")
    }

    private fun stopRecording() {
        recorder?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        recorder = null

        if (outputFile != null) {
            getSharedPreferences("audio", MODE_PRIVATE).edit {
                putString("last_audio", outputFile)
            }
            android.util.Log.i("AUDIO_SERVICE", "audioFile path saved: $outputFile")
        } else {
            android.util.Log.e("AUDIO_SERVICE", "outputFile is null")
        }
    }


    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channelId = "audio_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "Audio Recording", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Запись голоса")
            .setContentText("Голос записывается…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .build()
    }
}
