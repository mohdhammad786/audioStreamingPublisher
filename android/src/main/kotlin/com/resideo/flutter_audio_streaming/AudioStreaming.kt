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

class AudioStreaming(
    private var activity: Activity? = null,
    private var dartMessenger: DartMessenger? = null
) : ConnectCheckerRtsp {


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
        Log.d(TAG, "StartAudioStreaming url: $url")
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
        Log.d(TAG, "=== stopStreaming START ===")
        try {
            // Cancel any pending interruption timeout
            Log.d(TAG, "Cancelling interruption timeout")
            cancelInterruptionTimeout()

            Log.d(TAG, "Resetting state: isInterrupted = false, savedUrl = null")
            isInterrupted = false
            savedUrl = null

            // Unregister phone state listener
            phoneStateListener?.let {
                Log.d(TAG, "Unregistering PhoneStateListener")
                val telephonyManager = activity?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
                phoneStateListener = null
                Log.d(TAG, "PhoneStateListener unregistered")
            }

            Log.d(TAG, "Calling rtspAudio.stopStream()")
            rtspAudio.stopStream()
            Log.d(TAG, "rtspAudio.stopStream() completed")

            result.success(null)
            Log.d(TAG, "=== stopStreaming END SUCCESS ===")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException in stopStreaming: ${e.message}", e)
            e.printStackTrace()
            result.error("StopAudioStreamingFailed", e.message, null)
            Log.d(TAG, "=== stopStreaming END FAILURE ===")
        }
    }

    /*   override fun onConnectionSuccessRtmp() {
           Log.d(TAG, "onConnectionSuccessRtmp")
       }

       override fun onNewBitrateRtmp(bitrate: Long) {
           Log.d(TAG, "onNewBitrateRtmp: $bitrate")
       }

       override fun onDisconnectRtmp() {
           Log.d(TAG, "onDisconnectRtmp")
           activity?.runOnUiThread {
               dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Disconnected")
           }
       }

       override fun onAuthErrorRtmp() {
           Log.d(TAG, "onAuthErrorRtmp")
           activity?.runOnUiThread {
               dartMessenger?.send(DartMessenger.EventType.ERROR, "Auth error")
           }
       }

       override fun onAuthSuccessRtmp() {
           Log.d(TAG, "onAuthSuccessRtmp")
       }

       override fun onConnectionFailedRtmp(reason: String) {
           Log.d(TAG, "onConnectionFailedRtmp")
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
           Log.d(TAG, "onConnectionStartedRtmp")
       }*/

    private fun isPhoneCallActive(): Boolean {
        val telephonyManager = activity?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val audioManager = activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Check telephony manager call state
        val callState = telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE
        Log.d(TAG, "Checking phone call - TelephonyManager state: $callState")
        if (callState != TelephonyManager.CALL_STATE_IDLE) {
            Log.d(TAG, "Phone call detected via TelephonyManager: state=$callState")
            return true
        }

        // Check if audio is being used for communication (call mode)
        val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        Log.d(TAG, "Checking phone call - AudioManager mode: $audioMode")
        if (audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            Log.d(TAG, "Phone call detected via AudioManager: mode=$audioMode")
            return true
        }

        Log.d(TAG, "No phone call detected")
        return false
    }

    private fun registerPhoneStateListener() {
        if (phoneStateListener != null) {
            Log.d(TAG, "PhoneStateListener already registered")
            return
        }

        val telephonyManager = activity?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager == null) {
            Log.e(TAG, "TelephonyManager not available, cannot monitor phone calls")
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

                Log.d(TAG, "=== PhoneStateListener.onCallStateChanged ===")
                Log.d(TAG, "New state: $stateString")
                Log.d(TAG, "isInterrupted: $isInterrupted")
                Log.d(TAG, "isStreaming: ${rtspAudio.isStreaming}")

                when (state) {
                    TelephonyManager.CALL_STATE_RINGING,
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.d(TAG, "Phone call active - calling handleInterruptionBegan()")
                        handleInterruptionBegan()
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d(TAG, "Phone call ended - calling handleInterruptionEnded()")
                        handleInterruptionEnded()
                    }
                }
                Log.d(TAG, "=== PhoneStateListener.onCallStateChanged END ===")
            }
        }

        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.d(TAG, "PhoneStateListener registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register PhoneStateListener: ${e.message}")
            phoneStateListener = null
        }
    }

    private fun handleInterruptionBegan() {
        Log.d(TAG, "=== handleInterruptionBegan START ===")
        Log.d(TAG, "isInterrupted: $isInterrupted")
        Log.d(TAG, "rtspAudio.isStreaming: ${rtspAudio.isStreaming}")

        if (isInterrupted) {
            Log.d(TAG, "Already handling interruption - returning")
            return
        }

        if (!rtspAudio.isStreaming) {
            Log.d(TAG, "Not streaming, ignoring interruption - returning")
            return
        }

        isInterrupted = true
        Log.d(TAG, "Set isInterrupted = true")
        Log.d(TAG, "Phone call detected - gracefully stopping stream")

        // 1. Fully stop the stream (releases microphone)
        try {
            Log.d(TAG, "Calling rtspAudio.stopStream()...")
            rtspAudio.stopStream()
            Log.d(TAG, "rtspAudio.stopStream() completed successfully")
            Log.d(TAG, "After stop - isStreaming: ${rtspAudio.isStreaming}")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream: ${e.message}", e)
            e.printStackTrace()
        }

        // 2. Send AUDIO_INTERRUPTED event (NOT RTMP_STOPPED)
        Log.d(TAG, "Sending AUDIO_INTERRUPTED event to Flutter")
        activity?.runOnUiThread {
            dartMessenger?.send(
                DartMessenger.EventType.AUDIO_INTERRUPTED,
                "Phone call detected - stream paused temporarily"
            )
        }

        // 3. Start timeout (30 seconds)
        Log.d(TAG, "Starting interruption timeout timer")
        startInterruptionTimeout()
        Log.d(TAG, "=== handleInterruptionBegan END ===")
    }

    private fun handleInterruptionEnded() {
        Log.d(TAG, "=== handleInterruptionEnded START ===")
        Log.d(TAG, "isInterrupted: $isInterrupted")

        if (!isInterrupted) {
            Log.d(TAG, "Not in interrupted state - returning")
            return
        }

        Log.d(TAG, "Phone call ended - attempting reconnection")

        // Cancel timeout
        Log.d(TAG, "Cancelling interruption timeout")
        cancelInterruptionTimeout()

        // Add delay before reconnection attempt
        Log.d(TAG, "Scheduling reconnection with 1 second delay")
        interruptionHandler.postDelayed({
            Log.d(TAG, "Executing delayed reconnection")
            reconnectStream()
        }, 1000)  // 1 second delay

        Log.d(TAG, "=== handleInterruptionEnded END ===")
    }

    private fun startInterruptionTimeout() {
        cancelInterruptionTimeout()  // Cancel any existing timeout

        interruptionRunnable = Runnable {
            handleInterruptionTimeout()
        }

        interruptionHandler.postDelayed(interruptionRunnable!!, interruptionTimeout)
        Log.d(TAG, "Interruption timeout started (30 seconds)")
    }

    private fun cancelInterruptionTimeout() {
        interruptionRunnable?.let {
            interruptionHandler.removeCallbacks(it)
            interruptionRunnable = null
        }
    }

    private fun handleInterruptionTimeout() {
        Log.d(TAG, "Interruption timeout expired - giving up on reconnection")

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
        Log.d(TAG, "=== reconnectStream START ===")
        val url = savedUrl
        if (url == null) {
            Log.e(TAG, "No saved URL for reconnection")
            isInterrupted = false
            return
        }

        Log.d(TAG, "Reconnecting to: $url")
        Log.d(TAG, "Current state - prepared: $prepared, isStreaming: ${rtspAudio.isStreaming}")

        try {
            // CRITICAL: Add delay to ensure stream is fully stopped
            Log.d(TAG, "Waiting 500ms before reconnection...")
            Thread.sleep(500)

            Log.d(TAG, "After wait - prepared: $prepared, isStreaming: ${rtspAudio.isStreaming}")

            // Force re-prepare
            Log.d(TAG, "Force re-preparing stream...")
            prepared = false  // Force prepare
            if (!prepare()) {
                Log.e(TAG, "Failed to prepare stream for reconnection")
                handleReconnectionFailure("Failed to prepare stream")
                return
            }
            Log.d(TAG, "Stream prepared successfully")

            // Restart stream
            Log.d(TAG, "Calling rtspAudio.startStream($url)...")
            rtspAudio.startStream(url)
            Log.d(TAG, "rtspAudio.startStream() completed")
            Log.d(TAG, "After start - isStreaming: ${rtspAudio.isStreaming}")

            // Re-register phone state listener
            Log.d(TAG, "Re-registering phone state listener...")
            registerPhoneStateListener()

            // Reset state
            isInterrupted = false
            savedUrl = null
            Log.d(TAG, "Reset state - isInterrupted: false, savedUrl: null")

            // Send AUDIO_RESUMED event
            Log.d(TAG, "Sending AUDIO_RESUMED event to Flutter")
            activity?.runOnUiThread {
                dartMessenger?.send(
                    DartMessenger.EventType.AUDIO_RESUMED,
                    "Stream resumed after interruption"
                )
            }

            Log.d(TAG, "Reconnection successful!")
            Log.d(TAG, "=== reconnectStream END SUCCESS ===")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during reconnection: ${e.message}", e)
            e.printStackTrace()
            handleReconnectionFailure("Reconnection failed: ${e.message}")
            Log.d(TAG, "=== reconnectStream END FAILURE ===")
        }
    }

    private fun handleReconnectionFailure(error: String) {
        Log.e(TAG, error)

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
        Log.d(TAG, "onAuthErrorRtsp")
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.ERROR, "Auth error")
        }
    }

    override fun onAuthSuccessRtsp() {
        Log.d(TAG, "onAuthSuccessRtsp")
    }

    override fun onConnectionFailedRtsp(reason: String) {
        Log.d(TAG, "onConnectionFailedRtsp")
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
        Log.d(TAG, "onConnectionStartedRtsp")
    }

    override fun onConnectionSuccessRtsp() {
        Log.d(TAG, "onConnectionSuccessRtsp")
    }

    override fun onDisconnectRtsp() {
        Log.d(TAG, "onDisconnectRtsp")
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Disconnected")
        }
    }

    override fun onNewBitrateRtsp(bitrate: Long) {
        Log.d(TAG, "onNewBitrateRtsp: $bitrate")
    }
}