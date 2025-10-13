package com.example.camtellect

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Локальный превью WebRTC (тот же поток, который уходит в Realtime).
 * В onReady навешиваем track.addSink(renderer) через peer.attachLocalRenderer(renderer).
 */
@Composable
fun RealtimeVideoView(
    eglBase: EglBase,
    onReady: (SurfaceViewRenderer) -> Unit,
    onDisposeRenderer: (SurfaceViewRenderer) -> Unit = {} // прокинем peer.detachLocalRenderer
) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(false)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(false)
                renderer = this
                onReady(this)
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f),
        onRelease = {
            renderer?.let { onDisposeRenderer(it) }
            it.release()
            renderer = null
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            renderer?.let { onDisposeRenderer(it) }
        }
    }
}

