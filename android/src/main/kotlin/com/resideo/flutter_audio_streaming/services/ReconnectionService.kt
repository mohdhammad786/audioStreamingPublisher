package com.resideo.flutter_audio_streaming.services

import android.os.Handler
import android.util.Log
import com.resideo.flutter_audio_streaming.utils.DartMessenger
import com.resideo.flutter_audio_streaming.interfaces.AudioFocusMonitorInterface
import com.resideo.flutter_audio_streaming.interfaces.StreamingClient
import com.resideo.flutter_audio_streaming.interfaces.StreamingMediator
import com.resideo.flutter_audio_streaming.models.StreamEvent
import com.resideo.flutter_audio_streaming.models.StreamState
import com.resideo.flutter_audio_streaming.models.StreamingContext
import com.resideo.flutter_audio_streaming.models.InterruptionSource

class ReconnectionService(
    private val streamingContext: StreamingContext,
    private val client: StreamingClient,
    private val interruptionManager: InterruptionManager,
    private val audioFocusManager: AudioFocusMonitorInterface,
    private val mainHandler: Handler,
    private val dartMessenger: DartMessenger?
) {
    lateinit var mediator: StreamingMediator
    lateinit var prepareStream: () -> Boolean
    lateinit var stopStream: () -> Unit

    companion object {
        private const val TAG = "ReconnectionService"
    }

    fun reconnectStream() {
        val url = streamingContext.activeUrl
        if (url == null) {
            Log.e(TAG, "Cannot reconnect - activeUrl is null")
            handleReconnectionFailure("Configuration lost")
            return
        }

        if (mediator.getStreamState() != StreamState.INTERRUPTED) {
            Log.w(TAG, "reconnectStream called but state is not INTERRUPTED (State: ${mediator.getStreamState()})")
            return
        }
        
        Log.i(TAG, "Scheduling reconnection (500ms delay for Mic release)...")

        // Run reconnection on Main Thread with delay
        mainHandler.postDelayed({
            // Re-check state after delay
            if (streamingContext.currentInterruptionSource != InterruptionSource.NONE || mediator.getStreamState() != StreamState.INTERRUPTED) {
                Log.w(TAG, "Reconnection aborted - state changed during delay")
                return@postDelayed
            }

            mediator.transitionTo(StreamEvent.ReconnectionStarted)

            try {
                Log.d(TAG, "Starting reconnection sequence on Main Thread...")

                // 1. Ensure clean slate (Stop RTMP only, don't reset full state)
                try { client.stopStream() } catch (e: Exception) {}

                // 2. Force Audio Prepare (Re-initializes buffers/encoders)
                val prepared = prepareStream()
                if (!prepared) {
                     Log.e(TAG, "Failed to re-prepare audio components")
                     handleReconnectionFailure("Device prepare failed")
                     return@postDelayed
                }
                
                // 3. Acquire Focus
                if (!audioFocusManager.requestFocus()) {
                    Log.e(TAG, "Failed to acquire audio focus for reconnection")
                    handleReconnectionFailure("Could not regain audio focus")
                    return@postDelayed
                }

                // 4. Start RTMP Stream
                Log.i(TAG, "🚀 Restarting RTMP stream to $url")
                client.startStream(url)

            } catch (e: Exception) {
                Log.e(TAG, "Reconnection exception: ${e.message}")
                handleReconnectionFailure(e.message ?: "Unknown error")
            }
        }, 1000)
    }

    private fun handleReconnectionFailure(reason: String) {
        Log.e(TAG, "Reconnection failed: $reason")

        // Clean up normally
        stopStream()
        
        // Store error for Mapper
        streamingContext.lastError = reason
        mediator.transitionTo(StreamEvent.ReconnectionFailed)
    }
}
