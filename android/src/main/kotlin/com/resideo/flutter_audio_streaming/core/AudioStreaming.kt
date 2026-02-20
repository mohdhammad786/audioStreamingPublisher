package com.resideo.flutter_audio_streaming.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

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
    private val reconnectionService: ReconnectionService,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : LifecycleEventListener, InterruptionDelegate, StreamingMediator {

    companion object {
        private const val TAG = "AudioStreaming"
    }

    // Context and lifecycle management
    private val applicationContext: Context = context.applicationContext
    private var activity: Activity? = (context as? Activity)
    private var isActivityValid: Boolean = true
    private val scope = MainScope()

    fun setActivity(activity: Activity?) {
        this.activity = activity
        this.isActivityValid = activity != null
    }

    private val application: Application?
        get() = applicationContext as? Application

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
        // 🔍 DIAGNOSTIC: This fires if NetworkMonitor triggered the issue (Path A)
        // If you see this → the issue is NetworkMonitor, NOT RTMP disconnect
        Log.e(TAG, "🟡 DIAG-PATH-A: onNetworkLost() from NetworkMonitor")
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
        
        scope.launch {
            prepareInternal()
        }
        return true
    }

    private suspend fun prepareInternal(): Boolean {
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
        Log.i(TAG, "=== START STREAMING CALLED ===")
        Log.i(TAG, "startStreaming: url=$url")
        
        if (url == null) {
            Log.e(TAG, "startStreaming: URL is null")
            result?.error("StartAudioStreaming", "Must specify a url.", null)
            return
        }

        // Check for active call
        Log.d(TAG, "startStreaming: Checking for active call...")
        try {
            if (phoneCallManager.isCallActive) {
                Log.w(TAG, "startStreaming: Phone call active, rejecting")
                result?.error("PHONE_CALL_ACTIVE", "Cannot start streaming during an active call", null)
                return
            }
            Log.d(TAG, "startStreaming: No active call")
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_PHONE_STATE permission missing, assuming no active call")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking call state: ${e.message}")
        }

        // Request Audio Focus
        Log.d(TAG, "startStreaming: Requesting audio focus...")
        if (!audioFocusManager.requestFocus()) {
            Log.e(TAG, "startStreaming: Audio focus denied")
            result?.error("AUDIO_FOCUS_DENIED", "Cannot acquire audio focus", null)
            return
        }
        Log.d(TAG, "startStreaming: Audio focus acquired")

        Log.d(TAG, "startStreaming: Launching coroutine...")
        scope.launch {
            try {
                Log.d(TAG, "startStreaming: Inside coroutine, isStreaming=${rtmpAudio.isStreaming}")
                if (!rtmpAudio.isStreaming) {
                    Log.d(TAG, "startStreaming: Calling prepareInternal()...")
                    val prepared = prepareInternal()
                    Log.d(TAG, "startStreaming: prepareInternal() returned: $prepared")
                    
                    if (prepared) {
                        Log.d(TAG, "startStreaming: Transitioning to StartRequested")
                        transitionTo(StreamEvent.StartRequested)
                        
                        Log.d(TAG, "startStreaming: Calling rtmpAudio.startStream()")
                        rtmpAudio.startStream(url)
                        Log.d(TAG, "startStreaming: rtmpAudio.startStream() called")
                        
                        // Start Foreground Service to keep alive in background
                        Log.d(TAG, "startStreaming: Starting foreground service")
                        AudioStreamingForegroundService.start(applicationContext)
                        
                        // Reset Interruption Flags for clean start
                        interruptionManager.reset()
                        streamingContext.pendingReconnectOnResume = false
                        
                        streamingContext.activeUrl = url // Persist URL for reconnection
                        
                        // Start Services & Listeners
                        Log.d(TAG, "startStreaming: Starting monitors")
                        phoneCallManager.startMonitoring()
                        networkMonitor.startMonitoring()
                        application?.registerActivityLifecycleCallbacks(systemLifecycleObserver)
                        streamingContext.isInForeground = true

                        val ret = hashMapOf<String, Any>()
                        ret["url"] = url
                        Log.i(TAG, "=== START STREAMING SUCCESS - returning result ===")
                        result?.success(ret)
                    } else {
                        Log.e(TAG, "startStreaming: prepareInternal() FAILED")
                        audioFocusManager.abandonFocus()
                        transitionTo(StreamEvent.StartFailed)
                        result?.error("AudioStreamingFailed", "Error preparing stream", null)
                    }
                } else {
                    Log.w(TAG, "Already streaming, ignoring start request")
                    result?.success(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startStreaming: EXCEPTION: ${e.message}", e)
                audioFocusManager.abandonFocus()
                result?.error("AudioStreamingFailed", e.message, null)
            }
        }
    }

    fun stopStreaming(result: MethodChannel.Result?) {
        Log.d(TAG, "stopStreaming requested - current state: $currentState")

        try {
            if (currentState == StreamState.IDLE) {
                result?.success(null)
                return
            }
            
            // CRITICAL FIX: If we're in INTERRUPTED state (e.g., music playing),
            // don't process stop request - let the 30s timer handle the final stop.
            // This prevents the client's redundant stop() call from sending rtmp_stopped prematurely.
            if (currentState == StreamState.INTERRUPTED || currentState == StreamState.RECONNECTING) {
                Log.w(TAG, "⚠️ Ignoring stopStreaming during $currentState - timer will handle final stop")
                result?.success(null)
                return
            }

            interruptionManager.reset()
            streamingContext.pendingReconnectOnResume = false

            try {
                if (rtmpAudio.isStreaming) {
                    rtmpAudio.stopStream()
                }
            } catch (_: Throwable) {
            }

            audioFocusManager.abandonFocus()
            phoneCallManager.stopMonitoring()
            networkMonitor.stopMonitoring()

            AudioStreamingForegroundService.stop(applicationContext)
            application?.unregisterActivityLifecycleCallbacks(systemLifecycleObserver)

            transitionTo(StreamEvent.ExplicitStop)
            streamingContext.activeUrl = null
            streamingContext.currentInterruptionSource = InterruptionSource.NONE

            result?.success(null)
        } catch (e: Throwable) {
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
        // Note: isExpectingSafetyDisconnect flag is now managed by InterruptionManager
        // BEFORE this method is called, to prevent race conditions.
        try {
            rtmpAudio.stopStream()
            Log.d(TAG, "Stream stopped for interruption")
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping stream for interruption: ${e.message}")
        }
    }

    override fun tryAcquireAudioFocus(): Boolean {
        return audioFocusManager.requestFocus()
    }

    override fun abandonAudioFocus() {
        audioFocusManager.abandonFocus()
    }

    override fun getStreamState(): StreamState = currentState
    
    override fun runOnMainThread(block: () -> Unit) {
        // execute on main thread unconditionally (Service/Logic requirement)
        mainHandler.post(block)
    }

    override fun reconnectStream() {
        // Add safeguard delay for Hardware Mic release
        // This prevents "silence" issues where the Mic is still busy from the previous session
        Log.i(TAG, "reconnectStream called - waiting 1000ms for hardware cleanup...")
        mainHandler.postDelayed({
            reconnectionService.reconnectStream()
        }, 1000)
    }



    // --- Activity Lifecycle ---

    override fun onActivityResumed(activity: Activity) {
        if (activity === this.activity) {
            Log.i(TAG, "📱 Activity Resumed - current state: $currentState, source: ${streamingContext.currentInterruptionSource}")
            streamingContext.isInForeground = true
            isActivityValid = true  // Activity is valid again
            
            // Fix for false network interruption on wakeup:
            // If we are flagged as Network Interrupted, but network is actually available, clear it.
            if (streamingContext.currentInterruptionSource == InterruptionSource.NETWORK && networkMonitor.isNetworkAvailable) {
                 Log.i(TAG, "Resumed with NETWORK interruption but network is available - clearing")
                 interruptionManager.handleNetworkAvailable()
            }

            // PROACTIVE AUDIO FOCUS RECOVERY (iOS Parity)
            // When app comes to foreground while interrupted, check if we can resume.
            // This handles cases where AUDIOFOCUS_GAIN was never received from the system
            // (e.g., music app exits without explicitly releasing focus).
            if (currentState == StreamState.INTERRUPTED) {
                val currentSource = streamingContext.currentInterruptionSource
                Log.i(TAG, "📱 Proactive resume check scheduled (interrupted by $currentSource)")
                
                mainHandler.postDelayed({
                    // Re-check state after delay (let previous app release resources)
                    if (currentState != StreamState.INTERRUPTED) {
                        Log.d(TAG, "📱 State changed during proactive delay - aborting")
                        return@postDelayed
                    }
                    
                    Log.i(TAG, "📱 Executing proactive resume check")
                    
                    // Check if there's an actual phone call
                    val hasActiveCall = try { 
                        phoneCallManager.isCallActive 
                    } catch (_: Exception) { 
                        false 
                    }
                    
                    if (hasActiveCall) {
                        Log.i(TAG, "📱 Phone call still active - not resuming")
                        return@postDelayed
                    }
                    
                    // For PHONE_CALL interruption source (which includes audio focus loss):
                    // Try to acquire audio focus - if successful, we can resume
                    if (currentSource == InterruptionSource.PHONE_CALL || 
                        currentSource == InterruptionSource.SYSTEM_RESOURCE) {
                        if (audioFocusManager.requestFocus()) {
                            Log.i(TAG, "📱 Audio focus acquired - triggering resume")
                            interruptionManager.handlePhoneInterruptionEnded()
                        } else {
                            Log.w(TAG, "📱 Failed to acquire audio focus - cannot resume yet")
                        }
                    }
                }, 500)  // 500ms delay to let previous app release resources
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
                    this.activity = null
                    // Don't call stopStreaming() - let interruption timer handle it or service keep it alive
                }
                StreamState.STREAMING, StreamState.PREPARING -> {
                    Log.i(TAG, "Activity destroyed while active - keeping service alive")
                    isActivityValid = false
                    this.activity = null
                    // CRITICAL FIX: Do NOT stop streaming here. 
                    // The Foreground Service will keep the process alive.
                }
                else -> {
                    isActivityValid = false
                    this.activity = null
                }
            }
        }
    }
}
