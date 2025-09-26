package com.example.camtellect.realtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import kotlin.math.sqrt

sealed class CallConnectionState {
    object Idle : CallConnectionState()
    object Connecting : CallConnectionState()
    object Connected : CallConnectionState()
    data class Failed(val reason: String) : CallConnectionState()
}

class RealtimeSessionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: RealtimeSessionRepository,
    private val httpClient: OkHttpClient,
) {
    private val tag = "RTRTC"

    private val _connectionState = MutableStateFlow<CallConnectionState>(CallConnectionState.Idle)
    val connectionState: StateFlow<CallConnectionState> = _connectionState.asStateFlow()

    private val _microphoneLevel = MutableStateFlow(0f)
    val microphoneLevel: StateFlow<Float> = _microphoneLevel.asStateFlow()

    private val _assistantLevel = MutableStateFlow(0f)
    val assistantLevel: StateFlow<Float> = _assistantLevel.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isCameraMuted = MutableStateFlow(false)
    val isCameraMuted: StateFlow<Boolean> = _isCameraMuted.asStateFlow()

    private val _isAssistantSpeaking = MutableStateFlow(false)
    val isAssistantSpeaking: StateFlow<Boolean> = _isAssistantSpeaking.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val eglBase: EglBase = EglBase.create()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrackInternal: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null
    private var peerConnection: PeerConnection? = null
    private var websocket: WebSocket? = null

    private var signalingJob: Job? = null

    fun eglBaseContext(): EglBase.Context = eglBase.eglBaseContext

    fun startLocalPreview() {
        scope.launch(Dispatchers.Main) {
            ensurePeerConnectionFactory()
            if (localVideoTrackInternal == null) {
                createVideoTrack()
            }
        }
    }

    fun toggleMute() {
        val newValue = !_isMicMuted.value
        _isMicMuted.value = newValue
        localAudioTrack?.setEnabled(!newValue)
    }

    fun toggleCamera() {
        val newValue = !_isCameraMuted.value
        _isCameraMuted.value = newValue
        localVideoTrackInternal?.setEnabled(!newValue)
    }

    fun connect() {
        if (_connectionState.value == CallConnectionState.Connecting ||
            _connectionState.value == CallConnectionState.Connected
        ) {
            Log.d(tag, "Already connected or connecting")
            return
        }
        _connectionState.value = CallConnectionState.Connecting
        signalingJob = scope.launch {
            try {
                val session = repository.fetchSession()
                withContext(Dispatchers.Main) {
                    ensurePeerConnectionFactory()
                    if (localAudioTrack == null) createAudioTrack()
                    if (localVideoTrackInternal == null) createVideoTrack()
                    buildPeerConnection(session)
                    openSignalingWebSocket(session)
                }
            } catch (t: Throwable) {
                Log.e(tag, "Connection failed", t)
                _connectionState.value = CallConnectionState.Failed(t.message ?: "Connection failed")
                disconnect()
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.Main) {
            signalingJob?.cancel()
            signalingJob = null
            websocket?.close(1000, "disconnect")
            websocket = null
            peerConnection?.close()
            peerConnection = null
            _remoteVideoTrack.value = null
            _isAssistantSpeaking.value = false
            _connectionState.value = CallConnectionState.Idle
        }
    }

    fun release() {
        disconnect()
        scope.launch(Dispatchers.Main) {
            localAudioTrack?.dispose()
            audioSource?.dispose()
            audioDeviceModule?.release()
            localAudioTrack = null
            audioSource = null
            audioDeviceModule = null

            localVideoTrackInternal?.dispose()
            videoSource?.dispose()
            videoCapturer?.stopCaptureSafely()
            videoCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            localVideoTrackInternal = null
            _localVideoTrack.value = null
            videoSource = null
            videoCapturer = null
            surfaceTextureHelper = null

            eglBase.release()
        }
    }

    private suspend fun ensurePeerConnectionFactory() {
        if (peerConnectionFactory != null) return
        withContext(Dispatchers.Main) {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val audioModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                        Log.e(tag, "AudioRecord init error: $errorMessage")
                    }

                    override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?) {
                        Log.e(tag, "AudioRecord start error: $errorCode $errorMessage")
                    }

                    override fun onWebRtcAudioRecordError(errorMessage: String?) {
                        Log.e(tag, "AudioRecord error: $errorMessage")
                    }
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                        Log.e(tag, "AudioTrack init error: $errorMessage")
                    }

                    override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?, errorMessage: String?) {
                        Log.e(tag, "AudioTrack start error: $errorCode $errorMessage")
                    }

                    override fun onWebRtcAudioTrackError(errorMessage: String?) {
                        Log.e(tag, "AudioTrack error: $errorMessage")
                    }
                })
                .setSamplesReadyCallback { samples ->
                    if (samples.audioSampleType == JavaAudioDeviceModule.AudioSamples.AudioSampleType.AudioSampleTypeCapture) {
                        _microphoneLevel.value = samples.calculateLevel()
                    }
                }
                .setAudioTrackSamplesReadyCallback { samples ->
                    if (samples.audioSampleType == JavaAudioDeviceModule.AudioSamples.AudioSampleType.AudioSampleTypeRender) {
                        val level = samples.calculateLevel()
                        _assistantLevel.value = level
                        _isAssistantSpeaking.value = level > 0.05f
                    }
                }
                .createAudioDeviceModule()
            audioDeviceModule = audioModule

            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
        }
    }

    private fun createAudioTrack() {
        val factory = peerConnectionFactory ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val source = factory.createAudioSource(constraints)
        audioSource = source
        localAudioTrack = factory.createAudioTrack("ARDAMSa0", source).apply {
            setEnabled(!_isMicMuted.value)
        }
    }

    private fun createVideoTrack() {
        val factory = peerConnectionFactory ?: return
        val capturer = createCameraCapturer() ?: run {
            Log.e(tag, "No camera capturer available")
            return
        }
        videoCapturer = capturer
        val helper = SurfaceTextureHelper.create("RTCVideoCapturerThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper
        val source = factory.createVideoSource(false)
        videoSource = source
        capturer.initialize(helper, context, source.capturerObserver)
        try {
            capturer.startCapture(1280, 720, 30)
        } catch (ex: Exception) {
            Log.e(tag, "Unable to start capture", ex)
        }
        localVideoTrackInternal = factory.createVideoTrack("ARDAMSv0", source).apply {
            setEnabled(!_isCameraMuted.value)
        }
        _localVideoTrack.value = localVideoTrackInternal
    }

    private fun buildPeerConnection(session: RealtimeSessionInfo) {
        val factory = peerConnectionFactory ?: return
        val rtcConfig = PeerConnection.RTCConfiguration(
            if (session.iceServers.isEmpty()) {
                listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            } else {
                session.iceServers.map { server ->
                    val builder = PeerConnection.IceServer.builder(server.urls)
                    if (!server.username.isNullOrEmpty() && !server.credential.isNullOrEmpty()) {
                        builder.setUsername(server.username)
                        builder.setPassword(server.credential)
                    }
                    builder.createIceServer()
                }
            }
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                Log.d(tag, "Signaling state: $newState")
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(tag, "ICE state: $newState")
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> _connectionState.value = CallConnectionState.Connected
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.CLOSED,
                    PeerConnection.IceConnectionState.FAILED -> if (_connectionState.value != CallConnectionState.Idle) {
                        _connectionState.value = CallConnectionState.Failed("ICE state $newState")
                    }
                    else -> {}
                }
            }

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                Log.d(tag, "ICE gathering: $newState")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                Log.d(tag, "Local ICE candidate: ${candidate.sdp}")
                sendIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: org.webrtc.MediaStream?) {}

            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}

            override fun onDataChannel(dc: org.webrtc.DataChannel?) {}

            override fun onRenegotiationNeeded() {
                Log.d(tag, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                val track = receiver?.track() ?: return
                Log.d(tag, "Remote track added: ${track.kind()} state=${track.state()}")
                if (track is AudioTrack) {
                    track.setEnabled(true)
                } else if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() ?: return
                Log.d(tag, "Transceiver track: ${track.kind()} state=${track.state()}")
                if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
                }
            }
        }
        val pc = factory.createPeerConnection(rtcConfig, observer)
        peerConnection = pc
        pc?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
        pc?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
        localAudioTrack?.let { track ->
            pc?.addTrack(track)
        }
        localVideoTrackInternal?.let { track ->
            pc?.addTrack(track)
        }
    }

    private fun openSignalingWebSocket(session: RealtimeSessionInfo) {
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=${session.model}")
            .addHeader("Authorization", "Bearer ${session.clientSecret}")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()
        websocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.i(tag, "WebSocket opened")
                createOffer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleSignalingMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(tag, "WebSocket failure", t)
                _connectionState.value = CallConnectionState.Failed(t.message ?: "WebSocket failure")
                disconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WebSocket closed: $code $reason")
                if (_connectionState.value == CallConnectionState.Connected) {
                    _connectionState.value = CallConnectionState.Idle
                }
            }
        })
    }

    private fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(SimpleSdpObserver(), desc)
                val message = JSONObject().apply {
                    put("type", "webrtc/offer")
                    put("sdp", desc.description)
                }
                websocket?.send(message.toString())
            }
        }, constraints)
    }

    private fun handleSignalingMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "webrtc/answer" -> {
                    val sdp = json.optString("sdp")
                    if (!sdp.isNullOrEmpty()) {
                        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                        peerConnection?.setRemoteDescription(SimpleSdpObserver(), desc)
                    }
                }
                "webrtc/ice-candidate" -> {
                    val candidateJson = json.optJSONObject("candidate") ?: return
                    val candidate = IceCandidate(
                        candidateJson.optString("sdpMid"),
                        candidateJson.optInt("sdpMLineIndex"),
                        candidateJson.optString("candidate")
                    )
                    peerConnection?.addIceCandidate(candidate)
                }
                "session.created" -> {
                    Log.i(tag, "Session created: $message")
                }
                else -> {
                    Log.d(tag, "Unknown signaling message: $message")
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Error parsing signaling message: $message", t)
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val message = JSONObject().apply {
            put("type", "webrtc/ice-candidate")
            put("candidate", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }
        websocket?.send(message.toString())
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        return if (Camera2Enumerator.isSupported(context)) {
            createCameraCapturer(Camera2Enumerator(context))
        } else {
            createCameraCapturer(Camera1Enumerator(false))
        }
    }

    private fun createCameraCapturer(enumerator: org.webrtc.CameraEnumerator): CameraVideoCapturer? {
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    return capturer
                }
            }
        }
        for (deviceName in deviceNames) {
            val capturer = enumerator.createCapturer(deviceName, null)
            if (capturer != null) {
                return capturer
            }
        }
        return null
    }

    private fun VideoCapturer.stopCaptureSafely() {
        try {
            if (this is CameraVideoCapturer) {
                stopCapture()
            }
        } catch (t: Throwable) {
            Log.e(tag, "stopCapture error", t)
        }
    }

    private fun JavaAudioDeviceModule.AudioSamples.calculateLevel(): Float {
        val shortBuffer = data.asShortBuffer()
        if (!shortBuffer.hasRemaining()) return 0f
        val samples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(samples)
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        val rms = sqrt(sum / samples.size)
        val level = (rms / Short.MAX_VALUE).toFloat()
        return level.coerceIn(0f, 1f)
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
