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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

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
    val photoPath = context.filesDir.absolutePath + "/photo.jpg"

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
//        CameraPreview(
//            modifier = Modifier.weight(1f),
//            onSnapshotReady = { path ->
//                photoFile = path
//                sendPromptToServer(context, audioFile, photoFile) { reply ->
//                    serverReply = reply
//                }
//            }
//        )

        val videoHtml = """
            <html>
              <body style="margin:0;padding:0;overflow:hidden;background:black;">
                <img src="http://192.168.1.55:8080/video" 
                     style="width:100vw;height:auto;display:block;" />
              </body>
            </html>
        """.trimIndent()

        CameraWebViewHtml(
            html = videoHtml,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        Button(
            onClick = {
                if (!isRecording) {
                    ContextCompat.startForegroundService(context, Intent(context, AudioRecordService::class.java))
                } else {
                    // Стопаем аудиозапись
                    context.stopService(Intent(context, AudioRecordService::class.java))
                    // Достаём путь к аудио
                    val prefs = context.getSharedPreferences("audio", MODE_PRIVATE)
                    audioFile = prefs.getString("last_audio", null)
                    // Качаем фото с IP Webcam
                    downloadPhotoFromIpWebcam(
                        url = "http://192.168.1.55:8080/photo.jpg",
                        outputPath = photoPath,
                        onComplete = { ok ->
                            if (ok) {
                                photoFile = photoPath
                                // Отправляем на сервер
                                sendPromptToServer(context, audioFile, photoFile) { reply ->
                                    serverReply = reply
                                }
                            }
                        }
                    )
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

fun downloadPhotoFromIpWebcam(
    url: String,
    outputPath: String,
    onComplete: (Boolean) -> Unit
) {
    val client = OkHttpClient()
    val request = Request.Builder().url(url).build()
    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            onComplete(false)
        }

        override fun onResponse(call: Call, response: Response) {
            response.body?.byteStream()?.use { input ->
                File(outputPath).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            onComplete(true)
        }
    })
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

