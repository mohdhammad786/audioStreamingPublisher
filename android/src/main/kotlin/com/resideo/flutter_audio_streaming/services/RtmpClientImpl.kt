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
import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpStream
import com.haishinkit.rtmp.RtmpStreamSessionFactory
import com.haishinkit.stream.StreamSession
import com.resideo.flutter_audio_streaming.interfaces.StreamingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Uses HaishinKit 0.17.0's official StreamSession API.
 * This is the recommended API — it manages connection, createStream,
 * publish and all RTMP protocol details internally.
 *
 * Pattern taken directly from the official example app:
 * - CameraViewModel.kt (mixer setup, session creation)
 * - CameraScreen.kt (connect/close via coroutine)
 * - RtmpStreamSession.kt (internal implementation)
 */
class RtmpClientImpl(
    private val context: Context,
    private val handler: RtmpConnectionHandler
) : StreamingClient {

    private var mixer: MediaMixer? = null
    private var session: StreamSession? = null
    private var scope: CoroutineScope? = null

    // Audio settings saved across sessions
    private var lastBitrate: Int = 128 * 1024
    private var lastSampleRate: Int = 44100
    private var lastChannelCount: Int = 1

    private var streamingActive: Boolean = false
    private var lastUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Register the RTMP factory so StreamSession.Builder can create RTMP sessions
        StreamSession.Builder.registerFactory(RtmpStreamSessionFactory)
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
        return try {
            lastBitrate = bitrate
            lastSampleRate = sampleRate
            lastChannelCount = if (isStereo) 2 else 1
            Log.i(TAG, "Audio settings saved: bitrate=$bitrate sampleRate=$sampleRate stereo=$isStereo")
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepareAudio failed: ${e.message}", e)
            false
        }
    }

    override fun startStream(url: String) {
        // Teardown any previous session
        teardown()

        lastUrl = url
        streamingActive = true

        // Create a new coroutine scope for this session
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sessionScope

        // Create session using official API
        val uri = Uri.parse(url)
        val newSession = StreamSession.Builder(context, uri)
            .setMode(StreamSession.Mode.PUBLISH)
            .build()

        // Create mixer and attach audio (exactly like CameraViewModel)
        val newMixer = MediaMixer(context)

        // Apply audio settings to the stream
        val stream = newSession.stream
        if (stream is RtmpStream) {
            stream.audioSetting.bitRate = lastBitrate
            stream.audioSetting.sampleRate = lastSampleRate
            stream.audioSetting.channelCount = lastChannelCount
        }

        // Attach audio source (like CameraViewModel.selectAudioDevice)
        val selectedSource = getBestAudioSource()
        val micSource = AudioRecordSource(context).apply {
            audioSource = selectedSource
        }
        sessionScope.launch(Dispatchers.IO) {
            val result = newMixer.attachAudio(0, micSource)
            if (result.isFailure) {
                Log.e(TAG, "Failed to attach audio: ${result.exceptionOrNull()}")
            } else {
                Log.i(TAG, "Audio source attached successfully")
            }
        }

        // Wire mixer → stream and start (exactly like CameraViewModel.init)
        newMixer.registerOutput(stream)
        newMixer.startRunning()

        mixer = newMixer
        session = newSession

        // Connect using coroutine (exactly like CameraScreen)
        sessionScope.launch {
            Log.i(TAG, "startStream: connecting to $url")
            val result = newSession.connect()
            result.onSuccess {
                Log.i(TAG, "✅ StreamSession connected and publishing!")
                handler.notifyConnected()
            }
            result.onFailure { error ->
                Log.e(TAG, "❌ StreamSession connect failed: ${error.message}")
                streamingActive = false
                handler.notifyDisconnected(
                    "NetConnection.Connect.Failed",
                    error.message
                )
            }
        }

        // Monitor readyState changes
        sessionScope.launch {
            newSession.readyState.collect { state ->
                Log.i(TAG, "📊 StreamSession readyState: $state")
                when (state) {
                    StreamSession.ReadyState.CLOSED -> {
                        if (streamingActive) {
                            Log.w(TAG, "Session closed while streaming — treating as disconnect")
                            streamingActive = false
                            mainHandler.post {
                                handler.notifyDisconnected(
                                    "NetConnection.Connect.Closed",
                                    null
                                )
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun stopStream() {
        teardown()
        streamingActive = false
    }

    private fun teardown() {
        try {
            session?.let { s ->
                scope?.launch {
                    try { s.close() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        try {
            session?.stream?.let { mixer?.unregisterOutput(it) }
        } catch (_: Throwable) {}
        try { mixer?.stopRunning() } catch (_: Throwable) {}
        try { mixer?.dispose() } catch (_: Throwable) {}
        try { scope?.cancel() } catch (_: Throwable) {}
        mixer = null
        session = null
        scope = null
        Log.d(TAG, "teardown: all components destroyed")
    }

    override fun disableAudio() {
        session?.stream?.hasAudio = false
    }

    override fun enableAudio() {
        session?.stream?.hasAudio = true
    }

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

    // No handleEvent needed — StreamSession manages all RTMP events internally

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
