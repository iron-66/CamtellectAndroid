package com.example.camtellect

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var peer: RealtimePeer

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Права на камеру/микрофон
        permsLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))

        setContent {
            val ctx = LocalContext.current
            var connected by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            // Инициализация peer с провайдером эфемерального токена
            LaunchedEffect(Unit) {
                peer = RealtimePeer(
                    context = this@MainActivity,
                    tokenProvider = {
                        // Замените URL на ваш backend-эндпоинт эпемерального ключа
                        val req = Request.Builder()
                            .url("https://devicio.org/realtime-session")
                            .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                            .build()
                        OkHttpClient().newCall(req).execute().use { r ->
                            val body = r.body?.string().orEmpty()
                            val j = JSONObject(body)
                            j.getString("client_secret")
                        }
                    }
                )
            }

            Scaffold(
                bottomBar = {
                    Row(Modifier.padding(16.dp)) {
                        if (!connected) {
                            Button(onClick = {
                                // Запускаем connect НЕ на UI-потоке
                                scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                                    peer.connect { s -> android.util.Log.i("RTRTC", s) }
                                    connected = true
                                }
                            }) { Text("Connect") }
                        } else {
                            Button(onClick = {
                                peer.disconnect()
                                connected = false
                            }) { Text("Disconnect") }
                        }
                    }
                }
            ) { pad ->
                Column(Modifier.padding(pad).padding(16.dp)) {
                    Text("Camera Preview", style = MaterialTheme.typography.titleMedium)
                    if (!connected) {
                        CameraXPreview(lensFacing = CameraSelector.LENS_FACING_BACK)
                    } else {
                        Text("Connected to Realtime…")
                    }
                }
            }
        }
    }
}