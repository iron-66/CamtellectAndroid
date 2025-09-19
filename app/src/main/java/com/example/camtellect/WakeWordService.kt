package com.example.camtellect

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.camtellect.oww.OpenWakeWordEngine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIF_CHANNEL_ID = "wake_service"
        private const val NOTIF_ID = 42
        private const val SERVER_URL = "https://devicio.org/process"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var oww: OpenWakeWordEngine? = null

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
                .setSmallIcon(R.mipmap.ic_launcher) // добавь простой иконке ресурс
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
            wakeModelAsset = "oww/what_is_this.onnx",  // проверь имя файла!
            threshold = 0.30f,                         // начни с пониже, потом вернёшь
            smoothWindow = 3
        ) {
            Log.i(TAG, "Wake word detected")
            onWakeTriggered()
        }

        // проверка разрешения делается внутри start(); если нет — просто выйдет false
        val ok = oww?.start() == true
        Log.i(TAG, "OWW start = $ok")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // перезапускать при убийстве системой
        return START_STICKY
    }

    override fun onDestroy() {
        try { oww?.stop() } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        try { wifiLock?.release() } catch (_: Throwable) {}
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
        // предпочтительно — кадр с IP-камеры (без UI и разрешений камеры)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val ip = prefs.getString("wireless_ip", "") ?: ""
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
            // Если IP-камера не настроена — можно отправить broadcast в Activity
            Log.w(TAG, "wireless_ip is empty: nothing to snapshot")
        }
    }

    // --- net helpers ---

    private fun downloadPhoto(url: String, outFile: File, cb: (Boolean) -> Unit) {
        val req = Request.Builder().url(url).build()
        val start = System.currentTimeMillis()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "downloadPhoto error after ${System.currentTimeMillis()-start}ms: ${e.message}", e)
                cb(false)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) { cb(false); return }
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
                Log.e(TAG, "sendPhoto error after ${System.currentTimeMillis()-start}ms: ${e.message}", e)
                cb(null)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val s = resp.body?.string()
                    cb(parseReplyPayloadForService(s))
                    Log.i(TAG, "sendPhoto done in ${System.currentTimeMillis()-start}ms, code=${resp.code}, body=$s")
                }
            }
        })
    }

    private fun parseReplyPayloadForService(payload: String?): String? {
        if (payload.isNullOrBlank()) return null
        return try {
            val obj = org.json.JSONObject(payload)
            when {
                obj.has("reply")   -> obj.optString("reply", null)
                obj.has("message") -> obj.optString("message", null)
                obj.has("text")    -> obj.optString("text", null)
                else -> payload
            }
        } catch (_: Exception) { payload.trim('"', '\'') }
    }
}
