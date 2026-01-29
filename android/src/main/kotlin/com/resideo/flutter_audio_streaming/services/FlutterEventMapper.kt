package com.resideo.flutter_audio_streaming.services

import android.util.Log
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
    companion object {
        private const val TAG = "FlutterEventMapper"
    }

    // Guard against duplicate RTMP_STOPPED events
    private var lastSentStopEvent = false

    fun handleStateTransition(oldState: StreamState, newState: StreamState, event: StreamEvent) {
        if (oldState == newState) return // Prevent duplicate events for same-state transitions

        when (newState) {
            StreamState.STREAMING -> {
                // Reset stop event guard when streaming starts/resumes
                lastSentStopEvent = false
                
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
                // Only send interruption event if this is a NEW interruption (not returning from failed reconnect)
                if (event == StreamEvent.InterruptionBegan) {
                    val eventType = when (streamingContext.currentInterruptionSource) {
                        InterruptionSource.PHONE_CALL -> DartMessenger.EventType.AUDIO_INTERRUPTED
                        InterruptionSource.NETWORK -> DartMessenger.EventType.NETWORK_INTERRUPTED
                        else -> return
                    }
                    val remaining = interruptionManager.getRemainingInterruptionSeconds()
                    val extras = mapOf("remainingSeconds" to remaining)
                    dartMessenger?.send(eventType, "Stream paused due to ${streamingContext.currentInterruptionSource}", extras)
                }
                // If event is ReconnectionFailed, silently return to INTERRUPTED - no event to Flutter
            }
            StreamState.FAILED -> {
                // CRITICAL: Only send RTMP_STOPPED on TimeoutExpired (30s timer), NOT on internal failures
                if (event != StreamEvent.TimeoutExpired) {
                    Log.d(TAG, "Suppressing RTMP_STOPPED - event is $event, not TimeoutExpired")
                    return
                }
                if (lastSentStopEvent) {
                    Log.d(TAG, "Suppressing duplicate RTMP_STOPPED event (transition to FAILED)")
                    return
                }
                val error = streamingContext.lastError ?: "Stream stopped due to timeout"
                dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, error)
                lastSentStopEvent = true
                streamingContext.lastError = null
            }
            StreamState.IDLE -> {
                 if (event == StreamEvent.ExplicitStop) {
                     if (lastSentStopEvent) {
                         Log.d(TAG, "Suppressing duplicate RTMP_STOPPED event (transition to IDLE)")
                         return
                     }
                     dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Stream stopped")
                     lastSentStopEvent = true
                 }
            }
            else -> {
                // Other states don't necessarily trigger events
            }
        }
    }
    
    /**
     * Reset the event mapper state. Call this when starting a new stream.
     */
    fun reset() {
        lastSentStopEvent = false
    }
}
