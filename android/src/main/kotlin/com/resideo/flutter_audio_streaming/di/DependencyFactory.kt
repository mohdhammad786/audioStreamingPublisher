package com.resideo.flutter_audio_streaming.di

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.resideo.flutter_audio_streaming.core.AudioStreaming
import com.resideo.flutter_audio_streaming.models.StreamingContext
import com.resideo.flutter_audio_streaming.services.*
import com.resideo.flutter_audio_streaming.utils.DartMessenger
import com.resideo.flutter_audio_streaming.services.FlutterEventMapper

class DependencyFactory(
    private val context: Context,
    private val messenger: DartMessenger
) {
    fun createAudioStreaming(): AudioStreaming {
        val streamingContext = StreamingContext()
        val mainHandler = Handler(Looper.getMainLooper())

        val interruptionManager = InterruptionManager(streamingContext, messenger)
        val rtmpConnectionHandler = RtmpConnectionHandler(interruptionManager, messenger, streamingContext)
        val rtmpClient = RtmpClientImpl(context, rtmpConnectionHandler)
        rtmpConnectionHandler.setClient(rtmpClient)

        val systemLifecycleObserver = SystemLifecycleObserver()
        val audioFocusManager = AudioFocusManager(context)
        val phoneCallManager = PhoneCallManager(context)
        val networkMonitor = NetworkMonitor(context)
        
        val flutterEventMapper = FlutterEventMapper(messenger, streamingContext, interruptionManager)
        
        val stateMachine = StreamStateMachine { oldState, newState, event ->
            flutterEventMapper.handleStateTransition(oldState, newState, event)
        }

        val reconnectionService = ReconnectionService(
            streamingContext,
            rtmpClient,
            interruptionManager,
            audioFocusManager,
            mainHandler,
            messenger
        )

        return AudioStreaming(
            context,
            streamingContext,
            messenger,
            interruptionManager,
            systemLifecycleObserver,
            rtmpConnectionHandler,
            rtmpClient,
            audioFocusManager,
            phoneCallManager,
            networkMonitor,
            flutterEventMapper,
            stateMachine,
            reconnectionService,
            mainHandler
        )
    }
}
