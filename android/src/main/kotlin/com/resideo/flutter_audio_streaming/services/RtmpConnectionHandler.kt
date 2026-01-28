package com.resideo.flutter_audio_streaming.services

import android.util.Log
import com.resideo.flutter_audio_streaming.utils.DartMessenger
import com.resideo.flutter_audio_streaming.interfaces.StreamingClient
import com.resideo.flutter_audio_streaming.interfaces.StreamingMediator
import com.resideo.flutter_audio_streaming.models.StreamEvent
import com.resideo.flutter_audio_streaming.models.StreamState

class RtmpConnectionHandler(
    private val interruptionManager: InterruptionManager,
    private val dartMessenger: DartMessenger?
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
            if (currentState == StreamState.RECONNECTING) {
                mediator.transitionTo(StreamEvent.ReconnectionSuccess)
            } else {
                mediator.transitionTo(StreamEvent.StartSuccess)
            }
        }
    }

    fun notifyDisconnected(code: String? = null, description: String? = null) {
        Log.i(TAG, "RTMP Connection Disconnected/Failed - Code: $code, Desc: $description")

        // Check for network-related errors to trigger interruption instead of failure
        if (description != null && isNetworkRelatedError(description)) {
            Log.w(TAG, "Network error detected from RTMP: $description - Treating as Network Interruption")
            interruptionManager.handleNetworkLost()
            return
        }

        mediator.runOnMainThread {
            val currentState = mediator.getStreamState()
            if (currentState != StreamState.IDLE) {
                mediator.transitionTo(StreamEvent.ReconnectionFailed)
            }
        }
    }

    private fun isNetworkRelatedError(description: String): Boolean {
        val keywords = listOf(
            "network", "timeout", "unreachable", "connection refused",
            "no route", "socket", "broken pipe", "failed to connect",
            "host", "resolve", "dns", "ioexception",
            "software", "abort", "connection reset", "etimedout", "ehostunreach"
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
