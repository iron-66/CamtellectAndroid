package com.example.camtellect

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RealtimePeer(
    private val context: Context,
    private val tokenProvider: suspend () -> String // вернёт ephemeral token
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
    private var localVideoTrack: org.webrtc.VideoTrack? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null

    fun getEglBase(): EglBase? = eglBase

    /** Привязать локальный рендерер в любой момент (после Connect покажет превью). */
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        eglBase?.let { /* renderer.init выполняется в RealtimeVideoView */ }
        localVideoTrack?.addSink(renderer)
    }

    // будем резолвить ожидание, когда IceGatheringState станет COMPLETE
    @Volatile private var iceGatheringComplete: Boolean = false

    suspend fun connect(onState: (String) -> Unit) {
        onState("init")

        // 1) Инициализация WebRTC (общий EGL контекст, фабрика) — оффер будет сырой, без SDP-munging
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        // 2) PeerConnection с UNIFIED_PLAN
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                onState("ice=$newState")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    iceGatheringComplete = true
                }
            }
            override fun onIceCandidate(candidate: IceCandidate) {
                // Realtime ждёт non-trickle (полный SDP), кандидаты шлём в составе оффера
                Log.d(TAG, "onIceCandidate: $candidate")
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                onState("pc=$newState")
            }
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver) {
                Log.i(TAG, "onTrack kind=${transceiver.receiver.track()?.kind()}")
            }
        }) ?: throw IllegalStateException("PeerConnection is null")

        // 3) Локальный аудио-трек (Opus по умолчанию)
        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = factory!!.createAudioSource(audioConstraints)
        val audioTrack = factory!!.createAudioTrack("audio0", audioSource)

        val aTrans = pc!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )
        aTrans.sender.setTrack(audioTrack, false)

        // 4) Локальный видео-трек (через Camera2Enumerator)
        val enumerator = Camera2Enumerator(context)
        val camName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: error("No cameras")
        videoCapturer = enumerator.createCapturer(camName, null)

        videoSource = factory!!.createVideoSource(false)
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoCapturer!!.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(1280, 720, 30)

        localVideoTrack = factory!!.createVideoTrack("video0", videoSource)

        val vTrans = pc!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )
        vTrans.sender.setTrack(localVideoTrack, false)

        // 5) Создаём оффер БЕЗ правок SDP (как рекомендуют гайды по Realtime/WebRTC)
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

        // Логи для валидации Opus
        val sdp = offer.description
        Log.i(TAG, sdp.lines().firstOrNull { it.startsWith("m=audio") } ?: "no m=audio")
        Log.i(TAG, sdp.lines().firstOrNull { it.contains("a=rtpmap:") && it.contains("opus") } ?: "no opus rtpmap")

        // 6) Ставим локальное описание и ждём ICE COMPLETE (не используем trickle)
        suspendCancellableCoroutine<Unit> { cont ->
            pc!!.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(reason: String?) =
                    cont.resumeWithException(RuntimeException("setLocal fail: $reason"))
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, offer)
        }

        // Простое ожидание ICE COMPLETE (с таймаутом ~3с)
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            while (!iceGatheringComplete && System.currentTimeMillis() - start < 3000L) {
                Thread.sleep(30)
            }
        }

        // 7) Отправляем исходный оффер в Realtime → получаем answer (всё в IO-пуле)
        val ephemeral = withContext(Dispatchers.IO) { tokenProvider() }
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

        // 8) Ставим удалённое описание и всё
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
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        videoCapturer?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        pc?.close()
        factory?.dispose()
        eglBase?.release()

        localVideoTrack = null
        videoCapturer = null
        videoSource = null
        audioSource = null
        pc = null
        factory = null
        eglBase = null
    }
}
