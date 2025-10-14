package com.example.camtellect

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.example.camtellect.ui.AppBottomBar

class MainActivity : ComponentActivity() {

    private lateinit var peer: RealtimePeer

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permsLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))

        setContent {
            val ctx = LocalContext.current
            // ---- UI state ----
            var connected by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("idle") }
            val isConnecting = (status == "init")
            var showWizard by remember { mutableStateOf(false) }

            // persist wireless IP between sessions
            val prefs = remember { ctx.getSharedPreferences("settings", MODE_PRIVATE) }
            var wirelessIp by remember {
                mutableStateOf(prefs.getString("wireless_ip", "") ?: "")
            }
            val wirelessEnabled = wirelessIp.isNotEmpty()

            val scope = rememberCoroutineScope()

            // peer init
            LaunchedEffect(Unit) {
                peer = RealtimePeer(
                    context = this@MainActivity,
                    tokenProvider = {
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
                    AppBottomBar(
                        connected = connected,
                        isConnecting = isConnecting,
                        wirelessEnabled = wirelessEnabled,
                        onConnect = {
                            status = "init"
                            connected = true
                            scope.launch(Dispatchers.Default) {
                                try {
                                    peer.connect { s -> status = s }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        status = "error: ${e.message}"
                                        connected = false
                                    }
                                }
                            }
                        },
                        onDisconnect = {
                            scope.launch(Dispatchers.Default) {
                                try { peer.disconnect() } finally {
                                    withContext(Dispatchers.Main) {
                                        connected = false
                                        status = "disconnected"
                                    }
                                }
                            }
                        },
                        onSelectCamera = { which ->
                            when (which) {
                                "back"  -> if (connected) scope.launch(Dispatchers.Default) { peer.switchCameraFacing(back = true) }
                                "front" -> if (connected) scope.launch(Dispatchers.Default) { peer.switchCameraFacing(back = false) }
                                "wireless" -> {
                                    android.util.Log.i("APP", "Wireless selected: ip=$wirelessIp")
                                }
                            }
                        },
                        onSettingsClick = { showWizard = true }
                    )
                }
            ) { pad ->
                if (showWizard) {
                    ConnectCameraWizard(
                        onClose = { showWizard = false },
                        onIpChosen = { ip ->
                            wirelessIp = ip
                            prefs.edit().putString("wireless_ip", ip).apply()
                            showWizard = false
                        }
                    )
                } else {
                    Column(
                        Modifier.padding(pad).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Status: $status", style = MaterialTheme.typography.titleMedium)

                        if (!connected) {
                            Text("Camera Preview", style = MaterialTheme.typography.titleMedium)
                            CameraXPreview(lensFacing = CameraSelector.LENS_FACING_BACK)
                        } else {
                            val egl = peer.getEglBase()
                            if (egl != null) {
                                RealtimeVideoView(
                                    eglBase = egl,
                                    onReady = { r -> peer.attachLocalRenderer(r) },
                                    onDisposeRenderer = { r -> peer.detachLocalRenderer(r) }
                                )
                            } else {
                                Text("Preparing video preview…")
                            }
                        }
                    }
                }
            }
        }
    }
}
