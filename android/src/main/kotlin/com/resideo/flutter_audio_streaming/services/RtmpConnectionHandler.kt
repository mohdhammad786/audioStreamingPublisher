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

    fun notifyDisconnected() {
        Log.i(TAG, "RTMP Connection Disconnected/Failed")
        mediator.runOnMainThread {
            val currentState = mediator.getStreamState()
            if (currentState != StreamState.IDLE) {
                mediator.transitionTo(StreamEvent.ReconnectionFailed)
            }
        }
    }

    fun notifyAuthError(code: String?) {
        Log.e(TAG, "Auth error: $code")
        mediator.runOnMainThread {
            dartMessenger?.send(DartMessenger.EventType.ERROR, "Auth error")
            stopStream()
        }
    }
}
