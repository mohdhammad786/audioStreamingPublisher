package com.resideo.flutter_audio_streaming

/**
 * Interface for the RTMP/RTSP client to enable mocking.
 */
interface StreamingClient {
    val isStreaming: Boolean
    fun prepareAudio(bitrate: Int, sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean
    fun startStream(url: String)
    fun stopStream()
    fun disableAudio()
    fun enableAudio()
    fun reTry(delay: Long, reason: String): Boolean
}

/**
 * Interface for monitoring network connectivity.
 */
interface NetworkMonitorInterface {
    val isNetworkAvailable: Boolean
    fun startMonitoring()
    fun stopMonitoring()
}

/**
 * Interface for monitoring phone call states.
 */
interface PhoneCallMonitorInterface {
    val isCallActive: Boolean
    fun startMonitoring()
    fun stopMonitoring()
}

/**
 * Interface for managing Android Audio Focus.
 */
interface AudioFocusMonitorInterface {
    val hasFocus: Boolean
    fun requestFocus(): Boolean
    fun abandonFocus()
}

/**
 * Mediator interface for AudioStreaming to communicate with managers.
 */
interface StreamingMediator {
    fun onPhoneInterruptionBegan()
    fun onPhoneInterruptionEnded()
    fun onNetworkLost()
    fun onNetworkAvailable()
    fun onAudioFocusLostPermanently()
    fun onAudioFocusLostTransient()
}
