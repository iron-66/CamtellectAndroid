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
import android.view.WindowManager
import android.widget.Toast
import android.speech.tts.TextToSpeech
import androidx.camera.core.CameraSelector
import com.example.camtellect.oww.OpenWakeWordEngine
import java.util.Locale
import org.json.JSONObject

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var micPermLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private var oww: OpenWakeWordEngine? = null

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

        oww = OpenWakeWordEngine(
            context = this,
            wakeModelAsset = "oww/weather_v0.1.onnx",
            threshold = 0.55f
        ) {
            WakeWordTrigger.shouldTakeAndSendPhoto = true
            WakeWordTrigger.appContext = applicationContext
            CameraPreview.takePicture?.invoke()
            Toast.makeText(this, "Wake word!", Toast.LENGTH_SHORT).show()
        }
        micPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // стартуем движок только теперь
                if (oww == null) {
                    oww = OpenWakeWordEngine(
                        context = this,
                        wakeModelAsset = "oww/weather_v0.1.onnx",
                        threshold = 0.55f
                    ) {
                        WakeWordTrigger.shouldTakeAndSendPhoto = true
                        WakeWordTrigger.appContext = applicationContext
                        CameraPreview.takePicture?.invoke()
                        Toast.makeText(this, "Wake word!", Toast.LENGTH_SHORT).show()
                    }
                }
                oww?.start()
            } else {
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        // вместо прямого oww?.start() в onCreate — проверка + запрос:
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // разрешение уже есть — можно стартовать
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) // это просто выстрелит granted=true мгновенно
        } else {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent { VoicePromptScreen(tts) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.UK
        }
    }

    override fun onDestroy() {
        oww?.stop()
        tts.stop(); tts.shutdown()
        super.onDestroy()
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

private fun parseReplyPayload(payload: String?): String? {
    if (payload.isNullOrBlank()) return null
    return try {
        val obj = JSONObject(payload)
        when {
            obj.has("reply")    -> obj.optString("reply", null)
            obj.has("message")  -> obj.optString("message", null)
            obj.has("text")     -> obj.optString("text", null)
            else -> payload
        }
    } catch (_: Exception) {
        val s = payload.trim()
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s.substring(1, s.length - 1)
        } else s
    }
}

@Composable
fun VoicePromptScreen(tts: TextToSpeech) {
    val context = LocalContext.current

    var isRecording by remember { mutableStateOf(false) }
    var audioFile by remember { mutableStateOf<String?>(null) }
    var photoFile by remember { mutableStateOf<String?>(null) }
    var serverReply by remember { mutableStateOf<String?>(null) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isConnectWizardOpen by remember { mutableStateOf(false) }
    var ipAddress by remember { mutableStateOf("") }
    var allowBackground by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf("back") }
    val cameraOptions = getCameraOptions()
    val photoPath = context.filesDir.absolutePath + "/photo.jpg"
    var shouldSendPrompt by remember { mutableStateOf(false) }
    var pendingAudioFile by remember { mutableStateOf<String?>(null) }

    val neededPerms = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(neededPerms)
    }

    LaunchedEffect(audioFile, photoFile, shouldSendPrompt) {
        if (shouldSendPrompt && !audioFile.isNullOrEmpty() && !photoFile.isNullOrEmpty()) {
            shouldSendPrompt = false
            android.util.Log.i("BTN", "📸 Manual snapshot, sending audio=$audioFile, photo=$photoFile")
            sendPromptToServer(context, audioFile, photoFile) { reply ->
                serverReply = reply
                tts.speak(reply ?: "", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    fun onRecordToggle() {
        if (!isRecording) {
            ContextCompat.startForegroundService(context, Intent(context, AudioRecordService::class.java))
        } else {
            context.stopService(Intent(context, AudioRecordService::class.java))

            val prefs = context.getSharedPreferences("audio", MODE_PRIVATE)
            val audio = prefs.getString("last_audio", null)
            pendingAudioFile = audio

            if (selectedCamera == "wireless" && ipAddress.isNotEmpty()) {
                val photoUrl = "http://$ipAddress:8080/photo.jpg"
                downloadPhotoFromIpWebcam(
                    url = photoUrl,
                    outputPath = photoPath,
                    onComplete = { ok ->
                        if (ok) {
                            val againPrefs = context.getSharedPreferences("audio", MODE_PRIVATE)
                            val againAudio = againPrefs.getString("last_audio", null)
                            if (!againAudio.isNullOrEmpty()) {
                                sendPromptToServer(context, againAudio, photoPath) { reply ->
                                    serverReply = reply
                                    tts.speak(reply ?: "", TextToSpeech.QUEUE_FLUSH, null, null)
                                }
                            }
                        }
                    }
                )
            } else {
                CameraPreview.takePicture?.invoke()
            }
        }
        isRecording = !isRecording
    }

    fun onSnapshotReady(path: String) {
        photoFile = path

        val ctx = WakeWordTrigger.appContext
        if (WakeWordTrigger.shouldTakeAndSendPhoto && ctx != null) {
            WakeWordTrigger.shouldTakeAndSendPhoto = false
            android.util.Log.i("WAKE", "📸 Wake-word snapshot, sending photo=$path")
            sendPhotoOnlyToServer(ctx, path) { reply ->
                serverReply = reply
                tts.speak(reply ?: "", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }

        if (!pendingAudioFile.isNullOrEmpty()) {
            val audioToSend = pendingAudioFile
            pendingAudioFile = null
            android.util.Log.i("BTN", "📸 Manual snapshot, sending audio=$audioToSend, photo=$path")
            sendPromptToServer(context, audioToSend, path) { reply ->
                serverReply = reply
                tts.speak(reply ?: "", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    if (isConnectWizardOpen) {
        ConnectCameraWizard(
            onClose = { isConnectWizardOpen = false },
            onIpChosen = { ip ->
                ipAddress = ip
                context.getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putString("wireless_ip", ip).apply()
            }
        )
        return
    }

    if (isSettingsOpen) {
        SettingsScreen(
            currentIp = ipAddress,
            allowBackground = allowBackground,
            onIpChange = { ipAddress = it },
            onAllowBackgroundChange = { allowBackground = it },
            onBack = { isSettingsOpen = false },
            onConnectCamera = { isConnectWizardOpen = true }
        )
        return
    }

    Scaffold(
        bottomBar = {
            AppBottomBar(
                cameraOptions = cameraOptions,
                selectedCamera = selectedCamera,
                onCameraSelect = { selectedCamera = it },
                isRecording = isRecording,
                onRecordToggle = ::onRecordToggle,
                onSettingsClick = { isSettingsOpen = true },
                ipAddress = ipAddress
            )
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
                    onSnapshotReady = ::onSnapshotReady
                )
                "front" -> CameraPreview(
                    modifier = Modifier.weight(1f),
                    lensFacing = CameraSelector.LENS_FACING_FRONT,
                    onSnapshotReady = ::onSnapshotReady
                )
                "wireless" -> {
                    if (ipAddress.isNotEmpty()) {
                        val videoHtml = """
                            <html>
                              <body style="margin:0;padding:0;overflow:hidden;background:black;">
                                <img src="http://$ipAddress:8080/video" 
                                     style="width:100vw;height:auto;display:block;" />
                              </body>
                            </html>
                        """.trimIndent()
                        WirelessCameraView(
                            html = videoHtml,
                            baseUrl = "http://$ipAddress:8080",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        )
                    } else {
                        Text("Введите IP адрес в настройках")
                    }
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
    var appContext: android.content.Context? = null
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

    val compressedPhoto = downscaleJpegIfNeeded(photoFile.absolutePath)
    val client = OkHttpClient()
    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("image", compressedPhoto.name, compressedPhoto.asRequestBody("image/jpeg".toMediaType()))
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
            val replyText = parseReplyPayload(respString)
            android.util.Log.i("SEND", "Server response (photo): code=${response.code}, body=$respString")
            response.close()
            onReply(replyText)
        }
    })
}

private fun downscaleJpegIfNeeded(inputPath: String, maxDim: Int = 1280, quality: Int = 85): File {
    val src = java.io.File(inputPath)
    // Быстрая защита: если файл уже небольшой (< 600KB), не трогаем
    if (src.length() < 600_000) return src

    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(inputPath, opts)
    val w = opts.outWidth
    val h = opts.outHeight
    if (w <= 0 || h <= 0) return src

    var inSample = 1
    var tw = w
    var th = h
    while (tw > maxDim || th > maxDim) { inSample *= 2; tw = w / inSample; th = h / inSample }

    val opts2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = inSample }
    val bmp = android.graphics.BitmapFactory.decodeFile(inputPath, opts2) ?: return src

    // Второй проход (точное масштабирование по maxDim)
    val scale = maxOf(bmp.width.toFloat() / maxDim, bmp.height.toFloat() / maxDim, 1f)
    val outW = (bmp.width / scale).toInt()
    val outH = (bmp.height / scale).toInt()
    val scaled = if (scale > 1f) android.graphics.Bitmap.createScaledBitmap(bmp, outW, outH, true) else bmp

    val outFile = java.io.File(src.parentFile, "upload_${System.currentTimeMillis()}.jpg")
    java.io.FileOutputStream(outFile).use { fos ->
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, fos)
    }
    if (scaled !== bmp) bmp.recycle()
    scaled.recycle()
    return outFile
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
            val replyText = parseReplyPayload(respString)
            android.util.Log.i("SEND", "Server response: code=${response.code}, body=$respString")
            response.close()
            onReply(replyText)
        }
    })
}
