package com.example.camtellect

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.example.camtellect.ui.theme.CamtellectTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var peer: RealtimePeer

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op */ }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permsLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))

        setContent {
            CamtellectTheme {
                val ctx = LocalContext.current
                var connected by remember { mutableStateOf(false) }
                var status by remember { mutableStateOf("idle") }
                val isConnecting = status == "init"
                var showWizard by remember { mutableStateOf(false) }

                val prefs = remember { ctx.getSharedPreferences("settings", MODE_PRIVATE) }
                var wirelessIp by remember {
                    mutableStateOf(prefs.getString("wireless_ip", "") ?: "")
                }
                val wirelessEnabled = wirelessIp.isNotEmpty()

                val scope = rememberCoroutineScope()

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

                val backgroundGradient = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                        MaterialTheme.colorScheme.surface
                    )
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundGradient)
                ) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        topBar = {
                            if (!showWizard) {
                                CenterAlignedTopAppBar(
                                    title = {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "Camtellect",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Realtime vision assistant",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                        containerColor = Color.Transparent,
                                        scrolledContainerColor = Color.Transparent
                                    )
                                )
                            }
                        },
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
                                                status = "error: ${'$'}{e.message}"
                                                connected = false
                                            }
                                        }
                                    }
                                },
                                onDisconnect = {
                                    scope.launch(Dispatchers.Default) {
                                        try {
                                            peer.disconnect()
                                        } finally {
                                            withContext(Dispatchers.Main) {
                                                connected = false
                                                status = "disconnected"
                                            }
                                        }
                                    }
                                },
                                onSelectCamera = { which ->
                                    when (which) {
                                        "back" -> if (connected) scope.launch(Dispatchers.Default) { peer.switchCameraFacing(back = true) }
                                        "front" -> if (connected) scope.launch(Dispatchers.Default) { peer.switchCameraFacing(back = false) }
                                        "wireless" -> {
                                            android.util.Log.i("APP", "Wireless selected: ip=${'$'}wirelessIp")
                                        }
                                    }
                                },
                                onSettingsClick = { showWizard = true }
                            )
                        }
                    ) { innerPadding ->
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
                            val statusAccent = when {
                                status.startsWith("error", ignoreCase = true) -> MaterialTheme.colorScheme.error
                                isConnecting -> MaterialTheme.colorScheme.tertiary
                                connected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.primary
                            }
                            val statusHeadline = when {
                                status.startsWith("error", ignoreCase = true) -> "Error"
                                isConnecting -> "Connecting"
                                connected -> "Connected"
                                else -> "Idle"
                            }
                            val statusDetails = remember(status) {
                                status.replaceFirstChar { ch ->
                                    if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .padding(innerPadding)
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 16.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(28.dp),
                                    tonalElevation = 6.dp
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = "Status",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Surface(
                                            color = statusAccent.copy(alpha = 0.12f),
                                            contentColor = statusAccent,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = statusHeadline,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        Text(
                                            text = statusDetails,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                ElevatedCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(32.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(20.dp)
                                    ) {
                                        Text(
                                            text = if (connected) "Live feed" else "Camera preview",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = if (connected) {
                                                "You are broadcasting the realtime stream."
                                            } else {
                                                "Frame your shot before going live."
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        val previewShape = RoundedCornerShape(26.dp)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(previewShape)
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                                .aspectRatio(16f / 9f)
                                        ) {
                                            Crossfade(targetState = connected) { isConnected ->
                                                if (isConnected) {
                                                    val egl = peer.getEglBase()
                                                    if (egl != null) {
                                                        RealtimeVideoView(
                                                            eglBase = egl,
                                                            onReady = { r -> peer.attachLocalRenderer(r) },
                                                            onDisposeRenderer = { r -> peer.detachLocalRenderer(r) },
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else {
                                                        Box(
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            CircularProgressIndicator()
                                                        }
                                                    }
                                                } else {
                                                    CameraXPreview(
                                                        lensFacing = CameraSelector.LENS_FACING_BACK,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    tonalElevation = 2.dp,
                                    shape = RoundedCornerShape(26.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Control from below",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "Use the toolbar to connect, switch cameras or open the wireless setup wizard.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
