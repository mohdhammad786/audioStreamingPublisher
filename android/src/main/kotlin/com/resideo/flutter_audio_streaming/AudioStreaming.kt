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
    private var activity: Activity? = null,
    private var dartMessenger: DartMessenger? = null
) : ConnectCheckerRtsp, Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "AudioStreaming"
        private const val INTERRUPTION_TIMEOUT_MS = 30000L // 30 seconds
    }

    private val application: Application?
        get() = activity?.application

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

    // Interruption Handling
    private val mainHandler = Handler(Looper.getMainLooper())
    private var interruptionRunnable: Runnable? = null

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
                onInterruptionBegan = { handleInterruptionBegan() },
                onInterruptionEnded = { handleInterruptionEnded() }
            )
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
        Log.d(TAG, "stopStreaming requested")
        
        // Cancel any pending tasks
        cancelInterruptionTimeout()
        pendingReconnectOnResume = false
        
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
        
        activity?.let { AudioStreamingForegroundService.stop(it) }
        application?.unregisterActivityLifecycleCallbacks(this)

        // Reset State
        currentState = StreamState.IDLE
        activeUrl = null // Crucial: clear URL only on explicit stop
        
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

    private fun handleInterruptionBegan() {
        Log.i(TAG, "Interruption Began (Call Started)")
        
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

        // 3. Notify Flutter (Optional, UI update)
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.AUDIO_INTERRUPTED, "Stream paused due to call")
        }

        // 4. Start Buffer Timer
        startInterruptionTimeout()
    }

    private fun handleInterruptionEnded() {
        Log.i(TAG, "Interruption Ended (Call Ended)")

        if (currentState != StreamState.INTERRUPTED) {
            // If we weren't interrupted, nothing to do (e.g. call ended but we were already stopped)
            Log.d(TAG, "Ignoring interruption end - state is $currentState")
            return
        }

        // Cancel the timeout immediately
        cancelInterruptionTimeout()

        // Attempt Reconnection
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
        interruptionRunnable = Runnable {
            Log.w(TAG, "Interruption timeout expired ($INTERRUPTION_TIMEOUT_MS ms) - Aborting reconnection")
            handleReconnectionFailure("Stream stopped due to prolonged interruption")
        }
        mainHandler.postDelayed(interruptionRunnable!!, INTERRUPTION_TIMEOUT_MS)
        Log.d(TAG, "Interruption timer started: $INTERRUPTION_TIMEOUT_MS ms")
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
                
                // 1. Ensure clean slate
                if (rtspAudio.isStreaming) {
                    try { rtspAudio.stopStream() } catch (e: Exception) {}
                }
                
                // 2. Force Audio Prepare (Re-initializes buffers/encoders)
                // Note: prepareAudio handles being called multiple times usually, 
                // but checking constraints is good.
                val prepared = prepareInternal()
                if (!prepared) {
                     Log.e(TAG, "Failed to re-prepare audio components")
                     activity?.runOnUiThread { handleReconnectionFailure("Device prepare failed") }
                     return@Thread
                }
                
                // 3. Acquire Focus on Main Thread
                var focusGranted = false
                val latch = java.util.concurrent.CountDownLatch(1)
                
                activity?.runOnUiThread {
                    focusGranted = audioFocusManager?.requestFocus() == true
                    latch.countDown()
                }
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS) // Safety wait

                if (!focusGranted) {
                    activity?.runOnUiThread { handleReconnectionFailure("Could not regain audio focus") }
                    return@Thread
                }

                // 4. Start RTSP Stream
                Log.d(TAG, "Restarting RTSP stream to $url")
                rtspAudio.startStream(url)
                
                // State update happens in onConnectionSuccessRtsp
                
            } catch (e: Exception) {
                Log.e(TAG, "Reconnection exception: ${e.message}")
                activity?.runOnUiThread { handleReconnectionFailure(e.message ?: "Unknown error") }
            }
        }.start()
    }

    private fun handleReconnectionFailure(reason: String) {
        Log.e(TAG, "Reconnection failed: $reason")
        
        // Clean up normally
        stopStreaming(null)
        
        // Send STOPPED event so UI knows we are done
        activity?.runOnUiThread {
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
             activity?.runOnUiThread {
                 dartMessenger?.send(DartMessenger.EventType.AUDIO_RESUMED, "Stream resumed")
             }
        }
        
        currentState = StreamState.STREAMING
    }

    override fun onConnectionFailedRtsp(reason: String) {
        Log.e(TAG, "RTSP Connection Failed: $reason")
        
        if (currentState == StreamState.INTERRUPTED) {
            Log.w(TAG, "Connection failed while interrupted (likely network switch) - ignoring for now")
            return
        }

        // Retry logic could go here, or just fail
        activity?.runOnUiThread {
             if (rtspAudio.reTry(5000, reason)) {
                 dartMessenger?.send(DartMessenger.EventType.RTMP_RETRY, reason)
             } else {
                 dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Failed retry")
                 stopStreaming(null)
             }
        }
    }

    override fun onDisconnectRtsp() {
        Log.d(TAG, "RTSP Disconnected")
        
        if (currentState == StreamState.INTERRUPTED || currentState == StreamState.RECONNECTING) {
             Log.d(TAG, "Ignored explicit disconnect during interruption flow")
             return
        }
        
        // Normal disconnect
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Disconnected")
        }
    }

    override fun onAuthErrorRtsp() {
        Log.e(TAG, "Auth Error")
        activity?.runOnUiThread {
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
            stopStreaming(null)
        }
    }
    
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}