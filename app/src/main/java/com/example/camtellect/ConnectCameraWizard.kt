package com.example.camtellect

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.*
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

    Surface(tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (step) {

                WizardStep.Intro -> {
                    Text("Connect camera", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "This wizard will connect to a wireless camera on your network. " +
                                "We’ll look for available camera in your current Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onClose) { Text("Cancel") }
                        Button(onClick = { step = WizardStep.OpenWifiSettings }) { Text("Start") }
                    }
                }

                WizardStep.OpenWifiSettings -> {
                    val context = LocalContext.current
                    Text("Connect to Wi-Fi", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Make sure both devices are in the same Wi-Fi. ",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { openWifiSettings(context) }) {
                            Text("Open Wi-Fi settings")
                        }
                        OutlinedButton(onClick = { step = WizardStep.Scanning }) { Text("I’m connected") }
                    }
                }

                WizardStep.Scanning -> {
                    Text("Looking for a camera…", style = MaterialTheme.typography.headlineMedium)
                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("This can take ~5–10 seconds.")
                    OutlinedButton(onClick = { step = WizardStep.PickResult }) { Text("Skip") }

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
                    Text("Select your camera", style = MaterialTheme.typography.headlineMedium)
                    if (candidates.isEmpty()) {
                        Text("Nothing found automatically. You can still enter IP manually on Settings.")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        candidates.forEach { c ->
                            ElevatedCard(
                                onClick = { selected = c; step = WizardStep.Preview },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("IP: ${c.ip}", style = MaterialTheme.typography.titleMedium)
                                    // мини-превью (статичный кадр)
                                    val thumbUrl = "http://${c.ip}:8080/shot.jpg"
                                    Image(
                                        painter = rememberAsyncImagePainter(thumbUrl),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(160.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { step = WizardStep.Scanning }) { Text("Rescan") }
                        OutlinedButton(onClick = onClose) { Text("Close") }
                    }
                }

                WizardStep.Preview -> {
                    val ip = selected?.ip ?: ""
                    Text("Is this your camera?", style = MaterialTheme.typography.headlineMedium)
                    Text(ip, style = MaterialTheme.typography.titleMedium)
                    val html = """
                        <html><body style="margin:0;padding:0;background:black;overflow:hidden;">
                        <img src="http://$ip:8080/video" style="width:100vw;height:auto;display:block;"/>
                        </body></html>
                    """.trimIndent()
                    WirelessCameraView(
                        html = html,
                        baseUrl = "http://$ip:8080",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { step = WizardStep.PickResult }) { Text("Back") }
                        Button(onClick = {
                            if (ip.isNotEmpty()) {
                                onIpChosen(ip)
                                step = WizardStep.Done
                            }
                        }) { Text("This is my camera!") }
                    }
                }

                WizardStep.Done -> {
                    Text("All set 🎉", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text("We saved your camera IP. You can switch to Wireless in the main screen.", textAlign = TextAlign.Start)
                    Button(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                        Text("Close")
                    }
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
