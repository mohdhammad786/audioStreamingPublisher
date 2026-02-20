package com.resideo.flutter_audio_streaming.services

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpStream
import com.haishinkit.rtmp.event.Event
import com.haishinkit.rtmp.event.EventUtils
import com.haishinkit.rtmp.event.IEventListener
import com.resideo.flutter_audio_streaming.interfaces.StreamingClient
import kotlinx.coroutines.runBlocking

/**
 * Modeled after HaishinKit's own RtmpStreamSession.kt (0.17.0).
 *
 * ALL components (MediaMixer, RtmpConnection, RtmpStream) are created fresh
 * for each streaming session. This eliminates ALL shared state bugs:
 * - MediaMixer's keepAlive flag (cannot restart after stopRunning)
 * - RtmpConnection's streams map (permanently emptied after close)
 * - Audio capture coroutine lifecycle
 */
class RtmpClientImpl(
    private val context: Context,
    private val handler: RtmpConnectionHandler
) : StreamingClient, IEventListener {

    private var mixer: MediaMixer? = null
    private var connection: RtmpConnection? = null
    private var stream: RtmpStream? = null

    // Audio settings saved across sessions
    private var lastBitrate: Int = 128 * 1024
    private var lastSampleRate: Int = 44100
    private var lastChannelCount: Int = 1

    private var streamingActive: Boolean = false
    private var lastUrl: String? = null
    private var pendingStreamName: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
        val lastSlash = url.lastIndexOf('/')
        if (lastSlash == -1) {
            Log.e(TAG, "Invalid RTMP URL: $url")
            return
        }
        val baseUrl = url.substring(0, lastSlash)
        val streamName = url.substring(lastSlash + 1)

        // Create EVERYTHING fresh — no shared state from previous sessions
        teardown()

        val newMixer = MediaMixer(context)
        val newConnection = RtmpConnection()
        newConnection.addEventListener(Event.RTMP_STATUS, this)
        val newStream = RtmpStream(context, newConnection)
        newStream.addEventListener(Event.RTMP_STATUS, this)

        // Apply audio settings
        newStream.audioSetting.bitRate = lastBitrate
        newStream.audioSetting.sampleRate = lastSampleRate
        newStream.audioSetting.channelCount = lastChannelCount
        newStream.hasAudio = true

        // Attach audio source to mixer
        val selectedSource = getBestAudioSource()
        val micSource = AudioRecordSource(context).apply {
            audioSource = selectedSource
        }
        runBlocking {
            val result = newMixer.attachAudio(0, micSource)
            if (result.isFailure) {
                Log.e(TAG, "Failed to attach audio: ${result.exceptionOrNull()}")
            }
        }

        // Wire mixer → stream and start audio capture
        newMixer.registerOutput(newStream)
        newMixer.startRunning()

        // Store references
        mixer = newMixer
        connection = newConnection
        stream = newStream
        lastUrl = url
        pendingStreamName = streamName
        streamingActive = true

        // Connect — publish will be called in Connect.Success handler
        newConnection.connect(baseUrl)
        Log.i(TAG, "startStream: connecting to $baseUrl (publish on Connect.Success)")
    }

    override fun stopStream() {
        pendingStreamName = null
        teardown()
        streamingActive = false
    }

    /**
     * Tears down all components cleanly. After this, mixer/connection/stream are null.
     */
    private fun teardown() {
        try { stream?.close() } catch (_: Throwable) {}
        try {
            connection?.removeEventListener(Event.RTMP_STATUS, this)
        } catch (_: Throwable) {}
        try { stream?.removeEventListener(Event.RTMP_STATUS, this) } catch (_: Throwable) {}
        try { connection?.close() } catch (_: Throwable) {}
        try {
            stream?.let { mixer?.unregisterOutput(it) }
        } catch (_: Throwable) {}
        try { mixer?.stopRunning() } catch (_: Throwable) {}
        try { mixer?.dispose() } catch (_: Throwable) {}
        mixer = null
        connection = null
        stream = null
        Log.d(TAG, "teardown: all components destroyed")
    }

    override fun disableAudio() { stream?.hasAudio = false }
    override fun enableAudio() { stream?.hasAudio = true }

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

    // -------------------------------------------------------------------------
    // IEventListener — matches RtmpStreamSession.handleEvent()
    // -------------------------------------------------------------------------

    override fun handleEvent(event: Event) {
        val data = EventUtils.toMap(event)
        val code = data["code"]?.toString() ?: return

        Log.w(TAG, "⚡ RTMP EVENT: $code | data=$data")

        when (code) {
            RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                Log.i(TAG, "🔗 Connected — calling publish('$pendingStreamName')")
                pendingStreamName?.let { stream?.publish(it) }
                handler.notifyConnected()
            }

            RtmpStream.Code.PUBLISH_START.rawValue -> {
                Log.i(TAG, "🎙️ Publish.Start — stream is live, audio should be flowing")
            }

            RtmpConnection.Code.CONNECT_CLOSED.rawValue,
            RtmpConnection.Code.CONNECT_FAILED.rawValue,
            RtmpConnection.Code.CONNECT_REJECTED.rawValue -> {
                streamingActive = false
                handler.notifyDisconnected(code, data["description"]?.toString())
            }

            "NetStream.Publish.BadName",
            "NetStream.Publish.Rejected" -> handler.notifyAuthError(code)
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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
