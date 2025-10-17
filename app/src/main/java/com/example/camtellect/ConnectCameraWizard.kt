package com.example.camtellect

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Duration

private enum class WizardStep { Intro, OpenWifiSettings, Scanning, PickResult, Preview, Done }

data class CameraCandidate(val ip: String)

@Composable
fun ConnectCameraWizard(
    onClose: () -> Unit,
    onIpChosen: (String) -> Unit
) {
    var step by remember { mutableStateOf(WizardStep.Intro) }
    var candidates by remember { mutableStateOf(listOf<CameraCandidate>()) }
    var selected by remember { mutableStateOf<CameraCandidate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val stepOrder = remember {
        listOf(
            WizardStep.Intro,
            WizardStep.OpenWifiSettings,
            WizardStep.Scanning,
            WizardStep.PickResult,
            WizardStep.Preview
        )
    }
    val stepIndex = stepOrder.indexOf(step).takeIf { it >= 0 }?.plus(1)
    val headerTitle = when (step) {
        WizardStep.Intro -> "Connect a wireless camera"
        WizardStep.OpenWifiSettings -> "Connect both devices"
        WizardStep.Scanning -> "Scanning the network"
        WizardStep.PickResult -> "Select your camera"
        WizardStep.Preview -> "Confirm the live feed"
        WizardStep.Done -> "All set!"
    }
    val headerSubtitle = when (step) {
        WizardStep.Intro -> "We'll guide you through pairing a camera on your Wi‑Fi."
        WizardStep.OpenWifiSettings -> "Make sure your phone and camera share the same network."
        WizardStep.Scanning -> "Searching for compatible streams nearby."
        WizardStep.PickResult -> "Tap a device to preview a live snapshot."
        WizardStep.Preview -> selected?.ip?.let { "Streaming from $it" } ?: "Preview the selected camera."
        WizardStep.Done -> "Your camera details are saved for quick access."
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = headerTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stepIndex?.let { "Step $it of ${stepOrder.size}" } ?: "Finished",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = headerSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close wizard")
                }
            }

            HorizontalDivider()

            when (step) {
                WizardStep.Intro -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Keep the camera powered on and connected to your Wi‑Fi. We'll attempt to discover it automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(onClick = onClose) { Text("Back") }
                        FilledTonalButton(
                            onClick = { step = WizardStep.OpenWifiSettings },
                            modifier = Modifier.weight(1f)
                        ) { Text("Start scanning") }
                    }
                }

                WizardStep.OpenWifiSettings -> {
                    val context = LocalContext.current
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Connect your device to the same network that the camera broadcasts from.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { openWifiSettings(context) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Open Wi‑Fi settings") }
                        TextButton(onClick = { step = WizardStep.Scanning }) { Text("I'm connected") }
                    }
                }

                WizardStep.Scanning -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            if (error != null) {
                                Text(
                                    text = error!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Text(
                                text = "This can take up to ten seconds while we probe the subnet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = { step = WizardStep.OpenWifiSettings }) { Text("Back") }

                    LaunchedEffect(step) {
                        if (step != WizardStep.Scanning) return@LaunchedEffect
                        error = null
                        try {
                            val result = withContext(Dispatchers.IO) { scanLocalSubnetForIpWebcam() }
                            candidates = result
                        } catch (t: Throwable) {
                            error = t.message ?: "Scan failed"
                            candidates = emptyList()
                        } finally {
                            step = WizardStep.PickResult
                        }
                    }
                }

                WizardStep.PickResult -> {
                    if (candidates.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            tonalElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "No cameras were discovered automatically. You can still add the IP later from Settings.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            candidates.forEach { c ->
                                ElevatedCard(
                                    onClick = {
                                        selected = c
                                        step = WizardStep.Preview
                                    },
                                    shape = RoundedCornerShape(28.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = "IP: ${c.ip}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        val thumbUrl = "http://${c.ip}:8080/shot.jpg"
                                        Image(
                                            painter = rememberAsyncImagePainter(thumbUrl),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(160.dp)
                                                .clip(RoundedCornerShape(20.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(onClick = { step = WizardStep.Scanning }) { Text("Rescan") }
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                }

                WizardStep.Preview -> {
                    val ip = selected?.ip ?: ""
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = ip,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            val html = """
                                <html><body style=\"margin:0;padding:0;background:black;overflow:hidden;\">
                                <img src=\"http://$ip:8080/video\" style=\"width:100vw;height:auto;display:block;\"/>
                                </body></html>
                            """.trimIndent()
                            WirelessCameraView(
                                html = html,
                                baseUrl = "http://$ip:8080",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(20.dp))
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(onClick = { step = WizardStep.PickResult }) { Text("Back") }
                        FilledTonalButton(
                            onClick = {
                                if (ip.isNotEmpty()) {
                                    onIpChosen(ip)
                                    step = WizardStep.Done
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Use this camera") }
                    }
                }

                WizardStep.Done -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "All set 🎉",
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "We saved your camera IP. You can switch to Wireless in the main screen.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Close") }
                }
            }
        }
    }
}

private fun openWifiSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
}

private suspend fun scanLocalSubnetForIpWebcam(): List<CameraCandidate> = coroutineScope {
    val base = localSubnetBase() ?: return@coroutineScope emptyList()

    val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofMillis(800))
        .connectTimeout(Duration.ofMillis(500))
        .readTimeout(Duration.ofMillis(700))
        .build()

    val dispatcher = Dispatchers.IO.limitedParallelism(24)

    val jobs = (1..254).map { host ->
        async(dispatcher) {
            val ip = "$base.$host"
            val req = Request.Builder().url("http://$ip:8080/shot.jpg").build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful && resp.header("Content-Type")?.contains("image") == true) {
                        CameraCandidate(ip)
                    } else null
                }
            }.getOrNull()
        }
    }
    jobs.awaitAll().filterNotNull()
}

private fun localSubnetBase(): String? {
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
    for (iface in ifaces) {
        val addrs = iface.inetAddresses ?: continue
        for (addr in addrs) {
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                val parts = addr.hostAddress.split(".")
                if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}"
            }
        }
    }
    return null
}
