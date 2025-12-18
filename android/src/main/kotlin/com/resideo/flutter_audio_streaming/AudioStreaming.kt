package com.resideo.flutter_audio_streaming

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import io.flutter.plugin.common.MethodChannel
import java.io.IOException

class AudioStreaming(
    context: Context,
    private var dartMessenger: DartMessenger? = null,
    private val client: StreamingClient? = null,
    private val phoneMonitor: PhoneCallMonitorInterface? = null,
    private val networkMonitorInterface: NetworkMonitorInterface? = null,
    private val audioFocusMonitor: AudioFocusMonitorInterface? = null
) : ConnectCheckerRtsp, Application.ActivityLifecycleCallbacks, StreamingMediator {

    companion object {
        private const val TAG = "AudioStreaming"
        private const val PHONE_INTERRUPTION_TIMEOUT_MS = 30000L
        private const val NETWORK_INTERRUPTION_TIMEOUT_MS = 30000L
    }

    // Context and lifecycle management
    private val applicationContext: Context = context.applicationContext
    private var activity: Activity? = (context as? Activity)
    private var isActivityValid: Boolean = true

    private val application: Application?
        get() = applicationContext as? Application

    // RTSP Client (DIP: Use interface if provided, otherwise default)
    private val rtspAudio: StreamingClient = client ?: RtspClientImpl(this)

    // Managers (DIP: Use interfaces if provided, otherwise default)
    private val audioFocusManager: AudioFocusMonitorInterface = audioFocusMonitor ?: AudioFocusManager(context, this)
    private val phoneCallManager: PhoneCallMonitorInterface = phoneMonitor ?: PhoneCallManager(context, this)
    private val networkMonitor: NetworkMonitorInterface = networkMonitorInterface ?: NetworkMonitor(context, this)

    // State Management (Centralized in StreamStateMachine)
    private val stateMachine = StreamStateMachine { oldState, newState, event ->
        handleStateTransition(oldState, newState, event)
    }
    
    // Independent Interruption Tracking (Volatile for background thread visibility)
    @Volatile private var isNetworkLost = false
    @Volatile private var isPhoneCallActive = false
    private var interruptionTimerStartedAt: Long = 0L

    // Configuration
    private var bitrate: Int? = null
    private var sampleRate: Int? = null
    private var isStereo: Boolean? = false
    private var echoCanceler: Boolean? = false
    private var noiseSuppressor: Boolean? = false

    // Persistence
    private var activeUrl: String? = null
    private var pendingReconnectOnResume: Boolean = false
    private var isInForeground: Boolean = false

    // Interruption Handling
    private val mainHandler = Handler(Looper.getMainLooper())
    private var interruptionRunnable: Runnable? = null
    private var currentInterruptionSource: InterruptionSource = InterruptionSource.NONE
    
    // State machine helper properties
    private val currentState: StreamState
        get() = stateMachine.getCurrentState()

    private fun transitionTo(event: StreamEvent): Boolean {
        return stateMachine.transition(event)
    }

    /**
     * CENTRALIZED EVENT MAPPING: This is the ONLY place where Flutter events are triggered by state changes.
     */
    private fun handleStateTransition(oldState: StreamState, newState: StreamState, event: StreamEvent) {
        runOnMainThreadSafely {
            when (newState) {
                StreamState.STREAMING -> {
                    if (event == StreamEvent.ReconnectionSuccess) {
                        val eventType = when (currentInterruptionSource) {
                            InterruptionSource.NETWORK -> DartMessenger.EventType.NETWORK_RESUMED
                            else -> DartMessenger.EventType.AUDIO_RESUMED
                        }
                        dartMessenger?.send(eventType, "Stream resumed successfully")
                        currentInterruptionSource = InterruptionSource.NONE
                    } else if (event == StreamEvent.StartSuccess) {
                        dartMessenger?.send(DartMessenger.EventType.RTMP_STARTED, "Connection success")
                    }
                }
                StreamState.INTERRUPTED -> {
                    val eventType = when (currentInterruptionSource) {
                        InterruptionSource.PHONE_CALL -> DartMessenger.EventType.AUDIO_INTERRUPTED
                        InterruptionSource.NETWORK -> DartMessenger.EventType.NETWORK_INTERRUPTED
                        else -> return@runOnMainThreadSafely
                    }
                    dartMessenger?.send(eventType, "Stream paused due to $currentInterruptionSource")
                }
                StreamState.FAILED -> {
                    // Failures usually handled by explicit result.error or RTMP_STOPPED
                }
                StreamState.IDLE -> {
                    // If we transitioned to IDLE not by user request, it might be a disconnect
                }
                else -> {}
            }
        }
    }

    // --- StreamingMediator Implementation ---

    override fun onPhoneInterruptionBegan() {
        Log.i(TAG, "Mediator: Phone Interruption Began")
        handlePhoneInterruptionBegan()
    }

    override fun onPhoneInterruptionEnded() {
        Log.i(TAG, "Mediator: Phone Interruption Ended")
        handlePhoneInterruptionEnded()
    }

    override fun onNetworkLost() {
        Log.i(TAG, "Mediator: Network Lost")
        handleNetworkLost()
    }

    override fun onNetworkAvailable() {
        Log.i(TAG, "Mediator: Network Available")
        handleNetworkAvailable()
    }

    override fun onAudioFocusLostPermanently() {
        Log.w(TAG, "Mediator: Audio Focus Lost Permanently")
        if (currentState != StreamState.INTERRUPTED && currentState != StreamState.RECONNECTING) {
            stopStreaming(null)
        }
    }

    override fun onAudioFocusLostTransient() {
        Log.i(TAG, "Mediator: Audio Focus Lost Transiently")
        handlePhoneInterruptionBegan()
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
        this.bitrate = bitrate
        this.sampleRate = sampleRate
        this.isStereo = isStereo
        this.echoCanceler = echoCanceler
        this.noiseSuppressor = noiseSuppressor
        return prepareInternal()
    }

    private fun prepareInternal(): Boolean {
        return rtspAudio.prepareAudio(
            this.bitrate ?: (128 * 1024),
            this.sampleRate ?: 44100,
            this.isStereo ?: true,
            this.echoCanceler ?: false,
            this.noiseSuppressor ?: true
        )
    }
    
    fun getStatistics(result: MethodChannel.Result) {
        val ret = hashMapOf<String, Any>()
        // TODO: Implement actual statistics from RtspOnlyAudio if available
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
            if (!rtspAudio.isStreaming) {
                if (prepareInternal()) {
                    rtspAudio.startStream(url)
                    
                    // Update State first
                    transitionTo(StreamEvent.StartRequested) 
                    
                    // Reset Interruption Flags for clean start
                    isNetworkLost = false
                    isPhoneCallActive = false
                    currentInterruptionSource = InterruptionSource.NONE
                    pendingReconnectOnResume = false
                    
                    activeUrl = url // Persist URL for reconnection
                    
                    // Start Services & Listeners
                    activity?.let { AudioStreamingForegroundService.start(it) }
                    phoneCallManager.startMonitoring()
                    networkMonitor.startMonitoring()
                    application?.registerActivityLifecycleCallbacks(this)
                    isInForeground = true

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
            cancelInterruptionTimeout()
            pendingReconnectOnResume = false
            isNetworkLost = false
            isPhoneCallActive = false
            
            // Clean up RTSP
            try {
                if (rtspAudio.isStreaming) {
                    rtspAudio.stopStream()
                }
            } catch (e:  Throwable) {
                Log.e(TAG, "Error stopping stream: ${e.message}")
            }

            // Clean up Managers & Services
            audioFocusManager.abandonFocus()
            phoneCallManager.stopMonitoring()
            networkMonitor.stopMonitoring()

            activity?.let { AudioStreamingForegroundService.stop(it) }
            application?.unregisterActivityLifecycleCallbacks(this)

            // Reset State
            transitionTo(StreamEvent.ExplicitStop)
            activeUrl = null // Crucial: clear URL only on explicit stop
            currentInterruptionSource = InterruptionSource.NONE
            
            result?.success(null)
            Log.d(TAG, "Stream stopped and state reset")
        } catch (e: Throwable) {
             Log.e(TAG, "Fatal error in stopStreaming: ${e.message}")
             // Ensure state is reset even if crash occurs
             transitionTo(StreamEvent.ExplicitStop)
             activeUrl = null
             result?.error("STOP_FAILED", e.message, null)
        }
    }

    fun muteStreaming(result: MethodChannel.Result) {
        try {
            rtspAudio.disableAudio()
            result.success(null)
        } catch (e: IllegalStateException) {
            result.error("MuteAudioStreamingFailed", e.message, null)
        }
    }

    fun unMuteStreaming(result: MethodChannel.Result) {
        try {
            rtspAudio.enableAudio()
            result.success(null)
        } catch (e: IllegalStateException) {
            result.error("UnMuteAudioStreamingFailed", e.message, null)
        }
    }

    // --- Interruption Logic ---

    // Phone Call Interruption Handlers
    private fun handlePhoneInterruptionBegan() {
        Log.i(TAG, "📱 Phone Call Interruption Detected")
        
        if (isPhoneCallActive) return
        isPhoneCallActive = true
        
        // If we are already interrupted by network, phone takes over UI priority
        if (currentState == StreamState.INTERRUPTED) {
            Log.i(TAG, "Already interrupted (likely network) - updating source to phone")
            currentInterruptionSource = InterruptionSource.PHONE_CALL
            
            // Send event immediately as requested for higher responsiveness
            runOnMainThreadSafely {
                dartMessenger?.send(DartMessenger.EventType.AUDIO_INTERRUPTED, "Phone call active")
            }
            startInterruptionTimeout() // Restart timer for the new source
            return
        }

        currentInterruptionSource = InterruptionSource.PHONE_CALL
        handleInterruptionBeganInternal()
    }

    private fun handlePhoneInterruptionEnded() {
        Log.i(TAG, "📱 Phone Call Interruption Ended")

        if (!isPhoneCallActive) return
        isPhoneCallActive = false

        // Scenario 3: If network is still lost, stay interrupted but switch source
        if (isNetworkLost) {
            Log.w(TAG, "Phone ended but network still lost - staying interrupted as NETWORK")
            currentInterruptionSource = InterruptionSource.NETWORK
            startInterruptionTimeout() // Restart timer for network
            
            runOnMainThreadSafely {
                dartMessenger?.send(DartMessenger.EventType.NETWORK_INTERRUPTED, "Phone call ended but network still lost")
            }
            return
        }

        handleInterruptionEndedInternal()
    }

    // Network Interruption Handlers
    private fun handleNetworkLost() {
        Log.i(TAG, "🌐 Network Lost Detected")

        if (isNetworkLost) return
        isNetworkLost = true

        // If phone call is active, network loss is recorded but phone keeps UI priority
        if (isPhoneCallActive) {
            Log.d(TAG, "Network lost during phone call - recorded but phone has priority")
            return
        }

        // Otherwise, trigger interruption as NETWORK
        currentInterruptionSource = InterruptionSource.NETWORK
        handleInterruptionBeganInternal()
    }

    private fun handleNetworkAvailable() {
        Log.i(TAG, "🌐 Network Available")

        if (!isNetworkLost) return
        isNetworkLost = false

        // If phone call is still active, we are still interrupted
        if (isPhoneCallActive) {
            Log.d(TAG, "Network restored but phone call still active - staying interrupted")
            return
        }

        // Only proceed if we were actually interrupted by network
        if (currentInterruptionSource == InterruptionSource.NETWORK) {
            handleInterruptionEndedInternal()
        }
    }

    // Common Interruption Handlers (Internal)
    private fun handleInterruptionBeganInternal() {
        Log.i(TAG, "handleInterruptionBeganInternal - Source: $currentInterruptionSource, State: $currentState")

        // If already interrupted, don't restart logic, just update timers if needed
        if (currentState == StreamState.INTERRUPTED) {
            Log.d(TAG, "Already in INTERRUPTED state")
            return
        }

        val shifted = transitionTo(StreamEvent.InterruptionBegan)
        if (!shifted) return

        // 1. Release Mic/Resources Immediately
        try {
            rtspAudio.stopStream()
            Log.d(TAG, "Stream stopped for interruption")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream: ${e.message}")
        }

        // 2. Abandon audio focus
        audioFocusManager.abandonFocus()

        // 3. Start Interruption Timeout (Scenario 2: 30s timer)
        startInterruptionTimeout()
    }

    private fun handleInterruptionEndedInternal() {
        Log.i(TAG, "handleInterruptionEndedInternal - Source: $currentInterruptionSource, State: $currentState")

        if (currentState != StreamState.INTERRUPTED) {
            Log.d(TAG, "Ignoring end - not in INTERRUPTED state")
            return
        }

        // Cancel timeout
        cancelInterruptionTimeout()

        // Attempt Reconnection
        if (isInForeground && activity != null) {
            Log.i(TAG, "End of all interruptions - triggering reconnection")
            reconnectStream()
        } else {
            Log.i(TAG, "End of interruptions but app in background - pending reconnect")
            pendingReconnectOnResume = true
        }
    }

    private fun startInterruptionTimeout() {
        cancelInterruptionTimeout()

        val timeout = when(currentInterruptionSource) {
            InterruptionSource.PHONE_CALL -> PHONE_INTERRUPTION_TIMEOUT_MS
            InterruptionSource.NETWORK -> NETWORK_INTERRUPTION_TIMEOUT_MS
            else -> return
        }

        interruptionRunnable = Runnable {
            Log.w(TAG, "Interruption timeout expired ($timeout ms, source=$currentInterruptionSource) - Aborting reconnection")
            handleReconnectionFailure("Stream stopped due to prolonged interruption")
        }
        mainHandler.postDelayed(interruptionRunnable!!, timeout)
        Log.d(TAG, "Interruption timer started: $timeout ms (source=$currentInterruptionSource)")
    }

    private fun cancelInterruptionTimeout() {
        interruptionRunnable?.let {
            mainHandler.removeCallbacks(it)
            interruptionRunnable = null
            Log.d(TAG, "Interruption timer cancelled")
        }
    }

    private fun reconnectStream() {
        val url = activeUrl
        if (url == null) {
            Log.e(TAG, "Cannot reconnect - activeUrl is null")
            handleReconnectionFailure("Configuration lost")
            return
        }

        if (currentState != StreamState.INTERRUPTED) {
            Log.w(TAG, "reconnectStream called but state is not INTERRUPTED (State: $currentState)")
            return
        }
        transitionTo(StreamEvent.ReconnectionStarted)

        // Perform "Fresh" Connection on background thread
        Thread {
            try {
                Log.d(TAG, "Starting reconnection sequence...")

                // Continuous Check: Did an interruption happen while starting thread?
                if (isPhoneCallActive || isNetworkLost || currentState != StreamState.RECONNECTING) {
                    Log.w(TAG, "Reconnection aborted - interrupted")
                    if (currentState == StreamState.RECONNECTING) transitionTo(StreamEvent.InterruptionBegan)
                    return@Thread
                }

                // 1. Ensure clean slate
                try { rtspAudio.stopStream() } catch (e: Exception) {}

                // 2. Force Audio Prepare (Re-initializes buffers/encoders)
                val prepared = prepareInternal()
                if (!prepared) {
                     Log.e(TAG, "Failed to re-prepare audio components")
                     runOnMainThreadSafely { handleReconnectionFailure("Device prepare failed") }
                     return@Thread
                }
                
                // RACE GUARD
                if (isPhoneCallActive || isNetworkLost || currentState != StreamState.RECONNECTING) {
                    Log.w(TAG, "Reconnection aborted after prepare")
                    if (currentState == StreamState.RECONNECTING) transitionTo(StreamEvent.InterruptionBegan)
                    return@Thread
                }

                // 3. Acquire Focus on Main Thread
                var focusGranted = false
                val latch = java.util.concurrent.CountDownLatch(1)
                runOnMainThreadSafely {
                    if (isActivityValid) {
                        focusGranted = audioFocusManager.requestFocus()
                    }
                    latch.countDown()
                }
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)

                if (!focusGranted) {
                    Log.e(TAG, "Failed to acquire audio focus for reconnection")
                    runOnMainThreadSafely { handleReconnectionFailure("Could not regain audio focus") }
                    return@Thread
                }

                // FINAL RACE GUARD
                if (isPhoneCallActive || isNetworkLost || currentState != StreamState.RECONNECTING) {
                    Log.w(TAG, "Reconnection aborted before actual startStream")
                    audioFocusManager.abandonFocus()
                    if (currentState == StreamState.RECONNECTING) transitionTo(StreamEvent.InterruptionBegan)
                    return@Thread
                }

                // 4. Start RTSP Stream
                Log.i(TAG, "🚀 Restarting RTSP stream to $url")
                rtspAudio.startStream(url)

                // Success/Failure will be handled in callbacks
            } catch (e: Exception) {
                Log.e(TAG, "Reconnection exception: ${e.message}")
                runOnMainThreadSafely { handleReconnectionFailure(e.message ?: "Unknown error") }
            }
        }.start()
    }

    private fun handleReconnectionFailure(reason: String) {
        Log.e(TAG, "Reconnection failed: $reason")

        // Clean up normally
        stopStreaming(null)
        transitionTo(StreamEvent.ReconnectionFailed)

        // Send STOPPED event so UI knows we are done
        runOnMainThreadSafely {
            dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, reason)
        }
    }

    // --- ConnectCheckerRtsp Callbacks ---
    
    override fun onConnectionStartedRtsp(rtspUrl: String) {
        Log.i(TAG, "RTSP Connection Started: $rtspUrl")
    }

    override fun onConnectionSuccessRtsp() {
        Log.i(TAG, "✅ RTSP Connection Successful")

        val wasReconnecting = (currentState == StreamState.RECONNECTING || currentState == StreamState.INTERRUPTED)
        
        if (wasReconnecting) {
            transitionTo(StreamEvent.ReconnectionSuccess)
        } else {
            transitionTo(StreamEvent.StartSuccess)
        }
    }

    override fun onConnectionFailedRtsp(reason: String) {
        Log.e(TAG, "❌ RTSP Connection Failed: $reason")

        // If already interrupted, we are already handled
        if (currentState == StreamState.INTERRUPTED) {
            Log.w(TAG, "Connection failed while interrupted - already handling")
            return
        }

        // Check if this looks like a network issue
        if (isNetworkRelatedError(reason) && (currentState == StreamState.STREAMING || currentState == StreamState.RECONNECTING)) {
            Log.i(TAG, "RTSP failure appears network-related, triggering network interruption flow")
            handleNetworkLost() // This handles flags and state transition
            return
        }

        // Non-network errors or if not streaming: use existing retry logic
        runOnMainThreadSafely {
             if (rtspAudio.reTry(5000, reason)) {
                 dartMessenger?.send(DartMessenger.EventType.RTMP_RETRY, reason)
             } else {
                 handleReconnectionFailure("RTSP connection failed after retries: $reason")
             }
        }
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

    override fun onDisconnectRtsp() {
        Log.d(TAG, "RTSP Disconnected callback")
        
        if (currentState == StreamState.INTERRUPTED || currentState == StreamState.RECONNECTING) {
             Log.d(TAG, "Ignored explicit disconnect callback during interruption/reconnection flow")
             return
        }
        
        // Normal disconnect (e.g., server closed connection)
        runOnMainThreadSafely {
            dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Server disconnected")
            stopStreaming(null)
        }
    }

    override fun onAuthErrorRtsp() {
        Log.e(TAG, "Auth Error")
        runOnMainThreadSafely {
            dartMessenger?.send(DartMessenger.EventType.ERROR, "Auth error")
        }
    }

    override fun onAuthSuccessRtsp() {
        Log.d(TAG, "Auth Success")
    }

    override fun onNewBitrateRtsp(bitrate: Long) {
        // Log.v(TAG, "Bitrate: $bitrate")
    }

    // --- Activity Lifecycle ---

    override fun onActivityResumed(activity: Activity) {
        if (activity === this.activity) {
            isInForeground = true
            isActivityValid = true  // Activity is valid again

            if (pendingReconnectOnResume) {
                Log.d(TAG, "Resumed with pending reconnect")
                pendingReconnectOnResume = false
                reconnectStream()
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {
         if (activity === this.activity) {
             isInForeground = false
         }
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity === this.activity) isInForeground = true
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity === this.activity) isInForeground = false
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === this.activity) {
            Log.d(TAG, "Activity destroyed - state: $currentState, source: $currentInterruptionSource")

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
    
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}