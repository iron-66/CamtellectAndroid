package com.example.camtellect

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "audio_channel",
                "Audio Recording",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        setContent {
            VoicePromptScreen()
        }
    }
}

@Composable
fun VoicePromptScreen() {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var audioFile by remember { mutableStateOf<String?>(null) }
    var photoFile by remember { mutableStateOf<String?>(null) }
    var serverReply by remember { mutableStateOf<String?>(null) }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        else
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
    }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissions)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        CameraPreview(
            modifier = Modifier.weight(1f),
            onSnapshotReady = { path ->
                photoFile = path
                sendPromptToServer(context, audioFile, photoFile) { reply ->
                    serverReply = reply
                }
            }
        )

        Button(
            onClick = {
                if (!isRecording) {
                    ContextCompat.startForegroundService(context, Intent(context, AudioRecordService::class.java))
                } else {
                    context.stopService(Intent(context, AudioRecordService::class.java))
                    Handler(Looper.getMainLooper()).postDelayed({
                        val prefs = context.getSharedPreferences("audio", MODE_PRIVATE)
                        audioFile = prefs.getString("last_audio", null)
                        CameraPreview.takePicture?.invoke()
                    }, 1000)
                }
                isRecording = !isRecording
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (!isRecording) "🎤 Record" else "■ Stop")
        }

        if (serverReply != null) {
            Text(
                text = "Response:\n$serverReply",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

fun sendPromptToServer(context: android.content.Context, audioPath: String?, photoPath: String?, onReply: (String?) -> Unit) {
    if (audioPath.isNullOrEmpty() || photoPath.isNullOrEmpty()) {
        android.util.Log.e("SEND", "Файлы не найдены: audio=$audioPath, photo=$photoPath")
        return
    }
    android.util.Log.i("SEND", "Пробуем отправить audio=$audioPath, photo=$photoPath")

    val url = "https://devicio.org/process"

    val audioFile = File(audioPath)
    val photoFile = File(photoPath)
    if (!audioFile.exists()) android.util.Log.e("SEND", "Нет файла audio: $audioPath")
    if (!photoFile.exists()) android.util.Log.e("SEND", "Нет файла photo: $photoPath")

    val client = OkHttpClient()
    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("audio", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
        .addFormDataPart("image", photoFile.name, photoFile.asRequestBody("image/jpeg".toMediaType()))
        .build()
    val request = Request.Builder()
        .url(url)
        .post(requestBody)
        .build()
    client.newCall(request).enqueue(object: Callback {
        override fun onFailure(call: Call, e: java.io.IOException) {
            android.util.Log.e("SEND", "Ошибка отправки: ${e.message}", e)
            onReply(null)
        }
        override fun onResponse(call: Call, response: Response) {
            val respString = response.body?.string()
            android.util.Log.i("SEND", "Ответ сервера: code=${response.code}, body=$respString")
            response.close()
            onReply(respString)
        }
    })
}

