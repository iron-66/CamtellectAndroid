package com.example.camtellect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer

@Composable
fun RealtimeCallPanel(baseUrl: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("idle") }
    val scope = rememberCoroutineScope()

    // Создаём peer один раз на композицию
    val peer = remember {
        RealtimePeer(
            context = ctx,
            tokenProvider = {
                val client = OkHttpClient()
                val req = Request.Builder()
                    .url("$baseUrl/realtime-session")
                    .post(RequestBody.create("application/json".toMediaTypeOrNull(), "{}"))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val j = JSONObject(body)
                    val tok = j.optString("client_secret", "")
                    require(tok.isNotEmpty()) { "Empty ephemeral token" }
                    tok
                }
            }
        )
    }

    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                status = "connecting"
                peer.attachLocalRenderer(localRenderer ?: return@Button)
                scope.launch(Dispatchers.Default) {
                    try {
                        peer.connect(
                            getLocalRenderer = { localRenderer },
                            onState = { s -> status = s }
                        )
                    } catch (e: Exception) {
                        status = "error: ${e.message}"
                    }
                }
            }) { Text("Connect") }

            Button(onClick = {
                peer.disconnect()
                status = "disconnected"
            }) { Text("Disconnect") }
        }

        Text("Status: $status", modifier = Modifier.padding(top = 8.dp))

        // Локальный превью-рендерер
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    setZOrderMediaOverlay(true)
                    localRenderer = this
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )
    }
}
