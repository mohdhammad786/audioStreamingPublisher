package com.resideo.flutter_audio_streaming.services

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.stream.StreamSession
import com.resideo.flutter_audio_streaming.interfaces.StreamingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * RtmpClientImpl for HaishinKit 0.17.0+ (using 0.18.2).
 *
 * Uses the officially supported StreamSession abstraction introduced
 * in 0.17.0, while correctly wiring the MediaMixer to the session stream
 * (matching the library author's demo app).
 */
class RtmpClientImpl(
    private val context: Context,
    private val handler: RtmpConnectionHandler
) : StreamingClient {

    private var session: StreamSession? = null
    private val mixer = MediaMixer(context)
    private var streamingActive: Boolean = false
    private var audioSource: AudioRecordSource? = null
    private var lastUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Scope to collect the session state flow
    private var sessionScope = CoroutineScope(Dispatchers.Main + Job())

    init {
        mixer.startRunning()
        Log.i(TAG, "INIT: MediaMixer started")
    }

    override val isStreaming: Boolean
        get() = streamingActive

    override suspend fun prepareAudio(
        bitrate: Int,
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        try {
            val selectedSource = getBestAudioSource()
            val micSource = AudioRecordSource(context).apply {
                audioSource = selectedSource
            }

            val attachResult = mixer.attachAudio(0, micSource)
            if (attachResult.isFailure) {
                Log.e(TAG, "Failed to attach audio: ${attachResult.exceptionOrNull()}")
                return false
            }

            audioSource = micSource
            Log.i(TAG, "Audio prepared: source=$selectedSource bitrate=$bitrate sampleRate=$sampleRate stereo=$isStereo")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "prepareAudio failed: ${e.message}", e)
            return false
        }
    }

    override fun startStream(url: String) {
        lastUrl = url
        Log.i(TAG, "🚀 startStream: Using StreamSession to connect to $url")

        // In 0.17.0+, we MUST build a new StreamSession for every connection
        val newSession = StreamSession.Builder(context, Uri.parse(url)).build()
        
        // Wire the mixer output to the new session's stream
        mixer.registerOutput(newSession.stream)
        
        // Apply audio settings
        audioSource?.let {
            newSession.stream.hasAudio = true
            // The audio pipeline is already setup in prepareAudio via mixer.attachAudio
        }

        // Cancel previous collector
        sessionScope.cancel()
        sessionScope = CoroutineScope(Dispatchers.Main + Job())

        // Monitor connection state
        sessionScope.launch {
            newSession.readyState.collectLatest { state ->
                Log.i(TAG, "🔄 StreamSession State: $state")
                when (state) {
                    StreamSession.ReadyState.OPEN -> {
                        streamingActive = true
                        Log.i(TAG, "🔗 Session OPEN & Publishing!")
                        handler.notifyConnected()
                    }
                    StreamSession.ReadyState.CLOSED -> {
                        if (streamingActive) {
                            Log.e(TAG, "🔴 Session CLOSED unexpectedly")
                            streamingActive = false
                            handler.notifyDisconnected("StreamSession.Closed", "Connection lost")
                        }
                    }
                    else -> {}
                }
            }
        }

        this.session = newSession

        // Connect (which internally drives rtmpStream.publish when ready)
        sessionScope.launch {
            newSession.connect().onFailure { error ->
                Log.e(TAG, "❌ Session connect failed: ${error.message}", error)
                streamingActive = false
                handler.notifyDisconnected("StreamSession.ConnectFailed", error.message)
            }
        }
    }

    override fun stopStream() {
        sessionScope.launch {
            try {
                session?.close()
                session?.let { mixer.unregisterOutput(it.stream) }
                session = null
            } catch (e: Throwable) {
                Log.e(TAG, "Error closing session", e)
            }
        }
        
        sessionScope.launch {
            try {
                mixer.attachAudio(0, null)
                audioSource = null
            } catch (e: Throwable) {
                Log.e(TAG, "Error cleanly detaching audio mix", e)
            }
        }
        
        streamingActive = false
        Log.d(TAG, "stopStream: done")
    }

    override fun disableAudio() { session?.stream?.hasAudio = false }
    override fun enableAudio() { session?.stream?.hasAudio = true }

    override fun reTry(delay: Long, reason: String): Boolean {
        val url = lastUrl ?: run {
            Log.e(TAG, "Cannot retry: no previous URL")
            return false
        }
        Log.i(TAG, "reTry: retrying in ${delay}ms — reason=$reason")
        mainHandler.postDelayed({
            stopStream()
            startStream(url)
        }, delay)
        return true
    }

    private fun getBestAudioSource(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return MediaRecorder.AudioSource.MIC
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val hasExternalMic = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return if (hasExternalMic) {
            Log.i(TAG, "✅ External USB mic — using AudioSource.DEFAULT")
            MediaRecorder.AudioSource.DEFAULT
        } else {
            Log.i(TAG, "ℹ️ No external USB — using AudioSource.MIC")
            MediaRecorder.AudioSource.MIC
        }
    }

    companion object {
        private const val TAG = "RtmpClientImpl"
    }
}
