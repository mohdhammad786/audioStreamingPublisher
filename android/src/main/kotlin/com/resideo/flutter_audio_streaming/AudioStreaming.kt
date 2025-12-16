package com.resideo.flutter_audio_streaming

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import com.pedro.rtplibrary.rtsp.RtspOnlyAudio
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import io.flutter.plugin.common.MethodChannel
import java.io.IOException
import android.app.Application
import android.os.Bundle


class AudioStreaming(
    private var activity: Activity? = null,
    private var dartMessenger: DartMessenger? = null
) : ConnectCheckerRtsp, Application.ActivityLifecycleCallbacks, AudioManager.OnAudioFocusChangeListener {

    private val application: Application?
        get() = activity?.application

    private var isInForeground: Boolean = false
    private var pendingReconnect: Boolean = false


    private val rtspAudio: RtspOnlyAudio = RtspOnlyAudio(this)
    private var prepared: Boolean = false
    private var bitrate: Int? = null
    private var sampleRate: Int? = null
    private var isStereo: Boolean? = false
    private var echoCanceler: Boolean? = false
    private var noiseSuppressor: Boolean? = false
    private var phoneStateListener: PhoneStateListener? = null

    // Interruption handling properties
    private val interruptionHandler = Handler(Looper.getMainLooper())
    private var interruptionRunnable: Runnable? = null
    private var isInterrupted: Boolean = false
    private var savedUrl: String? = null
    private val interruptionTimeout: Long = 30000  // 30 seconds

    fun prepare(
        bitrate: Int?, sampleRate: Int?, isStereo: Boolean?, echoCanceler: Boolean?,
        noiseSuppressor: Boolean?
    ): Boolean {
        this.bitrate = bitrate
        this.sampleRate = sampleRate
        this.isStereo = isStereo
        this.echoCanceler = echoCanceler
        this.noiseSuppressor = noiseSuppressor
        prepared = true
        return rtspAudio.prepareAudio(
            bitrate ?: (128 * 1024),
            sampleRate ?: 44100,
            isStereo ?: true,
            echoCanceler ?: false,
            (noiseSuppressor ?: true)
        )
    }

    private fun prepare(): Boolean {
        prepared = true
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
        result.success(ret)
    }

    fun startStreaming(url: String?, result: MethodChannel.Result) {
        Log.i(TAG, "AudioStreaming: StartAudioStreaming url: $url")
        if (url == null) {
            result.error("StartAudioStreaming", "Must specify a url.", null)
            return
        }

        // Save URL for potential reconnection
        savedUrl = url

        // Check if there's an active phone call
        if (isPhoneCallActive()) {
            result.error(
                "PHONE_CALL_ACTIVE",
                "Cannot start streaming during an active phone call",
                null
            )
            return
        }

        try {
            if (!rtspAudio.isStreaming) {
                if (prepared || prepare()) {
                    // ready to start streaming
                    rtspAudio.startStream(url)

                    // Register phone state listener AFTER stream starts
                    registerPhoneStateListener()

                    // Register lifecycle callbacks
                    application?.registerActivityLifecycleCallbacks(this)
                    isInForeground = true // Assume we are foregrounded when starting

                    // Request Audio Focus
                    requestAudioFocus()

                    val ret = hashMapOf<String, Any>()
                    ret["url"] = url
                    result.success(ret)
                } else {
                    result.error(
                        "AudioStreamingFailed",
                        "Error preparing stream, This device cant do it",
                        null
                    )
                    return
                }
            }
        } catch (e: IOException) {
            result.error("AudioStreamingFailed", e.message, null)
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

    fun stopStreaming(result: MethodChannel.Result) {
        Log.i(TAG, "AudioStreaming: === stopStreaming START ===")
        try {
            // Cancel any pending interruption timeout
            Log.i(TAG, "AudioStreaming: Cancelling interruption timeout")
            cancelInterruptionTimeout()

            Log.i(TAG, "AudioStreaming: Resetting state: isInterrupted = false, savedUrl = null")
            isInterrupted = false
            savedUrl = null

            // Unregister phone state listener
            phoneStateListener?.let {
                Log.i(TAG, "AudioStreaming: Unregistering PhoneStateListener")
                val telephonyManager = activity?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                phoneStateListener = null
                Log.i(TAG, "AudioStreaming: PhoneStateListener unregistered")
            }

            // Unregister lifecycle callbacks
            application?.unregisterActivityLifecycleCallbacks(this)

            // Abandon Audio Focus
            abandonAudioFocus()

            Log.i(TAG, "AudioStreaming: Calling rtspAudio.stopStream()")
            rtspAudio.stopStream()
            Log.i(TAG, "AudioStreaming: rtspAudio.stopStream() completed")

            result.success(null)
            Log.i(TAG, "AudioStreaming: === stopStreaming END SUCCESS ===")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "AudioStreaming ERROR: IllegalStateException in stopStreaming: ${e.message}")
            e.printStackTrace()
            result.error("StopAudioStreamingFailed", e.message, null)
            Log.i(TAG, "AudioStreaming: === stopStreaming END FAILURE ===")
        }
    }

    /*   override fun onConnectionSuccessRtmp() {
           println("AudioStreaming: onConnectionSuccessRtmp")
       }

       override fun onNewBitrateRtmp(bitrate: Long) {
           println("AudioStreaming: onNewBitrateRtmp: $bitrate")
       }

       override fun onDisconnectRtmp() {
           println("AudioStreaming: onDisconnectRtmp")
           activity?.runOnUiThread {
               dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Disconnected")
           }
       }

       override fun onAuthErrorRtmp() {
           println("AudioStreaming: onAuthErrorRtmp")
           activity?.runOnUiThread {
               dartMessenger?.send(DartMessenger.EventType.ERROR, "Auth error")
           }
       }

       override fun onAuthSuccessRtmp() {
           println("AudioStreaming: onAuthSuccessRtmp")
       }

       override fun onConnectionFailedRtmp(reason: String) {
           println("AudioStreaming: onConnectionFailedRtmp")
           activity?.runOnUiThread { //Wait 5s and retry connect stream
               if (rtmpAudio.reTry(5000, reason)) {
                   dartMessenger?.send(DartMessenger.EventType.RTMP_RETRY, reason)
               } else {
                   dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Failed retry")
                   rtmpAudio.stopStream()
               }
           }
       }

       override fun onConnectionStartedRtmp(rtmpUrl: String) {
           println("AudioStreaming: onConnectionStartedRtmp")
       }*/

    private fun isPhoneCallActive(): Boolean {
        val telephonyManager = activity?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val audioManager = activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Check telephony manager call state
        val callState = telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE
        Log.i(TAG, "AudioStreaming: Checking phone call - TelephonyManager state: $callState")
        if (callState != TelephonyManager.CALL_STATE_IDLE) {
            Log.i(TAG, "AudioStreaming: Phone call detected via TelephonyManager: state=$callState")
            return true
        }

        // Check if audio is being used for communication (call mode)
        val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        Log.i(TAG, "AudioStreaming: Checking phone call - AudioManager mode: $audioMode")
        if (audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            Log.i(TAG, "AudioStreaming: Phone call detected via AudioManager: mode=$audioMode")
            return true
        }

        Log.i(TAG, "AudioStreaming: No phone call detected")
        return false
    }

    private fun registerPhoneStateListener() {
        if (phoneStateListener != null) {
            Log.i(TAG, "AudioStreaming: PhoneStateListener already registered")
            return
        }

        val telephonyManager = activity?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager == null) {
            Log.e(TAG, "AudioStreaming ERROR: TelephonyManager not available, cannot monitor phone calls")
            return
        }

        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)

                val stateString = when(state) {
                    TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                    TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                    else -> "UNKNOWN($state)"
                }

                Log.i(TAG, "AudioStreaming: === PhoneStateListener.onCallStateChanged ===")
                Log.i(TAG, "AudioStreaming: New state: $stateString")
                Log.i(TAG, "AudioStreaming: isInterrupted: $isInterrupted")
                Log.i(TAG, "AudioStreaming: isStreaming: ${rtspAudio.isStreaming}")

                when (state) {
                    TelephonyManager.CALL_STATE_RINGING,
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.i(TAG, "AudioStreaming: Phone call active - calling handleInterruptionBegan()")
                        handleInterruptionBegan()
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.i(TAG, "AudioStreaming: Phone call ended - calling handleInterruptionEnded()")
                        handleInterruptionEnded()
                    }
                }
                Log.i(TAG, "AudioStreaming: === PhoneStateListener.onCallStateChanged END ===")
            }
        }

        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.i(TAG, "AudioStreaming: PhoneStateListener registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "AudioStreaming ERROR: Failed to register PhoneStateListener: ${e.message}")
            phoneStateListener = null
        }
    }

    private fun handleInterruptionBegan() {
        Log.i(TAG, "AudioStreaming: === handleInterruptionBegan START ===")
        Log.i(TAG, "AudioStreaming: isInterrupted: $isInterrupted")
        Log.i(TAG, "AudioStreaming: rtspAudio.isStreaming: ${rtspAudio.isStreaming}")

        if (isInterrupted) {
            Log.i(TAG, "AudioStreaming: Already handling interruption - returning")
            return
        }

        if (!rtspAudio.isStreaming) {
            Log.i(TAG, "AudioStreaming: Not streaming, ignoring interruption - returning")
            return
        }

        // Safety check: ensure activity exists
        if (activity == null) {
            Log.e(TAG, "AudioStreaming ERROR: Activity is null in handleInterruptionBegan")
            return
        }

        isInterrupted = true
        Log.i(TAG, "AudioStreaming: Set isInterrupted = true")
        Log.i(TAG, "AudioStreaming: Phone call detected - gracefully stopping stream")

        // 1. Fully stop the stream (releases microphone)
        try {
            Log.i(TAG, "AudioStreaming: Calling rtspAudio.stopStream()...")
            rtspAudio.stopStream()
            Log.i(TAG, "AudioStreaming: rtspAudio.stopStream() completed successfully")
            Log.i(TAG, "AudioStreaming: After stop - isStreaming: ${rtspAudio.isStreaming}")
        } catch (e: Exception) {
            Log.e(TAG, "AudioStreaming ERROR: Error stopping stream: ${e.message}")
            e.printStackTrace()
        }

        // Abandon Audio Focus (temporarily)
        abandonAudioFocus()

        // 2. Send AUDIO_INTERRUPTED event (NOT RTMP_STOPPED)
        Log.i(TAG, "AudioStreaming: Sending AUDIO_INTERRUPTED event to Flutter")
        activity?.runOnUiThread {
            dartMessenger?.send(
                DartMessenger.EventType.AUDIO_INTERRUPTED,
                "Phone call detected - stream paused temporarily"
            )
        }

        // 3. Start timeout (30 seconds)
        Log.i(TAG, "AudioStreaming: Starting interruption timeout timer")
        startInterruptionTimeout()
        Log.i(TAG, "AudioStreaming: === handleInterruptionBegan END ===")
    }

    private fun handleInterruptionEnded() {
        Log.i(TAG, "AudioStreaming: === handleInterruptionEnded START ===")
        Log.i(TAG, "AudioStreaming: isInterrupted: $isInterrupted")

        if (!isInterrupted) {
            Log.i(TAG, "AudioStreaming: Not in interrupted state - returning")
            return
        }

        Log.i(TAG, "AudioStreaming: Phone call ended - attempting reconnection")

        // Cancel timeout
        Log.i(TAG, "AudioStreaming: Cancelling interruption timeout")
        cancelInterruptionTimeout()

        // REMOVED DELAY - reconnect immediately like iOS
        Log.i(TAG, "AudioStreaming: Checking foreground status before reconnection")

        // CHECK FOREGROUND STATE
        if (isInForeground && activity != null) {
             Log.i(TAG, "AudioStreaming: App is in foreground - reconnecting now")
             reconnectStream()
        } else {
             Log.i(TAG, "AudioStreaming: App is in BACKGROUND or activity null - setting pendingReconnect = true")
             pendingReconnect = true
             isInterrupted = false  // Clear flag since we're deferring
        }

        Log.i(TAG, "AudioStreaming: === handleInterruptionEnded END ===")
    }

    private fun startInterruptionTimeout() {
        cancelInterruptionTimeout()  // Cancel any existing timeout

        interruptionRunnable = Runnable {
            handleInterruptionTimeout()
        }

        interruptionHandler.postDelayed(interruptionRunnable!!, interruptionTimeout)
        Log.i(TAG, "AudioStreaming: Interruption timeout started (30 seconds)")
    }

    private fun cancelInterruptionTimeout() {
        interruptionRunnable?.let {
            interruptionHandler.removeCallbacks(it)
            interruptionRunnable = null
        }
    }

    private fun handleInterruptionTimeout() {
        Log.i(TAG, "AudioStreaming: Interruption timeout expired - giving up on reconnection")

        isInterrupted = false
        savedUrl = null

        // Send RTMP_STOPPED event (timeout expired)
        activity?.runOnUiThread {
            dartMessenger?.send(
                DartMessenger.EventType.RTMP_STOPPED,
                "Stream stopped due to prolonged interruption"
            )
        }
    }

    private fun reconnectStream() {
        Log.i(TAG, "AudioStreaming: === reconnectStream START ===")

        // Validate state BEFORE attempting reconnection
        val url = savedUrl
        if (url == null) {
            Log.e(TAG, "AudioStreaming ERROR: No saved URL for reconnection")
            isInterrupted = false
            return
        }

        if (activity == null) {
            Log.e(TAG, "AudioStreaming ERROR: Activity is null, cannot reconnect")
            isInterrupted = false
            savedUrl = null
            return
        }

        if (!isInForeground) {
            Log.i(TAG, "AudioStreaming: App in background, will reconnect when resumed")
            pendingReconnect = true
            return
        }

        Log.i(TAG, "AudioStreaming: Reconnecting to: $url")
        Log.i(TAG, "AudioStreaming: Current state - prepared: $prepared, isStreaming: ${rtspAudio.isStreaming}")

        try {
            // Force re-prepare (NO background thread - run on current thread like iOS)
            Log.i(TAG, "AudioStreaming: Force re-preparing stream...")
            prepared = false
            if (!prepare()) {
                Log.e(TAG, "AudioStreaming ERROR: Failed to prepare stream for reconnection")
                handleReconnectionFailure("Failed to prepare stream")
                return
            }
            Log.i(TAG, "AudioStreaming: Stream prepared successfully")

            // Restart stream (NO background thread - matches iOS behavior)
            Log.i(TAG, "AudioStreaming: Calling rtspAudio.startStream($url)...")
            rtspAudio.startStream(url)
            Log.i(TAG, "AudioStreaming: rtspAudio.startStream() completed")
            Log.i(TAG, "AudioStreaming: After start - isStreaming: ${rtspAudio.isStreaming}")

            // Re-register phone state listener
            Log.i(TAG, "AudioStreaming: Re-registering phone state listener...")
            registerPhoneStateListener()

            // Reset state
            isInterrupted = false
            Log.i(TAG, "AudioStreaming: Reset state - isInterrupted: false")
            pendingReconnect = false

            // Request Audio Focus again
            requestAudioFocus()

            /*
               MATCHING iOS BEHAVIOR:
               Do NOT send AUDIO_RESUMED here.
               Do NOT clear savedUrl here.
               Wait for onConnectionSuccessRtsp to confirm connection, then send event.
            */

            Log.i(TAG, "AudioStreaming: Reconnection initiated - waiting for callback")
            Log.i(TAG, "AudioStreaming: === reconnectStream END SUCCESS ===")
        } catch (e: Exception) {
            Log.e(TAG, "AudioStreaming ERROR: Exception during reconnection: ${e.message}")
            e.printStackTrace()
            handleReconnectionFailure("Reconnection failed: ${e.message}")
            Log.i(TAG, "AudioStreaming: === reconnectStream END FAILURE ===")
        }
    }

    private fun handleReconnectionFailure(error: String) {
        Log.e(TAG, "AudioStreaming ERROR: $error")

        isInterrupted = false
        savedUrl = null

        activity?.runOnUiThread {
            dartMessenger?.send(
                DartMessenger.EventType.ERROR,
                error
            )
        }
    }

    companion object {
        const val TAG = "AudioStreaming"
    }

    override fun onAuthErrorRtsp() {
        Log.i(TAG, "AudioStreaming: onAuthErrorRtsp")
        if (isInterrupted) {
             Log.i(TAG, "AudioStreaming: Ignoring onAuthErrorRtsp because isInterrupted = true")
             return
        }
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.ERROR, "Auth error")
        }
    }

    override fun onAuthSuccessRtsp() {
        Log.i(TAG, "AudioStreaming: onAuthSuccessRtsp")
    }

    override fun onConnectionFailedRtsp(reason: String) {
        Log.i(TAG, "AudioStreaming: onConnectionFailedRtsp")
        if (isInterrupted) {
             Log.i(TAG, "AudioStreaming: Ignoring onConnectionFailedRtsp because isInterrupted = true")
             return
        }
        activity?.runOnUiThread { //Wait 5s and retry connect stream
            if (rtspAudio.reTry(5000, reason)) {
                dartMessenger?.send(DartMessenger.EventType.RTMP_RETRY, reason)
            } else {
                dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Failed retry")
                rtspAudio.stopStream()
            }
        }
    }

    override fun onConnectionStartedRtsp(rtspUrl: String) {
        Log.i(TAG, "AudioStreaming: onConnectionStartedRtsp")
    }

    override fun onConnectionSuccessRtsp() {
        Log.i(TAG, "AudioStreaming: onConnectionSuccessRtsp")

        // Always log connection success
        Log.i(TAG, "AudioStreaming: RTSP connection successful")

        // Check if this was a reconnection after interruption
        if (savedUrl != null) {
            Log.i(TAG, "AudioStreaming: Reconnection successful (savedUrl set) - sending AUDIO_RESUMED")

            // Additional safety check
            if (activity != null) {
                activity?.runOnUiThread {
                    dartMessenger?.send(
                        DartMessenger.EventType.AUDIO_RESUMED,
                        "Stream resumed after interruption"
                    )
                }
                savedUrl = null
            } else {
                Log.e(TAG, "AudioStreaming ERROR: Activity is null in onConnectionSuccessRtsp, cannot send event")
                savedUrl = null  // Clear it anyway
            }
        }
    }

    override fun onDisconnectRtsp() {
        Log.i(TAG, "AudioStreaming: onDisconnectRtsp")
        if (isInterrupted) {
             Log.i(TAG, "AudioStreaming: Ignoring onDisconnectRtsp because isInterrupted = true")
             return
        }
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Disconnected")
        }
    }

    override fun onNewBitrateRtsp(bitrate: Long) {
        Log.i(TAG, "AudioStreaming: onNewBitrateRtsp: $bitrate")
    }

    // --- Lifecycle Callbacks ---
    override fun onActivityResumed(activity: Activity) {
        if (activity === this.activity) {
            Log.i(TAG, "AudioStreaming: onActivityResumed")
            isInForeground = true

            if (pendingReconnect && savedUrl != null) {
                Log.i(TAG, "AudioStreaming: Resumed with pendingReconnect=true and savedUrl set - attempting reconnection")
                pendingReconnect = false

                // Reconnect immediately (no delay needed - resources should be ready)
                reconnectStream()
            } else if (pendingReconnect && savedUrl == null) {
                // Edge case: pending reconnect but URL was cleared (user stopped manually?)
                Log.i(TAG, "AudioStreaming: Resumed with pendingReconnect=true but savedUrl=null, clearing flag")
                pendingReconnect = false
                isInterrupted = false
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity === this.activity) {
            Log.i(TAG, "AudioStreaming: onActivityPaused")
            isInForeground = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
         if (activity === this.activity) {
             isInForeground = true
         }
    }
    override fun onActivityStopped(activity: Activity) {
         if (activity === this.activity) {
             isInForeground = false
         }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (activity === this.activity) {
            isInForeground = false
            application?.unregisterActivityLifecycleCallbacks(this)
        }
    }

    // --- Audio Focus ---
    private fun requestAudioFocus() {
        val audioManager = activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val result = audioManager?.requestAudioFocus(
            this,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "AudioStreaming: Audio focus GRANTED")
        } else {
            Log.w(TAG, "AudioStreaming: Audio focus REJECTED")
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.abandonAudioFocus(this)
        Log.i(TAG, "AudioStreaming: Audio focus abandoned")
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "AudioStreaming: Audio focus LOSS")
                // Usually we should stop, but depends on requirement. For now logging.
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "AudioStreaming: Audio focus LOSS_TRANSIENT")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "AudioStreaming: Audio focus GAIN")
            }
        }
    }
}