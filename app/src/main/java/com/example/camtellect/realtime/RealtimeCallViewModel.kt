package com.example.camtellect.realtime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camtellect.Http
import kotlinx.coroutines.flow.StateFlow

class RealtimeCallViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RealtimeSessionRepository()
    private val manager = RealtimeSessionManager(
        context = application.applicationContext,
        scope = viewModelScope,
        repository = repository,
        httpClient = Http.client
    )

    val connectionState: StateFlow<CallConnectionState> = manager.connectionState
    val microphoneLevel: StateFlow<Float> = manager.microphoneLevel
    val assistantLevel: StateFlow<Float> = manager.assistantLevel
    val isMicMuted: StateFlow<Boolean> = manager.isMicMuted
    val isCameraMuted: StateFlow<Boolean> = manager.isCameraMuted
    val isAssistantSpeaking: StateFlow<Boolean> = manager.isAssistantSpeaking
    val localVideoTrack: StateFlow<org.webrtc.VideoTrack?> = manager.localVideoTrack
    val remoteVideoTrack: StateFlow<org.webrtc.VideoTrack?> = manager.remoteVideoTrack

    val eglContext = manager.eglBaseContext()

    fun startPreview() {
        manager.startLocalPreview()
    }

    fun connect() {
        manager.connect()
    }

    fun disconnect() {
        manager.disconnect()
    }

    fun toggleMute() {
        manager.toggleMute()
    }

    fun toggleCamera() {
        manager.toggleCamera()
    }

    override fun onCleared() {
        super.onCleared()
        manager.release()
    }
}
