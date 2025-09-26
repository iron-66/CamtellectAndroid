package com.example.camtellect.realtime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.webrtc.EglBase
import org.webrtc.VideoTrack

@Composable
fun RealtimeCallScreen(
    modifier: Modifier = Modifier,
    viewModel: RealtimeCallViewModel = viewModel()
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val microphoneLevel by viewModel.microphoneLevel.collectAsStateWithLifecycle()
    val assistantLevel by viewModel.assistantLevel.collectAsStateWithLifecycle()
    val isMicMuted by viewModel.isMicMuted.collectAsStateWithLifecycle()
    val isCameraMuted by viewModel.isCameraMuted.collectAsStateWithLifecycle()
    val isAssistantSpeaking by viewModel.isAssistantSpeaking.collectAsStateWithLifecycle()
    val localTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val eglContext = viewModel.eglContext

    LaunchedEffect(Unit) {
        viewModel.startPreview()
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = when (connectionState) {
                    CallConnectionState.Idle -> "Disconnected"
                    CallConnectionState.Connecting -> "Connecting..."
                    CallConnectionState.Connected -> "Connected"
                    is CallConnectionState.Failed -> "Failed: ${(connectionState as CallConnectionState.Failed).reason}"
                },
                style = MaterialTheme.typography.titleMedium
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.BottomStart
            ) {
                WebRtcVideoRenderer(
                    track = localTrack,
                    eglContext = eglContext,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    mirror = true
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(140.dp)
                ) {
                    AnimatedVisibility(
                        visible = remoteTrack != null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        WebRtcVideoRenderer(
                            track = remoteTrack,
                            eglContext = eglContext,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                            mirror = false
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isAssistantSpeaking,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0x80000000), CircleShape)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("🤖 Speaking", color = Color.White)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Microphone level", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { microphoneLevel.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Assistant audio", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { assistantLevel.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (connectionState == CallConnectionState.Connected) {
                            viewModel.disconnect()
                        } else {
                            viewModel.connect()
                        }
                    }
                ) {
                    Text(
                        text = if (connectionState == CallConnectionState.Connected) "Disconnect" else "Connect"
                    )
                }

                FilledIconButton(onClick = { viewModel.toggleMute() }) {
                    Icon(
                        imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Toggle microphone"
                    )
                }

                FilledIconButton(onClick = { viewModel.toggleCamera() }) {
                    Icon(
                        imageVector = if (isCameraMuted) Icons.Default.VideocamOff else Icons.Default.VideoCall,
                        contentDescription = "Toggle camera"
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                FilledIconButton(onClick = { viewModel.disconnect() }, containerColor = MaterialTheme.colorScheme.error) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Hang up",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            }

            Text(
                text = "The assistant responds with realtime voice. Press Connect to start streaming your mic and camera.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WebRtcVideoRenderer(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    mirror: Boolean = false
) {
    val mirrorState by rememberUpdatedState(newValue = mirror)
    val rendererHolder = remember { mutableStateOf<org.webrtc.SurfaceViewRenderer?>(null) }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { context ->
            org.webrtc.SurfaceViewRenderer(context).apply {
                init(eglContext, null)
                setEnableHardwareScaler(true)
                setMirror(mirrorState)
                rendererHolder.value = this
            }
        },
        update = { view ->
            view.setMirror(mirrorState)
        }
    )

    DisposableEffect(track, rendererHolder.value) {
        val renderer = rendererHolder.value
        if (track != null && renderer != null) {
            track.addSink(renderer)
        }
        onDispose {
            if (track != null && renderer != null) {
                track.removeSink(renderer)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            rendererHolder.value?.release()
            rendererHolder.value = null
        }
    }
}
