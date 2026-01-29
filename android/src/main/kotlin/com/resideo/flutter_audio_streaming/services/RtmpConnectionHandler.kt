package com.resideo.flutter_audio_streaming.services

import android.util.Log
import com.resideo.flutter_audio_streaming.utils.DartMessenger
import com.resideo.flutter_audio_streaming.interfaces.StreamingClient
import com.resideo.flutter_audio_streaming.interfaces.StreamingMediator
import com.resideo.flutter_audio_streaming.models.StreamEvent
import com.resideo.flutter_audio_streaming.models.StreamState
import com.resideo.flutter_audio_streaming.models.StreamingContext

class RtmpConnectionHandler(
    private val interruptionManager: InterruptionManager,
    private val dartMessenger: DartMessenger?,
    private val streamingContext: StreamingContext
) {

    lateinit var mediator: StreamingMediator
    lateinit var stopStream: () -> Unit

    companion object {
        private const val TAG = "RtmpConnectionHandler"
    }

    private var client: StreamingClient? = null

    fun setClient(client: StreamingClient) {
        this.client = client
    }

    fun notifyConnected() {
        mediator.runOnMainThread {
            val currentState = mediator.getStreamState()
            if (currentState == StreamState.RECONNECTING || currentState == StreamState.INTERRUPTED) {
                mediator.transitionTo(StreamEvent.ReconnectionSuccess)
            } else {
                mediator.transitionTo(StreamEvent.StartSuccess)
            }
        }
    }

    fun notifyDisconnected(code: String? = null, description: String? = null) {
        Log.i(TAG, "RTMP Connection Disconnected/Failed - Code: $code, Desc: $description")

        mediator.runOnMainThread {
            // CRITICAL: Check if we are intentionally stopping the stream for an interruption.
            // If so, ignore this disconnect callback to prevent race conditions that cause
            // duplicate rtmp_stopped events or state corruption.
            if (streamingContext.isExpectingSafetyDisconnect) {
                Log.d(TAG, "Ignoring disconnect - isExpectingSafetyDisconnect is true (intentional stop for interruption)")
                return@runOnMainThread
            }
            
            val currentState = mediator.getStreamState()
            Log.d(TAG, "Processing disconnect in state: $currentState")

            // If already in terminal state, ignore
            if (currentState == StreamState.FAILED || currentState == StreamState.IDLE) {
                Log.d(TAG, "Already in terminal state $currentState - ignoring disconnect")
                return@runOnMainThread
            }

            // If already INTERRUPTED, ignore - let 30s timer handle final stop
            if (currentState == StreamState.INTERRUPTED) {
                Log.d(TAG, "Disconnected while INTERRUPTED - ignoring to preserve state for 30s timer")
                return@runOnMainThread
            }
            
            // If reconnecting, ignore - let ReconnectionService handle result
            if (currentState == StreamState.RECONNECTING) {
                Log.d(TAG, "Disconnected while RECONNECTING - letting ReconnectionService handle")
                return@runOnMainThread
            }

            // ALL other disconnects should be treated as network interruption
            // The 30s timer will send rtmp_stopped if not resumed in time
            Log.w(TAG, "Disconnected while $currentState - treating as Network Interruption")
            interruptionManager.handleNetworkLost()
        }
    }

    private fun isNetworkRelatedError(description: String): Boolean {
        val keywords = listOf(
            "network", "timeout", "unreachable", "connection refused",
            "no route", "socket", "broken pipe", "failed to connect",
            "host", "resolve", "dns", "ioexception",
            "software", "abort", "connection reset", "etimedout", "ehostunreach",
            "connection abort"
        )
        val lowerDesc = description.lowercase()
        return keywords.any { lowerDesc.contains(it) }
    }

    fun notifyAuthError(code: String?) {
        Log.e(TAG, "Auth error: $code")
        mediator.runOnMainThread {
            dartMessenger?.send(DartMessenger.EventType.ERROR, "Auth error")
            stopStream()
        }
    }
}
