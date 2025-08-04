package com.example.camtellect

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import java.io.IOException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import android.speech.tts.TextToSpeech
import androidx.camera.core.CameraSelector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var porcupineManager: PorcupineManager? = null
    private lateinit var tts: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "audio_channel",
                "Audio Recording",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setupPorcupine()
        setContent {
            VoicePromptScreen(tts)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.UK
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        porcupineManager?.stop()
        porcupineManager?.delete()
        super.onDestroy()
    }

    private fun setupPorcupine() {
        porcupineManager = PorcupineManager.Builder()
            .setAccessKey(BuildConfig.PORCUPINE_KEY)
            .setKeywordPaths(listOf("what_is_this.ppn").toTypedArray())
            .setSensitivity(0.7f)
            .build(applicationContext, object: PorcupineManagerCallback {
                override fun invoke(keywordIndex: Int) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@MainActivity, "Wake-word detected!", Toast.LENGTH_SHORT).show()

                        WakeWordTrigger.shouldTakeAndSendPhoto = true
                        WakeWordTrigger.appContext = applicationContext
                        CameraPreview.takePicture?.invoke()
                    }
                }
            })
        porcupineManager?.start()
    }
}

data class CameraOption(val id: String, val label: String)

fun getCameraOptions(): List<CameraOption> {
    return listOf(
        CameraOption("back", "Back Camera"),
        CameraOption("front", "Front Camera"),
        CameraOption("wireless", "Wireless Camera")
    )
}

@Composable
fun VoicePromptScreen(tts: TextToSpeech) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var audioFile by remember { mutableStateOf<String?>(null) }
    var photoFile by remember { mutableStateOf<String?>(null) }
    var serverReply by remember { mutableStateOf<String?>(null) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var ipAddress by remember { mutableStateOf("") }
    var allowBackground by remember { mutableStateOf(false) }
    var cameraMenuOpen by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf("back") }
    val cameraOptions = getCameraOptions()
    val photoPath = context.filesDir.absolutePath + "/photo.jpg"

    if (isSettingsOpen) {
        SettingsScreen(
            currentIp = ipAddress,
            allowBackground = allowBackground,
            onIpChange = { ipAddress = it },
            onAllowBackgroundChange = { allowBackground = it },
            onBack = { isSettingsOpen = false }
        )
        return
    }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        else
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissions)
    }

    Scaffold(
        bottomBar = {
            BottomAppBar {
                Box {
                    IconButton(onClick = { cameraMenuOpen = true }) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Switch Camera")
                    }
                    DropdownMenu(
                        expanded = cameraMenuOpen,
                        onDismissRequest = { cameraMenuOpen = false }
                    ) {
                        cameraOptions.forEach { option ->
                            val isWireless = option.id == "wireless"
                            val enabled = !isWireless || ipAddress.isNotEmpty()
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                enabled = enabled,
                                onClick = {
                                    if (enabled) {
                                        selectedCamera = option.id
                                        cameraMenuOpen = false
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        if (!isRecording) {
                            ContextCompat.startForegroundService(context, Intent(context, AudioRecordService::class.java))
                        } else {
                            context.stopService(Intent(context, AudioRecordService::class.java))
                            val prefs = context.getSharedPreferences("audio", MODE_PRIVATE)
                            audioFile = prefs.getString("last_audio", null)

                            WakeWordTrigger.shouldSendFullPrompt = true
                            WakeWordTrigger.appContext = context
                            CameraPreview.takePicture?.invoke()
                        }
                        isRecording = !isRecording
                    }
                ) {
                    Text(if (!isRecording) "🎤 Record" else "■ Stop")
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = {
                    isSettingsOpen = true
                }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            when (selectedCamera) {
                "back" -> CameraPreview(
                    modifier = Modifier.weight(1f),
                    lensFacing = CameraSelector.LENS_FACING_BACK,
                    onSnapshotReady = { path -> handleSnapshot(path, tts, serverReplySetter = { serverReply = it }) }
                )
                "front" -> CameraPreview(
                    modifier = Modifier.weight(1f),
                    lensFacing = CameraSelector.LENS_FACING_FRONT,
                    onSnapshotReady = { path -> handleSnapshot(path, tts, serverReplySetter = { serverReply = it }) }
                )
                "wireless" -> {
                    Text("Wireless camera (IP: $ipAddress) preview coming soon!")
                }
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
}

object WakeWordTrigger {
    var shouldTakeAndSendPhoto: Boolean by mutableStateOf(false)
    var shouldSendFullPrompt: Boolean by mutableStateOf(false)
    var appContext: android.content.Context? = null
}

fun handleSnapshot(
    path: String,
    tts: TextToSpeech,
    serverReplySetter: (String?) -> Unit
) {
    val ctx = WakeWordTrigger.appContext
    if (WakeWordTrigger.shouldTakeAndSendPhoto && ctx != null) {
        WakeWordTrigger.shouldTakeAndSendPhoto = false
        android.util.Log.i("WAKE", "📸 Wake-word snapshot, sending photo=$path")
        sendPhotoOnlyToServer(ctx, path) { reply ->
            serverReplySetter(reply)
            tts.speak(reply ?: "", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }
    if (WakeWordTrigger.shouldSendFullPrompt && ctx != null) {
        WakeWordTrigger.shouldSendFullPrompt = false
        android.util.Log.i("BTN", "📸 Manual snapshot, sending audio + photo")
        sendPromptToServer(ctx, null, path) { reply ->
            serverReplySetter(reply)
        }
    }
}

fun sendPhotoOnlyToServer(context: android.content.Context, photoPath: String?, onReply: (String?) -> Unit) {
    if (photoPath.isNullOrEmpty()) {
        android.util.Log.e("SEND", "No path to photo")
        return
    }

    val url = "https://devicio.org/process"
    val photoFile = File(photoPath)
    if (!photoFile.exists()) {
        android.util.Log.e("SEND", "File not found: $photoPath")
        return
    }

    val client = OkHttpClient()
    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("image", photoFile.name, photoFile.asRequestBody("image/jpeg".toMediaType()))
        .build()

    val request = Request.Builder()
        .url(url)
        .post(requestBody)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            android.util.Log.e("SEND", "Error sending photo: ${e.message}", e)
            onReply(null)
        }

        override fun onResponse(call: Call, response: Response) {
            val respString = response.body?.string()
            android.util.Log.i("SEND", "Server response (photo): code=${response.code}, body=$respString")
            response.close()
            onReply(respString)
        }
    })
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
        android.util.Log.e("SEND", "Files not found: audio=$audioPath, photo=$photoPath")
        return
    }
    android.util.Log.i("SEND", "Trying to send audio=$audioPath, photo=$photoPath")

    val url = "https://devicio.org/process"

    val audioFile = File(audioPath)
    val photoFile = File(photoPath)
    if (!audioFile.exists()) android.util.Log.e("SEND", "No audio file: $audioPath")
    if (!photoFile.exists()) android.util.Log.e("SEND", "No photo file: $photoPath")

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
            android.util.Log.e("SEND", "Send error: ${e.message}", e)
            onReply(null)
        }
        override fun onResponse(call: Call, response: Response) {
            val respString = response.body?.string()
            android.util.Log.i("SEND", "Server response: code=${response.code}, body=$respString")
            response.close()
            onReply(respString)
        }
    })
}
