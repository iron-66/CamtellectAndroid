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
 * Локальный превью WebRTC (тот же поток, что уходит в сеть).
 * Вызови peer.attachLocalRenderer(renderer) сразу после Connect.
 */
@Composable
fun RealtimeVideoView(
    eglBase: EglBase,                 // бери из peer.getEglBase()
    onReady: (SurfaceViewRenderer) -> Unit
) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(true)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(false)
                renderer = this
                onReady(this)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        onRelease = {
            it.release()
            if (renderer === it) renderer = null
        }
    )
}
