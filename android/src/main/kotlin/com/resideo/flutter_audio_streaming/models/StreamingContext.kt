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
    
    // Volatile flags (Note: data classes don't support @Volatile on fields directly in the same way, 
    // but we can wrap them or keep them in the main class if they need atomic access. 
    // For now, let's keep volatile flags in the main class for thread safety or use AtomicBoolean here)
) {
    fun clear() {
        activeUrl = null
        pendingReconnectOnResume = false
        currentInterruptionSource = InterruptionSource.NONE
    }
}
