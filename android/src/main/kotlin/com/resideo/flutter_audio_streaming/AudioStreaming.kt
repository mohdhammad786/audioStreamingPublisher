package com.resideo.flutter_audio_streaming

import android.app.Activity
import android.content.Context
import android.media.AudioManager
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
        try {
            rtspAudio.stopStream()
            result.success(null)
        } catch (e: IllegalStateException) {
            result.error("StopAudioStreamingFailed", e.message, null)
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