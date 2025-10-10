package com.example.camtellect

import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer

@Composable
fun RealtimeCallScreen(
    baseUrl: String // твой сервер, напр. "https://devicio.org"
) {
    var status by remember { mutableStateOf("idle") }
    val ctx = LocalContext.current

    // создаём поверхностно, повторное создание — ок для MVP
    val peer = remember {
        RealtimePeer(
            context = ctx,
            tokenProvider = {
                // простой запрос за эфемерным токеном
                val client = OkHttpClient()
                val req = Request.Builder()
                    .url("$baseUrl/realtime-session")
                    .post(RequestBody.create("application/json".toMediaTypeOrNull(), "{}"))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val j = JSONObject(body)
                    j.optString("client_secret")
                }
            }
        )
    }

    var localViewRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                status = "connecting"
                // attach renderer при подключении
                peer.attachLocalRenderer(localViewRef ?: return@Button)
                // стартуем в IO/Default
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        peer.connect(
                            getLocalRenderer = { localViewRef },
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

        Text("Status: $status", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))

        Spacer(Modifier.height(12.dp))

        // Локальное превью камеры
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    setZOrderMediaOverlay(true)
                    localViewRef = this
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f/9f)
        )
    }
}
