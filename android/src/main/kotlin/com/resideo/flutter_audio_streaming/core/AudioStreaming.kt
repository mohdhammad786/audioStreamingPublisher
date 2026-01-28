package com.resideo.flutter_audio_streaming.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.MethodChannel

import com.resideo.flutter_audio_streaming.models.*
import com.resideo.flutter_audio_streaming.services.*
import com.resideo.flutter_audio_streaming.utils.*
import com.resideo.flutter_audio_streaming.interfaces.*

class AudioStreaming(
    context: Context,
    private val streamingContext: StreamingContext,
    private val dartMessenger: DartMessenger,
    private val interruptionManager: InterruptionManager,
    private val systemLifecycleObserver: SystemLifecycleObserver,
    private val rtmpConnectionHandler: RtmpConnectionHandler,
    private val rtmpAudio: StreamingClient,
    private val audioFocusManager: AudioFocusMonitorInterface,
    private val phoneCallManager: PhoneCallMonitorInterface,
    private val networkMonitor: NetworkMonitorInterface,
    private val flutterEventMapper: FlutterEventMapper,
    private val stateMachine: StreamStateMachine,
    private val reconnectionService: ReconnectionService
) : LifecycleEventListener, InterruptionDelegate, StreamingMediator {

    companion object {
        private const val TAG = "AudioStreaming"
    }

    // Context and lifecycle management
    private val applicationContext: Context = context.applicationContext
    private var activity: Activity? = (context as? Activity)
    private var isActivityValid: Boolean = true

    private val application: Application?
        get() = applicationContext as? Application

    // Handlers
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // State machine helper properties
    private val currentState: StreamState
        get() = stateMachine.getCurrentState()

    init {
        // Wire up Mediator/Delegates
        interruptionManager.delegate = this
        systemLifecycleObserver.listener = this
        rtmpConnectionHandler.mediator = this
        rtmpConnectionHandler.stopStream = { stopStreaming(null) }
        
        // Wire up Services needing mediator
        (audioFocusManager as? AudioFocusManager)?.mediator = this
        (phoneCallManager as? PhoneCallManager)?.mediator = this
        (networkMonitor as? NetworkMonitor)?.mediator = this
        
        reconnectionService.mediator = this
        reconnectionService.prepareStream = { prepareInternal() }
        reconnectionService.stopStream = { stopStreaming(null) }
    }

    override fun transitionTo(event: StreamEvent): Boolean {
        return stateMachine.transition(event)
    }

    // --- StreamingMediator Implementation ---

    override fun onPhoneInterruptionBegan() {
        Log.i(TAG, "Mediator: Phone Interruption Began")
        interruptionManager.handlePhoneInterruptionBegan()
    }

    override fun onPhoneInterruptionEnded() {
        Log.i(TAG, "Mediator: Phone Interruption Ended")
        interruptionManager.handlePhoneInterruptionEnded()
    }

    override fun onNetworkLost() {
        Log.i(TAG, "Mediator: Network Lost")
        interruptionManager.handleNetworkLost()
    }

    override fun onNetworkAvailable() {
        Log.i(TAG, "Mediator: Network Available")
        interruptionManager.handleNetworkAvailable()
    }

    override fun onAudioFocusLostPermanently() {
        Log.w(TAG, "Mediator: Audio Focus Lost Permanently - Treating as Interruption")
        // Treat permanent loss as interruption (e.g. Camera recording)
        // This allows us to resume if the user comes back to the app.
        if (currentState == StreamState.STREAMING || currentState == StreamState.RECONNECTING) {
            interruptionManager.handlePhoneInterruptionBegan()
        }
    }

    override fun onAudioFocusLostTransient() {
        Log.i(TAG, "Mediator: Audio Focus Lost Transiently")
        interruptionManager.handlePhoneInterruptionBegan()
    }

    // Safe UI thread execution helper
    private fun runOnMainThreadSafely(block: () -> Unit) {
        val currentActivity = activity
        if (!isActivityValid || currentActivity == null || currentActivity.isFinishing) {
            Log.d(TAG, "Activity invalid or finishing, deferring UI operation")
            return
        }
        mainHandler.post {
            if (isActivityValid) {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing on main thread: ${e.message}")
                }
            }
        }
    }

    // --- Audio Configuration ---

    fun prepare(
        bitrate: Int?, sampleRate: Int?, isStereo: Boolean?, echoCanceler: Boolean?,
        noiseSuppressor: Boolean?
    ): Boolean {
        streamingContext.bitrate = bitrate
        streamingContext.sampleRate = sampleRate
        streamingContext.isStereo = isStereo
        streamingContext.echoCanceler = echoCanceler
        streamingContext.noiseSuppressor = noiseSuppressor
        return prepareInternal()
    }

    private fun prepareInternal(): Boolean {
        return rtmpAudio.prepareAudio(
            streamingContext.bitrate ?: (128 * 1024),
            streamingContext.sampleRate ?: 44100,
            streamingContext.isStereo ?: true,
            streamingContext.echoCanceler ?: false,
            streamingContext.noiseSuppressor ?: true
        )
    }
    
    fun getStatistics(result: MethodChannel.Result) {
        val ret = hashMapOf<String, Any>()
        // Pedro's library doesn't expose detailed stats easily via the interface,
        // but we can at least return the configured values which represent the active stream parameters.
        if (currentState == StreamState.STREAMING) {
            ret["bitrate"] = streamingContext.bitrate ?: 0
            ret["sampleRate"] = streamingContext.sampleRate ?: 0
            ret["isStereo"] = streamingContext.isStereo ?: false
        }
        result.success(ret)
    }

    // --- Streaming Control ---

    fun startStreaming(url: String?, result: MethodChannel.Result?) {
        Log.d(TAG, "startStreaming: $url")
        if (url == null) {
            result?.error("StartAudioStreaming", "Must specify a url.", null)
            return
        }

        // Check for active call
        if (phoneCallManager.isCallActive) {
             result?.error("PHONE_CALL_ACTIVE", "Cannot start streaming during an active call", null)
             return
        }

        // Request Audio Focus
        if (!audioFocusManager.requestFocus()) {
             result?.error("AUDIO_FOCUS_DENIED", "Cannot acquire audio focus", null)
             return
        }

        try {
            if (!rtmpAudio.isStreaming) {
                if (prepareInternal()) {
                    transitionTo(StreamEvent.StartRequested)
                    rtmpAudio.startStream(url)
                    
                    // Reset Interruption Flags for clean start
                    interruptionManager.reset()
                    streamingContext.pendingReconnectOnResume = false
                    
                    streamingContext.activeUrl = url // Persist URL for reconnection
                    
                    // Start Services & Listeners
                    activity?.let { AudioStreamingForegroundService.start(it) }
                    phoneCallManager.startMonitoring()
                    networkMonitor.startMonitoring()
                    application?.registerActivityLifecycleCallbacks(systemLifecycleObserver)
                    streamingContext.isInForeground = true

                    val ret = hashMapOf<String, Any>()
                    ret["url"] = url
                    result?.success(ret)
                } else {
                    audioFocusManager.abandonFocus()
                    transitionTo(StreamEvent.StartFailed)
                    result?.error("AudioStreamingFailed", "Error preparing stream", null)
                }
            } else {
                 Log.w(TAG, "Already streaming, ignoring start request")
                 result?.success(null)
            }
        } catch (e: Exception) {
            audioFocusManager.abandonFocus()
            result?.error("AudioStreamingFailed", e.message, null)
        }
    }

    fun stopStreaming(result: MethodChannel.Result?) {
        Log.d(TAG, "stopStreaming requested - current state: $currentState")

        try {
            // Guard against double-stop
            if (currentState == StreamState.IDLE) {
                Log.d(TAG, "Already stopped, ignoring")
                result?.success(null)
                return
            }

            // Cancel any pending tasks
            interruptionManager.reset()
            streamingContext.pendingReconnectOnResume = false
            
            // Clean up RTMP
            try {
                if (rtmpAudio.isStreaming) {
                    rtmpAudio.stopStream()
                }
            } catch (e:  Throwable) {
                Log.e(TAG, "Error stopping stream: ${e.message}")
            }

            // Clean up Managers & Services
            audioFocusManager.abandonFocus()
            phoneCallManager.stopMonitoring()
            networkMonitor.stopMonitoring()

            activity?.let { AudioStreamingForegroundService.stop(it) }
            application?.unregisterActivityLifecycleCallbacks(systemLifecycleObserver)

            // Reset State
            transitionTo(StreamEvent.ExplicitStop)
            streamingContext.activeUrl = null // Crucial: clear URL only on explicit stop
            streamingContext.currentInterruptionSource = InterruptionSource.NONE
            
            result?.success(null)
            Log.d(TAG, "Stream stopped and state reset")
        } catch (e: Throwable) {
             Log.e(TAG, "Fatal error in stopStreaming: ${e.message}")
             // Ensure state is reset even if crash occurs
             transitionTo(StreamEvent.ExplicitStop)
             streamingContext.activeUrl = null
             result?.error("STOP_FAILED", e.message, null)
        }
    }

    fun muteStreaming(result: MethodChannel.Result) {
        try {
            rtmpAudio.disableAudio()
            result.success(null)
        } catch (e: IllegalStateException) {
            result.error("MuteAudioStreamingFailed", e.message, null)
        }
    }

    fun unMuteStreaming(result: MethodChannel.Result) {
        try {
            rtmpAudio.enableAudio()
            result.success(null)
        } catch (e: IllegalStateException) {
            result.error("UnMuteAudioStreamingFailed", e.message, null)
        }
    }

    // --- Interruption Logic ---

    // --- InterruptionDelegate Implementation ---

    override fun stopStreamForInterruption() {
        try {
            rtmpAudio.stopStream()
            Log.d(TAG, "Stream stopped for interruption")
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping stream: ${e.message}")
        }
    }

    override fun abandonAudioFocus() {
        audioFocusManager.abandonFocus()
    }

    override fun getStreamState(): StreamState = currentState
    
    override fun runOnMainThread(block: () -> Unit) = runOnMainThreadSafely(block)

    override fun reconnectStream() {
        reconnectionService.reconnectStream()
    }



    // --- Activity Lifecycle ---

    override fun onActivityResumed(activity: Activity) {
        if (activity === this.activity) {
            streamingContext.isInForeground = true
            isActivityValid = true  // Activity is valid again
            
            // Fix for false network interruption on wakeup:
            // If we are flagged as Network Interrupted, but network is actually available, clear it.
            if (streamingContext.currentInterruptionSource == InterruptionSource.NETWORK && networkMonitor.isNetworkAvailable) {
                 Log.i(TAG, "Resumed with NETWORK interruption but network is available - clearing")
                 interruptionManager.handleNetworkAvailable()
            }

            interruptionManager.handleResumeFromInterruption(phoneCallManager.isCallActive)
        }
    }

    override fun onActivityPaused(activity: Activity) {
         if (activity === this.activity) {
             streamingContext.isInForeground = false
         }
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity === this.activity) streamingContext.isInForeground = true
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity === this.activity) streamingContext.isInForeground = false
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === this.activity) {
            Log.d(TAG, "Activity destroyed - state: $currentState, source: ${streamingContext.currentInterruptionSource}")

            when (currentState) {
                StreamState.INTERRUPTED, StreamState.RECONNECTING -> {
                    Log.i(TAG, "Activity destroyed during interruption - deferring cleanup")
                    isActivityValid = false
                    // Don't call stopStreaming() - let interruption timer handle it
                }
                StreamState.STREAMING, StreamState.PREPARING -> {
                    Log.i(TAG, "Activity destroyed while active - stopping cleanly")
                    isActivityValid = false
                    stopStreaming(null)
                }
                else -> {
                    isActivityValid = false
                }
            }
        }
    }
}
