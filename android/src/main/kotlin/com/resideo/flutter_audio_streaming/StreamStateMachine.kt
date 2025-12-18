package com.resideo.flutter_audio_streaming

import android.util.Log

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

/**
 * The State Machine for Audio Streaming.
 * Centralizes all validation and determines which Flutter events to send.
 */
class StreamStateMachine(private val onTransition: (oldState: StreamState, newState: StreamState, event: StreamEvent) -> Unit) {
    
    private var currentState: StreamState = StreamState.IDLE
    private val lock = Any()

    companion object {
        private const val TAG = "StreamStateMachine"
    }

    fun getCurrentState(): StreamState = synchronized(lock) { currentState }

    fun transition(event: StreamEvent): Boolean {
        synchronized(lock) {
            val oldState = currentState
            val newState = when (event) {
                StreamEvent.StartRequested -> {
                    if (oldState == StreamState.IDLE || oldState == StreamState.FAILED) StreamState.PREPARING else null
                }
                StreamEvent.StartSuccess -> {
                    if (oldState == StreamState.PREPARING || oldState == StreamState.CONNECTING) StreamState.STREAMING else null
                }
                StreamEvent.StartFailed -> StreamState.FAILED
                
                StreamEvent.InterruptionBegan -> {
                    if (oldState == StreamState.STREAMING || oldState == StreamState.PREPARING || oldState == StreamState.RECONNECTING || oldState == StreamState.CONNECTING) {
                        StreamState.INTERRUPTED
                    } else null
                }
                StreamEvent.InterruptionEnded -> {
                    // Logic for ending interruption is handled in AudioStreaming via flags, 
                    // but we validate the switch from INTERRUPTED to RECONNECTING separately.
                    null 
                }
                StreamEvent.ReconnectionStarted -> {
                    if (oldState == StreamState.INTERRUPTED) StreamState.RECONNECTING else null
                }
                StreamEvent.ReconnectionSuccess -> {
                    if (oldState == StreamState.RECONNECTING || oldState == StreamState.INTERRUPTED) StreamState.STREAMING else null
                }
                StreamEvent.ReconnectionFailed -> StreamState.FAILED
                
                StreamEvent.ExplicitStop -> StreamState.IDLE
            }

            if (newState != null && newState != oldState) {
                currentState = newState
                Log.i(TAG, "STATE CHANGE: $oldState --($event)--> $newState")
                onTransition(oldState, newState, event)
                return true
            } else if (newState == null) {
                Log.w(TAG, "INVALID TRANSITION: $oldState cannot handle $event")
                return false
            }
            return true // Same state, no change needed
        }
    }
}
