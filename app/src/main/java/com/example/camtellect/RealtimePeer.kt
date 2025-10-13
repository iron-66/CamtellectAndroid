package com.example.camtellect

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
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
    private var videoCapturer: VideoCapturer? = null

    private var localAudioTrack: org.webrtc.AudioTrack? = null
    private var localVideoTrack: org.webrtc.VideoTrack? = null

    // DataChannel + keep-alive, чтобы сессия не «засыпала»
    private var dc: DataChannel? = null
    private var keepAliveJob: kotlinx.coroutines.Job? = null

    // Реестр всех подключенных превью (ленивая привязка к треку)
    private val videoSinks = CopyOnWriteArraySet<VideoSink>()

    fun getEglBase(): EglBase? = eglBase

    /** Можно вызывать до/после connect — привяжем, как только появится трек. */
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        videoSinks.add(renderer)
        localVideoTrack?.addSink(renderer) // если трек уже есть — кадры пойдут сразу
    }

    /** Снять превью-рендерер (например, при dispose в Compose). */
    fun detachLocalRenderer(renderer: SurfaceViewRenderer) {
        try { localVideoTrack?.removeSink(renderer) } catch (_: Exception) {}
        videoSinks.remove(renderer)
    }

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
            override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out MediaStream>?) {}

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

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
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
                override fun onMessage(buffer: DataChannel.Buffer) { /* опционально логи */ }
            })
        }

        // 3) Local audio (Opus по умолчанию)
        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = factory!!.createAudioSource(audioConstraints)
        localAudioTrack = factory!!.createAudioTrack("audio0", audioSource).apply { setEnabled(true) }
        pc!!.addTrack(localAudioTrack, listOf("stream0")) // стабильный msid

        // 4) Local video
        val enumerator = Camera2Enumerator(context)
        val camName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: error("No cameras")
        videoCapturer = enumerator.createCapturer(camName, null)

        videoSource = factory!!.createVideoSource(false)
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoCapturer!!.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(1280, 720, 30)

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

        onState("connected")
    }

    fun disconnect() {
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

        // 3) Закрыть PeerConnection (в фоне)
        try { pc?.close() } catch (_: Exception) {}

        // 4) Остановить захват камеры (блокирующий вызов — поэтому не на UI)
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}

        // 5) Освободить низкоуровневые ресурсы
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}

        // 6) Фабрика и EGL
        try { pc?.dispose() } catch (_: Exception) {}
        pc = null
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        try { eglBase?.release() } catch (_: Exception) {}
        eglBase = null

        // 7) Обнулить ссылки
        localAudioTrack = null
        localVideoTrack = null
        videoCapturer = null
        videoSource = null
        audioSource = null
    }
}
