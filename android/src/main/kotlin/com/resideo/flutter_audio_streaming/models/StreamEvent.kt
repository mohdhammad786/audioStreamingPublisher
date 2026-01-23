package com.resideo.flutter_audio_streaming.models

/**
 * Events that can trigger state transitions.
 */
sealed class StreamEvent {
    object StartRequested : StreamEvent()
    object StartSuccess : StreamEvent()
    object StartFailed : StreamEvent()
    object InterruptionBegan : StreamEvent()
    object InterruptionEnded : StreamEvent()
    object ReconnectionStarted : StreamEvent()
    object ReconnectionSuccess : StreamEvent()
    object ReconnectionFailed : StreamEvent()
    object ExplicitStop : StreamEvent()
}
