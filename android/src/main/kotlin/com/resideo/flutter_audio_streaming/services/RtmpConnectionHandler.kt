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

        mediator.runOnMainThread {
            val currentState = mediator.getStreamState()

            // 1. If we are already INTERRUPTED, this disconnection is likely due to us stopping the stream
            //    or network loss that triggered the interruption. We should ignore it to preserve the
            //    INTERRUPTED state so we can resume later.
            if (currentState == StreamState.INTERRUPTED) {
                Log.d(TAG, "Disconnected while INTERRUPTED - ignoring to preserve state for resumption")
                return@runOnMainThread
            }

            // 2. If we were streaming, assume network interruption first
            // This catches the case where the socket breaks (e.g. internet off) but we want to retry
            if (currentState == StreamState.STREAMING || currentState == StreamState.RECONNECTING) {
                 Log.w(TAG, "Disconnected while $currentState - treating as Network Interruption")
                 interruptionManager.handleNetworkLost()
                 return@runOnMainThread
            }
 
            // Check for network-related errors to trigger interruption instead of failure
            if (description != null && isNetworkRelatedError(description)) {
                Log.w(TAG, "Network error detected from RTMP: $description - Treating as Network Interruption")
                interruptionManager.handleNetworkLost()
                return@runOnMainThread
            }
 
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
