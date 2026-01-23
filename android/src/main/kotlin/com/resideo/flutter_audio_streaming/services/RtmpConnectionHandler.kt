package com.resideo.flutter_audio_streaming.services

import android.util.Log
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.resideo.flutter_audio_streaming.utils.DartMessenger
import com.resideo.flutter_audio_streaming.interfaces.StreamingClient
import com.resideo.flutter_audio_streaming.interfaces.StreamingMediator
import com.resideo.flutter_audio_streaming.models.StreamEvent
import com.resideo.flutter_audio_streaming.models.StreamState

class RtmpConnectionHandler(
    private val interruptionManager: InterruptionManager,
    private val dartMessenger: DartMessenger?
) : ConnectCheckerRtmp {

    lateinit var mediator: StreamingMediator
    lateinit var stopStream: () -> Unit


    companion object {
        private const val TAG = "RtmpConnectionHandler"
    }

    private var client: StreamingClient? = null

    fun setClient(client: StreamingClient) {
        this.client = client
    }

    override fun onConnectionStartedRtmp(rtmpUrl: String) {
        Log.i(TAG, "RTMP Connection Started: $rtmpUrl")
    }

    override fun onConnectionSuccessRtmp() {
        Log.i(TAG, "✅ RTMP Connection Successful")

        val currentState = mediator.getStreamState()
        val wasReconnecting = (currentState == StreamState.RECONNECTING || currentState == StreamState.INTERRUPTED)
        
        if (wasReconnecting) {
            mediator.transitionTo(StreamEvent.ReconnectionSuccess)
        } else {
            mediator.transitionTo(StreamEvent.StartSuccess)
        }
    }

    override fun onConnectionFailedRtmp(reason: String) {
        Log.e(TAG, "❌ RTMP Connection Failed: $reason")

        val currentState = mediator.getStreamState()

        if (currentState == StreamState.IDLE) {
            return
        }

        // Check if this looks like a network issue
        if (isNetworkRelatedError(reason) && (currentState == StreamState.STREAMING || currentState == StreamState.RECONNECTING)) {
            Log.i(TAG, "RTMP failure appears network-related, triggering network interruption flow")
            interruptionManager.handleNetworkLost() // This handles flags and state transition
            return
        }

        // Non-network errors or if not streaming: use existing retry logic
        mediator.runOnMainThread {
             val client = this.client
             if (client != null && client.reTry(5000, reason)) {
                 // Internal retry - no event sent to client as requested
                 Log.d(TAG, "Retrying connection internally: $reason")
             } else {
                 handleReconnectionFailure("RTMP connection failed after retries: $reason")
             }
        }
    }

    private fun handleReconnectionFailure(reason: String) {
        Log.e(TAG, "Reconnection failed: $reason")

        // Clean up normally
        stopStream()
        mediator.transitionTo(StreamEvent.ReconnectionFailed)

        // Send STOPPED event so UI knows we are done
        mediator.runOnMainThread {
            dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, reason)
        }
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
        // Log.v(TAG, "Bitrate: $bitrate")
    }

    override fun onDisconnectRtmp() {
        Log.d(TAG, "RTMP Disconnected callback")
        
        val currentState = mediator.getStreamState()
        if (currentState == StreamState.INTERRUPTED || currentState == StreamState.RECONNECTING) {
             Log.d(TAG, "Ignored explicit disconnect callback during interruption/reconnection flow")
             return
        }
        
        // Normal disconnect (e.g., server closed connection)
        mediator.runOnMainThread {
            dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Server disconnected")
            stopStream()
        }
    }

    override fun onAuthErrorRtmp() {
        Log.e(TAG, "Auth error")
        mediator.runOnMainThread {
            dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Auth error")
            stopStream()
        }
    }

    override fun onAuthSuccessRtmp() {
        Log.i(TAG, "Auth success")
    }

    private fun isNetworkRelatedError(reason: String): Boolean {
        val networkKeywords = listOf(
            "network", "timeout", "unreachable", "connection refused",
            "no route", "socket", "broken pipe", "failed to connect",
            "host", "resolve", "dns", "ioexception",
            "software", "abort", "connection reset"
        )
        val lowerReason = reason.lowercase()
        return networkKeywords.any { lowerReason.contains(it) }
    }
}
