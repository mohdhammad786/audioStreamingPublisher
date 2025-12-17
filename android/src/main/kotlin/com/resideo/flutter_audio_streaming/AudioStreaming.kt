package com.resideo.flutter_audio_streaming

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pedro.rtplibrary.rtsp.RtspOnlyAudio
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import io.flutter.plugin.common.MethodChannel
import java.io.IOException

class AudioStreaming(
    context: Context,
    private var dartMessenger: DartMessenger? = null
) : ConnectCheckerRtsp, Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "AudioStreaming"
        private const val PHONE_INTERRUPTION_TIMEOUT_MS = 30000L // 30 seconds
        private const val NETWORK_INTERRUPTION_TIMEOUT_MS = 25000L // 25 seconds
    }

    // Context and lifecycle management
    private val applicationContext: Context = context.applicationContext
    private var activity: Activity? = (context as? Activity)
    private var isActivityValid: Boolean = true

    private val application: Application?
        get() = applicationContext as? Application

    // RTSP Client
    private val rtspAudio: RtspOnlyAudio = RtspOnlyAudio(this)

    // State Management
    private var currentState: StreamState = StreamState.IDLE
    
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

    // Managers
    private var audioFocusManager: AudioFocusManager? = null
    private var phoneCallManager: PhoneCallManager? = null
    private var networkMonitor: NetworkMonitor? = null

    // Interruption Handling
    private val mainHandler = Handler(Looper.getMainLooper())
    private var interruptionRunnable: Runnable? = null
    private var currentInterruptionSource: InterruptionSource = InterruptionSource.NONE
    private var networkLostDuringPhoneCall: Boolean = false  // Edge case: network lost while on call

    init {
        // Initialize managers if activity is available, or wait until startStreaming
        activity?.let { initializeManagers(it) }
    }

    private fun initializeManagers(context: Context) {
        if (audioFocusManager == null) {
            audioFocusManager = AudioFocusManager(context) {
                // On Audio Focus Lost
                Log.w(TAG, "Audio focus lost permanently - stopping stream")
                stopStreaming(null) // Stop gracefully
            }
        }
        if (phoneCallManager == null) {
            phoneCallManager = PhoneCallManager(
                context,
                onInterruptionBegan = { handlePhoneInterruptionBegan() },
                onInterruptionEnded = { handlePhoneInterruptionEnded() }
            )
        }
        if (networkMonitor == null) {
            networkMonitor = NetworkMonitor(
                context,
                onNetworkLost = { handleNetworkLost() },
                onNetworkAvailable = { handleNetworkAvailable() }
            )
        }
    }

    // Safe UI thread execution helper
    private fun runOnMainThreadSafely(block: () -> Unit) {
        if (!isActivityValid) {
            Log.d(TAG, "Activity invalid, deferring UI operation")
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

        // Ensure managers are ready
        activity?.let { initializeManagers(it) }

        // Check for active call
        if (phoneCallManager?.isCallActive() == true) {
             result?.error("PHONE_CALL_ACTIVE", "Cannot start streaming during an active call", null)
             return
        }

        // Request Audio Focus
        if (audioFocusManager?.requestFocus() != true) {
             result?.error("AUDIO_FOCUS_DENIED", "Cannot acquire audio focus", null)
             return
        }

        try {
            if (!rtspAudio.isStreaming) {
                if (prepareInternal()) {
                    rtspAudio.startStream(url)
                    
                    // Update State
                    currentState = StreamState.STREAMING
                    activeUrl = url // Persist URL for reconnection
                    
                    // Start Services & Listeners
                    activity?.let { AudioStreamingForegroundService.start(it) }
                    phoneCallManager?.startMonitoring()
                    networkMonitor?.startMonitoring()
                    application?.registerActivityLifecycleCallbacks(this)
                    isInForeground = true

                    val ret = hashMapOf<String, Any>()
                    ret["url"] = url
                    result?.success(ret)
                } else {
                    audioFocusManager?.abandonFocus()
                    result?.error("AudioStreamingFailed", "Error preparing stream", null)
                }
            } else {
                 Log.w(TAG, "Already streaming, ignoring start request")
                 result?.success(null)
            }
        } catch (e: Exception) {
            audioFocusManager?.abandonFocus()
            result?.error("AudioStreamingFailed", e.message, null)
        }
    }

    fun stopStreaming(result: MethodChannel.Result?) {
        Log.d(TAG, "stopStreaming requested - current state: $currentState")

        // Guard against double-stop
        if (currentState == StreamState.IDLE) {
            Log.d(TAG, "Already stopped, ignoring")
            result?.success(null)
            return
        }

        // Cancel any pending tasks
        cancelInterruptionTimeout()
        pendingReconnectOnResume = false
        networkLostDuringPhoneCall = false  // Reset edge case flag
        
        // Clean up RTSP
        try {
            if (rtspAudio.isStreaming) {
                rtspAudio.stopStream()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream: ${e.message}")
        }

        // Clean up Managers & Services
        audioFocusManager?.abandonFocus()
        phoneCallManager?.stopMonitoring()
        networkMonitor?.stopMonitoring()

        activity?.let { AudioStreamingForegroundService.stop(it) }
        application?.unregisterActivityLifecycleCallbacks(this)

        // Reset State
        currentState = StreamState.IDLE
        activeUrl = null // Crucial: clear URL only on explicit stop
        currentInterruptionSource = InterruptionSource.NONE
        
        result?.success(null)
        Log.d(TAG, "Stream stopped and state reset")
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
        Log.i(TAG, "Phone Call Interruption Began")

        // If already interrupted by network, switch to phone (phone takes precedence)
        if (currentState == StreamState.INTERRUPTED && currentInterruptionSource == InterruptionSource.NETWORK) {
            Log.i(TAG, "Switching from network to phone interruption (phone takes precedence)")
            cancelInterruptionTimeout()
            currentInterruptionSource = InterruptionSource.PHONE_CALL
            startInterruptionTimeout()

            // Notify Flutter of source change
            runOnMainThreadSafely {
                dartMessenger?.send(DartMessenger.EventType.AUDIO_INTERRUPTED, "Phone call started during network interruption")
            }
            return
        }

        currentInterruptionSource = InterruptionSource.PHONE_CALL
        handleInterruptionBegan()
    }

    private fun handlePhoneInterruptionEnded() {
        Log.i(TAG, "Phone Call Interruption Ended")

        // Only respond if interrupted by phone
        if (currentInterruptionSource != InterruptionSource.PHONE_CALL) {
            Log.d(TAG, "Ignoring phone end - not interrupted by phone (source=$currentInterruptionSource)")
            return
        }

        // Check if network was lost during phone call
        if (networkLostDuringPhoneCall) {
            Log.w(TAG, "Phone ended but network still down - switching to network interruption")
            networkLostDuringPhoneCall = false
            currentInterruptionSource = InterruptionSource.NETWORK
            startInterruptionTimeout()  // Restart with network timeout
            return
        }

        handleInterruptionEnded()
    }

    // Network Interruption Handlers
    private fun handleNetworkLost() {
        Log.i(TAG, "Network Lost Detected")

        // Ignore if already interrupted by phone (phone takes precedence)
        if (currentInterruptionSource == InterruptionSource.PHONE_CALL) {
            Log.d(TAG, "Already interrupted by phone call, flagging network loss for later")
            networkLostDuringPhoneCall = true
            return
        }

        // Only care if streaming
        if (currentState != StreamState.STREAMING && currentState != StreamState.PREPARING) {
            Log.d(TAG, "Network loss ignored - not active (state=$currentState)")
            return
        }

        currentInterruptionSource = InterruptionSource.NETWORK
        handleInterruptionBegan()
    }

    private fun handleNetworkAvailable() {
        Log.i(TAG, "Network Available")

        // Only respond if we're actually interrupted
        if (currentState != StreamState.INTERRUPTED) {
            Log.d(TAG, "Ignoring network available - not interrupted (state=$currentState)")
            return
        }

        // Only handle if interrupted by network specifically
        if (currentInterruptionSource != InterruptionSource.NETWORK) {
            Log.d(TAG, "Ignoring network available - interrupted by $currentInterruptionSource, not network")
            return
        }

        handleInterruptionEnded()
    }

    // Common Interruption Handlers
    private fun handleInterruptionBegan() {
        Log.i(TAG, "Interruption Began (Source: $currentInterruptionSource)")

        if (currentState == StreamState.INTERRUPTED) {
            Log.d(TAG, "Already interrupted, ignoring")
            return
        }

        // We only care if we were actually streaming or preparing
        if (currentState != StreamState.STREAMING && currentState != StreamState.PREPARING) {
             Log.d(TAG, "Interruption ignored - not active (state=$currentState)")
             return
        }

        currentState = StreamState.INTERRUPTED

        // 1. Force Stop Stream (Releases Mic)
        try {
            rtspAudio.stopStream()
            Log.d(TAG, "Stream stopped for interruption")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping for interruption: ${e.message}")
        }

        // 2. Abandon Focus Temporarily
        audioFocusManager?.abandonFocus()

        // 3. Notify Flutter with appropriate event
        runOnMainThreadSafely {
            val eventType = when(currentInterruptionSource) {
                InterruptionSource.PHONE_CALL -> DartMessenger.EventType.AUDIO_INTERRUPTED
                InterruptionSource.NETWORK -> DartMessenger.EventType.NETWORK_INTERRUPTED
                else -> return@runOnMainThreadSafely
            }
            val message = when(currentInterruptionSource) {
                InterruptionSource.PHONE_CALL -> "Stream paused due to call"
                InterruptionSource.NETWORK -> "Stream paused due to network loss"
                else -> ""
            }
            dartMessenger?.send(eventType, message)
        }

        // 4. Start Buffer Timer
        startInterruptionTimeout()
    }

    private fun handleInterruptionEnded() {
        Log.i(TAG, "Interruption Ended (Source: $currentInterruptionSource)")

        if (currentState != StreamState.INTERRUPTED) {
            Log.d(TAG, "Ignoring interruption end - state is $currentState")
            return
        }

        // Cancel the timeout immediately
        cancelInterruptionTimeout()

        // Attempt Reconnection - always reconnect regardless of foreground state for network
        if (isInForeground && activity != null) {
            Log.d(TAG, "App in foreground, reconnecting immediately")
            reconnectStream()
        } else {
            Log.d(TAG, "App in background, deferring reconnection")
            pendingReconnectOnResume = true
            // State remains INTERRUPTED until resumed
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

        currentState = StreamState.RECONNECTING

        // Perform "Fresh" Connection on background thread
        Thread {
            try {
                Log.d(TAG, "Starting reconnection sequence...")

                // Check activity validity first
                if (!isActivityValid) {
                    Log.w(TAG, "Activity no longer valid - aborting reconnection")
                    return@Thread
                }

                // 1. Ensure clean slate
                if (rtspAudio.isStreaming) {
                    try { rtspAudio.stopStream() } catch (e: Exception) {}
                }

                // 2. Force Audio Prepare (Re-initializes buffers/encoders)
                val prepared = prepareInternal()
                if (!prepared) {
                     Log.e(TAG, "Failed to re-prepare audio components")
                     runOnMainThreadSafely { handleReconnectionFailure("Device prepare failed") }
                     return@Thread
                }

                // Check validity after prepare
                if (!isActivityValid) {
                    Log.w(TAG, "Activity became invalid during prepare - aborting")
                    return@Thread
                }

                // 3. Acquire Focus on Main Thread
                var focusGranted = false
                val latch = java.util.concurrent.CountDownLatch(1)

                runOnMainThreadSafely {
                    if (isActivityValid) {
                        focusGranted = audioFocusManager?.requestFocus() == true
                    }
                    latch.countDown()
                }

                if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.e(TAG, "Timeout waiting for focus request")
                    return@Thread
                }

                if (!focusGranted) {
                    runOnMainThreadSafely { handleReconnectionFailure("Could not regain audio focus") }
                    return@Thread
                }

                // Final validity check before starting stream
                if (!isActivityValid) {
                    Log.w(TAG, "Activity became invalid before starting stream")
                    return@Thread
                }

                // 4. Start RTSP Stream
                Log.d(TAG, "Restarting RTSP stream to $url")
                rtspAudio.startStream(url)

                // State update happens in onConnectionSuccessRtsp

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
        Log.d(TAG, "RTSP Connection Successful")

        // Critical Fix: Do NOT clear activeUrl here!
        // We keep it valid as long as we intend to be streaming.

        if (currentState == StreamState.RECONNECTING || currentState == StreamState.INTERRUPTED) {
             Log.i(TAG, "Reconnection success - Sending RESUMED event")
             runOnMainThreadSafely {
                 val eventType = when(currentInterruptionSource) {
                     InterruptionSource.PHONE_CALL -> DartMessenger.EventType.AUDIO_RESUMED
                     InterruptionSource.NETWORK -> DartMessenger.EventType.NETWORK_RESUMED
                     else -> DartMessenger.EventType.AUDIO_RESUMED
                 }
                 dartMessenger?.send(eventType, "Stream resumed")
             }
             currentInterruptionSource = InterruptionSource.NONE
        }

        currentState = StreamState.STREAMING
    }

    override fun onConnectionFailedRtsp(reason: String) {
        Log.e(TAG, "RTSP Connection Failed: $reason")

        if (currentState == StreamState.INTERRUPTED) {
            Log.w(TAG, "Connection failed while interrupted - already handling")
            return
        }

        // Check if this looks like a network issue (vs auth/server error)
        if (isNetworkRelatedError(reason) && currentState == StreamState.STREAMING) {
            Log.i(TAG, "RTSP failure appears network-related, triggering network interruption")
            currentInterruptionSource = InterruptionSource.NETWORK
            handleInterruptionBegan()
            return
        }

        // Non-network errors: use existing retry logic
        runOnMainThreadSafely {
             if (rtspAudio.reTry(5000, reason)) {
                 dartMessenger?.send(DartMessenger.EventType.RTMP_RETRY, reason)
             } else {
                 dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Failed retry")
                 stopStreaming(null)
             }
        }
    }

    private fun isNetworkRelatedError(reason: String): Boolean {
        val networkKeywords = listOf(
            "network", "timeout", "unreachable", "connection refused",
            "no route", "socket", "broken pipe", "failed to connect"
        )
        val lowerReason = reason.lowercase()
        return networkKeywords.any { lowerReason.contains(it) }
    }

    override fun onDisconnectRtsp() {
        Log.d(TAG, "RTSP Disconnected")
        
        if (currentState == StreamState.INTERRUPTED || currentState == StreamState.RECONNECTING) {
             Log.d(TAG, "Ignored explicit disconnect during interruption flow")
             return
        }
        
        // Normal disconnect
        runOnMainThreadSafely {
            dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Disconnected")
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