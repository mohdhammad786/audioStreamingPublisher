package com.resideo.flutter_audio_streaming.models

data class StreamingContext(
    var activeUrl: String? = null,
    var bitrate: Int? = null,
    var sampleRate: Int? = null,
    var isStereo: Boolean? = false,
    var echoCanceler: Boolean? = false,
    var noiseSuppressor: Boolean? = false,
    
    // State flags that were previously in AudioStreaming
    var pendingReconnectOnResume: Boolean = false,
    var isInForeground: Boolean = false,
    var currentInterruptionSource: InterruptionSource = InterruptionSource.NONE,
    var reconnectionSource: InterruptionSource = InterruptionSource.NONE,
    var lastError: String? = null,
    
    // Explicit flag to ignore disconnection events when we INTENTIONALLY stop stream (e.g. for interruption)
    // This prevents race conditions where disconnect callbacks arrive before state transition completes
    @Volatile var isExpectingSafetyDisconnect: Boolean = false
) {
    fun clear() {
        activeUrl = null
        pendingReconnectOnResume = false
        currentInterruptionSource = InterruptionSource.NONE
        reconnectionSource = InterruptionSource.NONE
        lastError = null
        isExpectingSafetyDisconnect = false
    }
}
