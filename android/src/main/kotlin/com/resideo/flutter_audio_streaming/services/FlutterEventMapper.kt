package com.resideo.flutter_audio_streaming.services

import com.resideo.flutter_audio_streaming.utils.DartMessenger
import com.resideo.flutter_audio_streaming.models.InterruptionSource
import com.resideo.flutter_audio_streaming.models.StreamEvent
import com.resideo.flutter_audio_streaming.models.StreamState
import com.resideo.flutter_audio_streaming.models.StreamingContext

class FlutterEventMapper(
    private val dartMessenger: DartMessenger?,
    private val streamingContext: StreamingContext,
    private val interruptionManager: InterruptionManager
) {

    fun handleStateTransition(oldState: StreamState, newState: StreamState, event: StreamEvent) {
        when (newState) {
            StreamState.STREAMING -> {
                if (event == StreamEvent.ReconnectionSuccess) {
                    val eventType = when (streamingContext.reconnectionSource) {
                        InterruptionSource.NETWORK -> DartMessenger.EventType.NETWORK_RESUMED
                        else -> DartMessenger.EventType.AUDIO_RESUMED
                    }
                    dartMessenger?.send(eventType, "Stream resumed successfully")
                    streamingContext.reconnectionSource = InterruptionSource.NONE
                } else if (event == StreamEvent.StartSuccess) {
                    dartMessenger?.send(DartMessenger.EventType.RTMP_STARTED, "Connection success")
                }
            }
            StreamState.INTERRUPTED -> {
                val eventType = when (streamingContext.currentInterruptionSource) {
                    InterruptionSource.PHONE_CALL -> DartMessenger.EventType.AUDIO_INTERRUPTED
                    InterruptionSource.NETWORK -> DartMessenger.EventType.NETWORK_INTERRUPTED
                    else -> return
                }
                val remaining = interruptionManager.getRemainingInterruptionSeconds()
                val extras = mapOf("remainingSeconds" to remaining)
                dartMessenger?.send(eventType, "Stream paused due to ${streamingContext.currentInterruptionSource}", extras)
            }
            StreamState.FAILED -> {
                val error = streamingContext.lastError ?: "Stream connection failed"
                dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, error)
                streamingContext.lastError = null
            }
            StreamState.IDLE -> {
                 if (event == StreamEvent.ExplicitStop) {
                     dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Stream stopped")
                 }
            }
            else -> {
                // Other states don't necessarily trigger events
            }
        }
    }
}
