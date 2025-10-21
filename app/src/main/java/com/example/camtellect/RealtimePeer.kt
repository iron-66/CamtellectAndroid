package com.example.camtellect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*
import org.webrtc.JavaI420Buffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RealtimePeer(
    private val context: Context,
    private val tokenProvider: suspend () -> String // ephemeral token
) {
    companion object {
        private const val TAG = "RTRTC"
        private const val REALTIME_URL = "https://api.openai.com/v1/realtime?model=gpt-realtime"
        private const val BETA_HDR = "realtime=v1"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null

    // Единственный актуальный «живой» капчер: держим CameraVideoCapturer,
    // чтобы можно было switchCamera(...) без пересоздания пайплайна.
    private var camera2Enumerator: Camera2Enumerator? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    @Volatile private var preferredCameraFacingBack: Boolean = true
    @Volatile private var currentCameraFacingBack: Boolean? = null

    private sealed interface CaptureMode {
        data class DeviceCamera(val back: Boolean) : CaptureMode
        data class Wireless(val ip: String) : CaptureMode
    }

    @Volatile private var captureMode: CaptureMode = CaptureMode.DeviceCamera(back = true)

    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wirelessJob: Job? = null

    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null

    private var dc: DataChannel? = null
    private var keepAliveJob: kotlinx.coroutines.Job? = null

    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null

    private val videoSinks = CopyOnWriteArraySet<VideoSink>()

    @Volatile private var streaming: Boolean = false
    @Volatile private var uiConnected: Boolean = false

    fun getEglBase(): EglBase? = eglBase

    /** Можно вызывать до/после connect — привяжем, как только появится трек. */
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        videoSinks.add(renderer)
        localVideoTrack?.addSink(renderer)
    }

    /** Снять превью-рендерер (например, при dispose в Compose). */
    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        try { localVideoTrack?.removeSink(renderer) } catch (_: Exception) {}
        videoSinks.remove(renderer)
    }

    fun ensureVideoCaptureRunning() {
        if (!streaming && !uiConnected) return
        val capturer = cameraCapturer ?: return
        try {
            capturer.startCapture(1280, 720, 30)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Capture already running: ${e.message}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to restart capture", e)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Capture restart interrupted", e)
            Thread.currentThread().interrupt()
        }
    }

    fun useWirelessCamera(ip: String?) {
        val normalized = ip?.trim()?.takeIf { it.isNotEmpty() }
        val current = captureMode
        if (normalized == null) {
            captureMode = CaptureMode.DeviceCamera(preferredCameraFacingBack)
            if (current is CaptureMode.DeviceCamera) {
                return
            }
            captureScope.launch { startDeviceCameraCapture(preferredCameraFacingBack) }
        } else {
            if (current is CaptureMode.Wireless && current.ip == normalized) {
                return
            }
            captureMode = CaptureMode.Wireless(normalized)
            captureScope.launch { startWirelessCapture(normalized) }
        }
    }

    private fun startDeviceCameraCapture(back: Boolean) {
        val observer = videoSource?.capturerObserver ?: return
        val egl = eglBase ?: return
        stopWirelessJob()

        val enumerator = camera2Enumerator ?: Camera2Enumerator(context).also { camera2Enumerator = it }
        val preferBack = back
        val targetName = enumerator.deviceNames.firstOrNull {
            if (preferBack) enumerator.isBackFacing(it) else enumerator.isFrontFacing(it)
        } ?: enumerator.deviceNames.firstOrNull {
            if (preferBack) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: enumerator.deviceNames.firstOrNull() ?: run {
            Log.e(TAG, "No cameras available for capture")
            return
        }

        if (surfaceHelper == null) {
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
        }

        try { cameraCapturer?.stopCapture() } catch (_: Exception) {}
        try { cameraCapturer?.dispose() } catch (_: Exception) {}

        val helper = surfaceHelper ?: return
        cameraCapturer = (enumerator.createCapturer(targetName, null) as CameraVideoCapturer).also { cap ->
            cap.initialize(helper, context, observer)
            try {
                cap.startCapture(1280, 720, 30)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera capture", e)
            }
        }
        currentCameraFacingBack = enumerator.isBackFacing(targetName)
    }

    private fun startWirelessCapture(ip: String) {
        val observer = videoSource?.capturerObserver ?: return
        stopWirelessJob()
        currentCameraFacingBack = null

        val trimmed = ip.trim()
        val hasScheme = trimmed.contains("://")
        val base = if (hasScheme) {
            trimmed.trimEnd('/')
        } else {
            val withPort = if (":" in trimmed) trimmed else "$trimmed:8080"
            "http://$withPort"
        }
        val sanitized = base.trimEnd('/')
        val frameUrl = when {
            sanitized.endsWith("/video", ignoreCase = true) ->
                sanitized.substring(0, sanitized.length - "/video".length) + "/shot.jpg"
            sanitized.lowercase().endsWith("/shot.jpg") -> sanitized
            sanitized.lowercase().contains("/shot.jpg") -> sanitized
            else -> "$sanitized/shot.jpg"
        }

        wirelessJob = captureScope.launch {
            observer.onCapturerStarted(true)
            val client = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            try {
                while (isActive && captureMode is CaptureMode.Wireless && (captureMode as CaptureMode.Wireless).ip == ip) {
                    val bitmap = try {
                        val request = Request.Builder().url(frameUrl).build()
                        client.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                throw IOException("Wireless frame HTTP ${resp.code}")
                            }
                            val bytes = resp.body?.bytes()
                            if (bytes == null) {
                                throw IOException("Empty frame body")
                            }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: throw IOException("Unable to decode bitmap")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Wireless frame fetch failed", e)
                        delay(500)
                        continue
                    }

                    val frame = try {
                        bitmapToVideoFrame(bitmap)
                    } catch (e: Exception) {
                        Log.w(TAG, "Wireless frame conversion failed", e)
                        bitmap.recycle()
                        delay(200)
                        continue
                    }

                    observer.onFrameCaptured(frame)
                    frame.release()
                    bitmap.recycle()
                    delay(66)
                }
            } finally {
                observer.onCapturerStopped()
            }
        }
    }

    private fun stopWirelessJob() {
        wirelessJob?.cancel()
        wirelessJob = null
    }

    private fun bitmapToVideoFrame(bitmap: Bitmap): VideoFrame {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("Invalid bitmap dimensions")
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val buffer = JavaI420Buffer.allocate(width, height)
        val yPlane = buffer.dataY
        val uPlane = buffer.dataU
        val vPlane = buffer.dataV
        val yStride = buffer.strideY
        val uStride = buffer.strideU
        val vStride = buffer.strideV

        for (row in 0 until height) {
            val rowOffset = row * width
            val yRowStart = row * yStride
            val uRowStart = (row / 2) * uStride
            val vRowStart = (row / 2) * vStride
            for (col in 0 until width) {
                val color = pixels[rowOffset + col]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yPlane.put(yRowStart + col, clampToByte(yValue))
                if (row % 2 == 0 && col % 2 == 0) {
                    val uvIndex = (col / 2)
                    uPlane.put(uRowStart + uvIndex, clampToByte(uValue))
                    vPlane.put(vRowStart + uvIndex, clampToByte(vValue))
                }
            }
        }

        return VideoFrame(buffer, 0, System.nanoTime())
    }

    private fun clampToByte(value: Int): Byte = value.coerceIn(0, 255).toByte()

    // === DC утилиты ===
    private fun DataChannel.sendJson(json: String) {
        val data = java.nio.ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8))
        this.send(DataChannel.Buffer(data, false))
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            while (dc?.state() == DataChannel.State.OPEN) {
                try {
                    dc?.sendJson("""{"type":"session.update","session":{"ping":${System.currentTimeMillis()}}}""")
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(25_000)
            }
        }
    }

    @Volatile private var iceGatheringComplete: Boolean = false

    suspend fun connect(onState: (String) -> Unit) {
        onState("init")
        streaming = false

        // 1) WebRTC init
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        // 2) PeerConnection (UNIFIED_PLAN)
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        pc = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) { onState("ice=$newState") }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                if (newState == PeerConnection.IceGatheringState.COMPLETE) iceGatheringComplete = true
            }
            override fun onIceCandidate(candidate: IceCandidate) { Log.d(TAG, "onIceCandidate: $candidate") } // non-trickle
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) { onState("pc=$newState") }
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}

            // Если сервер сам откроет DC
            override fun onDataChannel(channel: DataChannel) {
                dc = channel
                dc?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() { Log.i(TAG, "DC state=${dc?.state()}") }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val bytes = java.nio.ByteBuffer.allocate(buffer.data.remaining()).also {
                            it.put(buffer.data); it.flip()
                        }
                        Log.d(TAG, "DC msg: " + java.nio.charset.StandardCharsets.UTF_8.decode(bytes).toString())
                    }
                })
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                Log.i(TAG, "onTrack kind=${transceiver.receiver.track()?.kind()}")
            }
        }) ?: error("PeerConnection is null")

        // Создаём свой DC (если сервер не откроет) — используем для session.update и keep-alive
        dc = pc!!.createDataChannel("oai-events", DataChannel.Init()).apply {
            registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(p0: Long) {}
                override fun onStateChange() {
                    if (state() == DataChannel.State.OPEN) {
                        sendJson(
                            """
                            {
                              "type": "session.update",
                              "session": {
                                "type": "realtime",
                                "instructions": "You are a helpful assistant.",
                                "audio": { "input": { "turn_detection": {
                                  "type": "server_vad",
                                  "idle_timeout_ms": 90000
                                }}}
                              }
                            }
                            """.trimIndent()
                        )
                        startKeepAlive()
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) { /* optional logs */ }
            })
        }

        // Настроим маршрутизацию аудио на громкий динамик
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.let { manager ->
            previousAudioMode = manager.mode
            previousSpeakerphoneOn = manager.isSpeakerphoneOn
            try {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                manager.isSpeakerphoneOn = true
            } catch (_: Exception) {
                // Если не удалось изменить режим, не прерываем соединение
            }
        }

        // 3) Local audio (Opus по умолчанию)
        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = factory!!.createAudioSource(audioConstraints)
        localAudioTrack = factory!!.createAudioTrack("audio0", audioSource).apply { setEnabled(true) }
        pc!!.addTrack(localAudioTrack, listOf("stream0"))

        // 4) Local video — создаём source и запускаем выбранный режим
        videoSource = factory!!.createVideoSource(false)
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)

        when (val mode = captureMode) {
            is CaptureMode.DeviceCamera -> startDeviceCameraCapture(mode.back)
            is CaptureMode.Wireless -> startWirelessCapture(mode.ip)
        }

        localVideoTrack = factory!!.createVideoTrack("video0", videoSource).apply { setEnabled(true) }
        pc!!.addTrack(localVideoTrack, listOf("stream0"))

        // ← после создания трека навешиваем все зарегистрированные превью
        for (sink in videoSinks) {
            try { localVideoTrack?.addSink(sink) } catch (_: Exception) {}
        }

        // 5) Offer (без правки SDP)
        val offerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) = cont.resume(desc)
                override fun onCreateFailure(reason: String?) =
                    cont.resumeWithException(RuntimeException("offer fail: $reason"))
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, offerConstraints)
        }

        val sdp = offer.description
        Log.i(TAG, sdp.lines().firstOrNull { it.startsWith("m=audio") } ?: "no m=audio")
        Log.i(TAG, sdp.lines().firstOrNull { it.contains("a=rtpmap:") && it.contains("opus") } ?: "no opus rtpmap")
        Log.i(TAG, sdp.lines().firstOrNull { it.startsWith("m=video") } ?: "no m=video")

        // 6) setLocal + ждём ICE COMPLETE (non-trickle)
        suspendCancellableCoroutine<Unit> { cont ->
            pc!!.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(reason: String?) =
                    cont.resumeWithException(RuntimeException("setLocal fail: $reason"))
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, offer)
        }
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            while (!iceGatheringComplete && System.currentTimeMillis() - start < 3000L) {
                Thread.sleep(30)
            }
        }

        // 7) POST offer → answer (в IO)
        val ephemeral = withContext(Dispatchers.IO) { tokenProvider() } // получать прямо перед connect
        val req = Request.Builder()
            .url(REALTIME_URL)
            .addHeader("Authorization", "Bearer $ephemeral")
            .addHeader("OpenAI-Beta", BETA_HDR)
            .addHeader("Content-Type", "application/sdp")
            .post(offer.description.toRequestBody("application/sdp".toMediaTypeOrNull()))
            .build()

        val answerSdp = withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Realtime HTTP ${resp.code} body=$body")
                    throw RuntimeException("Realtime HTTP ${resp.code}")
                }
                body
            }
        }

        // 8) setRemote(answer)
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        suspendCancellableCoroutine<Unit> { cont ->
            pc!!.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(reason: String?) =
                    cont.resumeWithException(RuntimeException("setRemote fail: $reason"))
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, answer)
        }

        streaming = true
        onState("connected")
    }

    /** Переключение камеры: пытаемся именованным API, иначе мягко пересоздаём капчер. */
    suspend fun switchCameraFacing(back: Boolean) {
        setPreferredCameraFacing(back)
    }

    fun setPreferredCameraFacing(back: Boolean) {
        preferredCameraFacingBack = back
        captureMode = CaptureMode.DeviceCamera(back)
        if (currentCameraFacingBack == back && cameraCapturer != null) {
            ensureVideoCaptureRunning()
            return
        }
        captureScope.launch { startDeviceCameraCapture(back) }
    }

    fun disconnect() {
        streaming = false
        uiConnected = false
        // Если вызвали с UI — перекинем в фон, чтобы не ловить ANR.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) { disconnect() }
            return
        }

        // Снимаем все sinks заранее
        for (sink in videoSinks) {
            try { localVideoTrack?.removeSink(sink) } catch (_: Exception) {}
        }
        videoSinks.clear()

        // 1) Остановить keep-alive и data channel
        try { keepAliveJob?.cancel() } catch (_: Exception) {}
        keepAliveJob = null
        try { dc?.close() } catch (_: Exception) {}
        dc = null

        // 2) Отключить треки
        try { localVideoTrack?.setEnabled(false) } catch (_: Exception) {}
        try { localAudioTrack?.setEnabled(false) } catch (_: Exception) {}

        // 3) Закрыть PeerConnection
        try { pc?.close() } catch (_: Exception) {}

        // 4) Остановить захват камеры
        try { cameraCapturer?.stopCapture() } catch (_: Exception) {}
        try { cameraCapturer?.dispose() } catch (_: Exception) {}
        cameraCapturer = null
        stopWirelessJob()
        captureScope.coroutineContext.cancelChildren()
        currentCameraFacingBack = null

        // 5) Освободить низкоуровневые ресурсы
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}

        // 6) Фабрика и EGL
        try { pc?.dispose() } catch (_: Exception) {}
        pc = null
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        surfaceHelper = null
        try { eglBase?.release() } catch (_: Exception) {}
        eglBase = null

        // 7) Вернём настройки аудио, чтобы не ломать системное поведение
        audioManager?.let { manager ->
            try {
                previousAudioMode?.let { manager.mode = it }
                previousSpeakerphoneOn?.let { manager.isSpeakerphoneOn = it }
            } catch (_: Exception) {}
        }
        previousAudioMode = null
        previousSpeakerphoneOn = null
        audioManager = null

        // 8) Обнулить ссылки
        localAudioTrack = null
        localVideoTrack = null
        camera2Enumerator = null
        captureMode = CaptureMode.DeviceCamera(back = true)
    }

    fun isStreaming(): Boolean = streaming

    fun setUiConnected(connected: Boolean) {
        uiConnected = connected
    }
}
