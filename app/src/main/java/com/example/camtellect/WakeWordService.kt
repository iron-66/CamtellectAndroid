package com.example.camtellect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.camtellect.oww.OpenWakeWordEngine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WakeWordService : LifecycleService() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIF_CHANNEL_ID = "wake_service"
        private const val NOTIF_ID = 42
        private const val SERVER_URL = "https://devicio.org/process"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var oww: OpenWakeWordEngine? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // общий OkHttp с увеличенными таймаутами
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(
            NOTIF_ID,
            NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Listening for wake word")
                .setContentText("App is actively listening in the background")
                .setOngoing(true)
                .build()
        )

        // держим CPU и Wi-Fi при погашенном экране
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Camtellect:WakeWord")
            .apply { setReferenceCounted(false); acquire() }

        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Camtellect:Wifi")
            ?.apply { setReferenceCounted(false); acquire() }

        // Запуск OWW
        oww = OpenWakeWordEngine(
            context = this,
            wakeModelAsset = "oww/what_is_this_.onnx",
            threshold = 0.002f,
            smoothWindow = 3
        ) {
            Log.i(TAG, "Wake word detected")
            onWakeTriggered()
        }

        val ok = oww?.start() == true
        Log.i(TAG, "OWW start = $ok")

        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                cameraProvider = try {
                    future.get()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get camera provider", e)
                    null
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        try { oww?.stop() } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        try { wifiLock?.release() } catch (_: Throwable) {}
        try {
            ContextCompat.getMainExecutor(this).execute {
                try { cameraProvider?.unbindAll() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        "Wake Word Service",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Keeps the wake word listener running" }
                )
            }
        }
    }

    private fun onWakeTriggered() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val selectedCamera = prefs.getString("selected_camera", "back") ?: "back"
        val ip = prefs.getString("wireless_ip", "") ?: ""

        if (selectedCamera == "wireless") {
            if (ip.isNotEmpty()) {
                val photoUrl = "http://$ip:8080/photo.jpg"
                val out = File(filesDir, "wake_${System.currentTimeMillis()}.jpg")
                downloadPhoto(photoUrl, out) { ok ->
                    if (ok) {
                        sendPhoto(out) { reply ->
                            Log.i(TAG, "Server reply (wake): $reply")
                        }
                    } else {
                        Log.e(TAG, "Failed to download IP-camera snapshot")
                    }
                }
            } else {
                Log.w(TAG, "wireless_ip is empty: nothing to snapshot")
            }
            return
        }

        val lensFacing = when (selectedCamera) {
            "front" -> CameraSelector.LENS_FACING_FRONT
            else -> CameraSelector.LENS_FACING_BACK
        }

        captureLocalPhoto(lensFacing) { file ->
            if (file != null) {
                sendPhoto(file) { reply ->
                    Log.i(TAG, "Server reply (wake, local): $reply")
                }
            } else {
                val preview = CameraPreview.takePicture
                if (preview != null) {
                    Log.i(TAG, "Delegating wake snapshot to activity preview")
                    WakeWordTrigger.appContext = applicationContext
                    WakeWordTrigger.shouldTakeAndSendPhoto = true
                    preview.invoke()
                } else {
                    Log.e(TAG, "Local camera capture failed and no preview available")
                }
            }
        }
    }

    private fun downloadPhoto(url: String, outFile: File, cb: (Boolean) -> Unit) {
        val req = Request.Builder().url(url).build()
        val start = System.currentTimeMillis()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "downloadPhoto error after ${System.currentTimeMillis() - start}ms: ${e.message}", e)
                cb(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        cb(false)
                        return
                    }
                    resp.body?.byteStream()?.use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    cb(true)
                }
            }
        })
    }

    private fun sendPhoto(photo: File, cb: (String?) -> Unit) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                photo.name,
                photo.asRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val req = Request.Builder().url(SERVER_URL).post(body).build()
        val start = System.currentTimeMillis()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "sendPhoto error after ${System.currentTimeMillis() - start}ms: ${e.message}", e)
                cb(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val s = resp.body?.string()
                    cb(parseReplyPayloadForService(s))
                    Log.i(TAG, "sendPhoto done in ${System.currentTimeMillis() - start}ms, code=${resp.code}, body=$s")
                }
            }
        })
    }

    private fun parseReplyPayloadForService(payload: String?): String? {
        if (payload.isNullOrBlank()) return null
        return try {
            val obj = org.json.JSONObject(payload)
            when {
                obj.has("reply") -> obj.optString("reply", null)
                obj.has("message") -> obj.optString("message", null)
                obj.has("text") -> obj.optString("text", null)
                else -> payload
            }
        } catch (_: Exception) {
            payload.trim('"', '\'')
        }
    }

    private fun captureLocalPhoto(lensFacing: Int, cb: (File?) -> Unit) {
        ensureCameraProvider { provider ->
            if (provider == null) {
                cb(null)
                return@ensureCameraProvider
            }

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val mainExecutor = ContextCompat.getMainExecutor(this)
            try {
                provider.bindToLifecycle(this, selector, imageCapture)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Camera already bound to another lifecycle", e)
                mainExecutor.execute { cb(null) }
                return@ensureCameraProvider
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera for wake capture", e)
                mainExecutor.execute { cb(null) }
                return@ensureCameraProvider
            }

            val outputDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
            if (!outputDir.exists()) outputDir.mkdirs()
            val photoFile = File(outputDir, "wake_${System.currentTimeMillis()}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            imageCapture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        mainExecutor.execute {
                            try {
                                cb(photoFile)
                            } finally {
                                try {
                                    provider.unbind(imageCapture)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Local photo capture failed: ${exc.message}", exc)
                        mainExecutor.execute {
                            try {
                                cb(null)
                            } finally {
                                try {
                                    provider.unbind(imageCapture)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    private fun ensureCameraProvider(onReady: (ProcessCameraProvider?) -> Unit) {
        val existing = cameraProvider
        if (existing != null) {
            onReady(existing)
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                onReady(provider)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to obtain camera provider", e)
                onReady(null)
            }
        }, ContextCompat.getMainExecutor(this))
    }
}
