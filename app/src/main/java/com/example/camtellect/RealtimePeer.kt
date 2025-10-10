package com.example.camtellect

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.webrtc.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RealtimePeer(
    private val context: Context,
    private val tokenProvider: suspend () -> String // выдаёт эфемерный токен с твоего бекенда
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

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var localVideoSource: VideoSource? = null
    private var localAudioSource: AudioSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var eglBase: EglBase? = null

    // будем резолвить это при IceGatheringState.COMPLETE
    private var iceCompleteCont: CancellableContinuation<Unit>? = null

    /** Позови, когда у тебя есть SurfaceViewRenderer под локальный превью. */
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        eglBase?.let { renderer.init(it.eglBaseContext, null) }
    }

    suspend fun connect(
        getLocalRenderer: () -> SurfaceViewRenderer?,
        onState: (String) -> Unit
    ) {
        onState("init")

        // 1) PeerConnectionFactory + EGL
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, /*enableIntelVp8Encoder*/true, /*enableH264HighProfile*/true))
            .createPeerConnectionFactory()

        // 2) ICE/STUN + UNIFIED_PLAN
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        // 3) PeerConnection + коллбэки
        pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onState("ice=$state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                // no-op
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    iceCompleteCont?.let { if (it.isActive) it.resume(Unit) }
                    iceCompleteCont = null
                }
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                // Trickle не используем (OpenAI ждёт полный SDP), просто лог
                Log.d(TAG, "onIceCandidate: $candidate")
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                onState("pc=$newState")
            }

            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {}

            override fun onTrack(transceiver: RtpTransceiver) {
                Log.i(TAG, "onTrack kind=${transceiver.receiver.track()?.kind()}")
            }
        }) ?: run {
            onState("failed: peerconnection null"); return
        }

        // 4) Локальное видео (Camera2 → VideoSource → Track)
        val cameraEnumerator = Camera2Enumerator(context)
        val camName = cameraEnumerator.deviceNames.firstOrNull { cameraEnumerator.isFrontFacing(it) }
            ?: cameraEnumerator.deviceNames.firstOrNull()
        videoCapturer = cameraEnumerator.createCapturer(camName, null)

        localVideoSource = factory!!.createVideoSource(false)
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoCapturer!!.initialize(surfaceHelper, context, localVideoSource!!.capturerObserver)
        videoCapturer!!.startCapture(1280, 720, 30)

        val localVideoTrack = factory!!.createVideoTrack("video0", localVideoSource)
        getLocalRenderer()?.let { localVideoTrack.addSink(it) }

        // 5) Локальный микрофон (Opus)
        val audioConstraints = MediaConstraints()
        localAudioSource = factory!!.createAudioSource(audioConstraints)
        val localAudioTrack = factory!!.createAudioTrack("audio0", localAudioSource)

        // 6) Unified Plan: добавляем два транспривера (SEND_RECV)
        val vTrans = pc!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )
        vTrans.sender.setTrack(localVideoTrack, false)

        val aTrans = pc!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )
        aTrans.sender.setTrack(localAudioTrack, false)

        // 7) Создаём offer
        val offerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc!!.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) = cont.resume(desc)
                override fun onCreateFailure(reason: String?) = cont.resumeWithException(RuntimeException("offer fail: $reason"))
                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, offerConstraints)
        }

        // 8) Мунжим SDP → оставляем VP8/Opus и выкидываем мусор
        val mungedOffer = SessionDescription(SessionDescription.Type.OFFER, preferVp8Opus(offer.description))

        // 9) Ставим локальное описание и ждём, пока соберутся ICE-кандидаты
        suspendCancellableCoroutine<Unit> { cont ->
            pc!!.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(reason: String?) = cont.resumeWithException(RuntimeException("setLocal fail: $reason"))
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, mungedOffer)
        }
        suspendCancellableCoroutine<Unit> { cont -> iceCompleteCont = cont }

        // 10) POST в OpenAI Realtime → получаем SDP answer
        val ephemeral = tokenProvider()
        val req = Request.Builder()
            .url(REALTIME_URL)
            .addHeader("Authorization", "Bearer $ephemeral")
            .addHeader("OpenAI-Beta", BETA_HDR)
            .addHeader("Content-Type", "application/sdp")
            .post(RequestBody.create("application/sdp".toMediaTypeOrNull(), mungedOffer.description))
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

        // 11) Ставит удалённое описание (answer) → готово
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        suspendCancellableCoroutine<Unit> { cont ->
            pc!!.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(reason: String?) = cont.resumeWithException(RuntimeException("setRemote fail: $reason"))
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, answer)
        }

        onState("connected")
    }

    fun disconnect() {
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        videoCapturer?.dispose()
        localVideoSource?.dispose()
        localAudioSource?.dispose()
        pc?.close()
        factory?.dispose()
        eglBase?.release()

        videoCapturer = null
        localVideoSource = null
        localAudioSource = null
        pc = null
        factory = null
        eglBase = null
    }

    // ================= Utils =================

    private fun preferVp8Opus(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()

        // Собираем карту pt -> codec
        val rtpmap = mutableMapOf<String, String>() // "111" -> "opus/48000/2", "96" -> "vp8/90000"
        lines.forEach { l ->
            if (l.startsWith("a=rtpmap:")) {
                val rest = l.removePrefix("a=rtpmap:")
                val sp = rest.indexOf(' ')
                if (sp > 0) {
                    val pt = rest.substring(0, sp).trim()
                    val codec = rest.substring(sp + 1).trim().lowercase()
                    rtpmap[pt] = codec
                }
            }
        }
        val opusPt = rtpmap.entries.firstOrNull { it.value.startsWith("opus/") }?.key
        val vp8Pt  = rtpmap.entries.firstOrNull { it.value.startsWith("vp8/") }?.key

        fun rewriteMLine(kind: String, preferPt: String?) {
            val idx = lines.indexOfFirst { it.startsWith("m=$kind ") }
            if (idx == -1 || preferPt == null) return
            val parts = lines[idx].split(" ").toMutableList()
            if (parts.size < 4) return
            val head = parts.take(3)
            val payloads = parts.drop(3)
            val newPayloads = listOf(preferPt) + payloads.filter { it == preferPt }
            // фактически оставляем только preferPt, что гарантирует «только Opus / только VP8»
            lines[idx] = (head + listOf(preferPt)).joinToString(" ")
        }

        // Уберём очевидный мусор из аудио
        val removeAudioPts = rtpmap.entries
            .filter { it.value.startsWith("cn/") || it.value.startsWith("telephone-event/") }
            .map { it.key }
            .toSet()

        // Переставим m=audio/m=video
        rewriteMLine("audio", opusPt)
        rewriteMLine("video", vp8Pt)

        // Почистим rtpmap/fmtp/rtcp-fb для выпиленных аудио payload’ов
        val keepPts = setOfNotNull(opusPt, vp8Pt)
        val cleaned = lines.filter { l ->
            if (l.startsWith("a=rtpmap:") || l.startsWith("a=fmtp:") || l.startsWith("a=rtcp-fb:")) {
                val colon = l.indexOf(':')
                val space = l.indexOf(' ')
                val pt = if (colon >= 0 && space > colon) l.substring(colon + 1, space) else ""
                // выкидываем «CN/telephone-event» и вообще всё, что не входит в выбранные pt
                !(removeAudioPts.contains(pt) || (pt.isNotEmpty() && pt !in keepPts))
            } else true
        }

        return cleaned.joinToString("\r\n")
    }

    private fun <T> setOfNotNull(vararg x: T?): Set<T> = x.filterNotNull().toSet()
}
