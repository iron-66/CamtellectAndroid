package com.example.camtellect

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.*
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.interfaces.IMedia

@Composable
fun RtspPlayerView(rtspUrl: String) {
    val context = LocalContext.current
    val mediaPlayer = remember {
        val args = ArrayList<String>().apply {
            add("--network-caching=150")
            add("--no-drop-late-frames")
            add("--no-skip-frames")
            add("--rtsp-tcp")
        }

        val libVlc = LibVLC(context, args)

        MediaPlayer(libVlc).apply {
            setEventListener { event ->
                if (event.type == MediaPlayer.Event.EncounteredError) {
                    android.util.Log.e("RTSP", "Ошибка воспроизведения RTSP")
                }
            }
        }
    }

    DisposableEffect(rtspUrl) {
        val media = Media(mediaPlayer.libVLC, rtspUrl)
        media.setHWDecoderEnabled(true, false)
        mediaPlayer.media = media
        media.release()

        mediaPlayer.play()

        onDispose {
            mediaPlayer.stop()
            mediaPlayer.release()
        }
    }

    AndroidView(
        factory = {
            FrameLayout(context).apply {
                val videoLayout = VLCVideoLayout(context)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(videoLayout)
                mediaPlayer.attachViews(videoLayout, null, false, false)
            }
        },
        update = {}
    )
}
