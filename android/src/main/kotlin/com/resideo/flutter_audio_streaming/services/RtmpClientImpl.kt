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
 * Modeled EXACTLY after HaishinKit's own RtmpStreamSession.kt from version 0.17.0.
 * See: https://github.com/HaishinKit/HaishinKit.kt/blob/0.17.0/rtmp/src/main/java/com/haishinkit/rtmp/RtmpStreamSession.kt
 *
 * Key patterns from the official implementation:
 * 1. Register OUR listener on connection FIRST, then create RtmpStream
 * 2. Call publish() INSIDE the Connect.Success handler (not before connect)
 * 3. Listen on BOTH connection AND stream for RTMP_STATUS events
 */
class RtmpClientImpl(
    private val context: Context,
    private val handler: RtmpConnectionHandler
) : StreamingClient, IEventListener {

    private val mixer = MediaMixer(context)

    private var connection: RtmpConnection = RtmpConnection()
    private var stream: RtmpStream = RtmpStream(context, connection)

    // Audio settings saved across sessions
    private var lastBitrate: Int = 128 * 1024
    private var lastSampleRate: Int = 44100
    private var lastChannelCount: Int = 1

    private var streamingActive: Boolean = false
    private var audioSource: AudioRecordSource? = null
    private var lastUrl: String? = null
    private var pendingStreamName: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Match official pattern: register on both connection and stream
        connection.addEventListener(Event.RTMP_STATUS, this)
        stream.addEventListener(Event.RTMP_STATUS, this)
        mixer.registerOutput(stream)
        mixer.startRunning()
    }

    /**
     * Creates fresh connection + stream pair.
     * Matches the RtmpStreamSession.init{} pattern exactly:
     * 1. Create connection, add OUR listener
     * 2. Create stream, add OUR listener
     */
    private fun createFreshSession() {
        Log.d(TAG, "createFreshSession")
        try { connection.removeEventListener(Event.RTMP_STATUS, this) } catch (_: Throwable) {}
        try { stream.removeEventListener(Event.RTMP_STATUS, this) } catch (_: Throwable) {}
        try { mixer.unregisterOutput(stream) } catch (_: Throwable) {}

        // Exactly like RtmpStreamSession.init{}:
        connection = RtmpConnection()
        connection.addEventListener(Event.RTMP_STATUS, this)
        stream = RtmpStream(context, connection)
        stream.addEventListener(Event.RTMP_STATUS, this)

        applyAudioSettingsToStream()
        mixer.registerOutput(stream)
        Log.d(TAG, "createFreshSession: done")
    }

    // -------------------------------------------------------------------------
    // StreamingClient interface
    // -------------------------------------------------------------------------

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
            val selectedSource = getBestAudioSource()
            val micSource = AudioRecordSource(context).apply { audioSource = selectedSource }
            val attachResult = mixer.attachAudio(0, micSource)
            if (attachResult.isFailure) {
                Log.e(TAG, "Failed to attach audio: ${attachResult.exceptionOrNull()}")
                return false
            }
            audioSource = micSource
            lastBitrate = bitrate
            lastSampleRate = sampleRate
            lastChannelCount = if (isStereo) 2 else 1
            Log.i(TAG, "Audio prepared: source=$selectedSource bitrate=$bitrate sampleRate=$sampleRate stereo=$isStereo")
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

        createFreshSession()

        lastUrl = url
        pendingStreamName = streamName
        streamingActive = true

        // CRITICAL: Do NOT call stream.publish() here!
        // In 0.17.0, publish must be called INSIDE the Connect.Success handler.
        // This matches the official RtmpStreamSession pattern exactly.
        connection.connect(baseUrl)
        Log.i(TAG, "startStream: connecting to $baseUrl (publish will be called on Connect.Success)")
    }

    override fun stopStream() {
        pendingStreamName = null
        try {
            stream.close()
            runBlocking { mixer.attachAudio(0, null) }
            audioSource = null
            Log.i(TAG, "stopStream: audio source detached")
        } catch (_: Throwable) {}
        try { connection.close() } catch (_: Throwable) {}
        streamingActive = false
    }

    override fun disableAudio() { stream.hasAudio = false }
    override fun enableAudio() { stream.hasAudio = true }

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
    // IEventListener — matches RtmpStreamSession.handleEvent() exactly
    // -------------------------------------------------------------------------

    override fun handleEvent(event: Event) {
        val data = EventUtils.toMap(event)
        val code = data["code"]?.toString() ?: return

        Log.w(TAG, "⚡ RTMP EVENT: $code | data=$data")

        when (code) {
            // Official pattern: call publish() inside Connect.Success
            RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                Log.i(TAG, "🔗 Connected — calling publish('$pendingStreamName')")
                pendingStreamName?.let { stream.publish(it) }
                handler.notifyConnected()
            }

            // Publish acknowledged — stream is live
            RtmpStream.Code.PUBLISH_START.rawValue -> {
                Log.i(TAG, "🎙️ Publish.Start — stream is live, starting audio codec")
                startAudioCodecForEncoding()
            }

            // Connection closed/failed
            RtmpConnection.Code.CONNECT_CLOSED.rawValue,
            RtmpConnection.Code.CONNECT_FAILED.rawValue,
            RtmpConnection.Code.CONNECT_REJECTED.rawValue -> {
                streamingActive = false
                handler.notifyDisconnected(code, data["description"]?.toString())
            }

            // Auth errors
            "NetStream.Publish.BadName",
            "NetStream.Publish.Rejected" -> handler.notifyAuthError(code)
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun startAudioCodecForEncoding() {
        try {
            var cls: Class<*>? = stream.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField("audioCodec")
                    f.isAccessible = true
                    val codec = f.get(stream)
                    codec?.javaClass?.getMethod("startRunning")?.invoke(codec)
                    Log.i(TAG, "✅ audioCodec.startRunning() via ${cls.simpleName}")
                    return
                } catch (_: NoSuchFieldException) { cls = cls.superclass }
            }
            Log.e(TAG, "❌ audioCodec field not found in class hierarchy")
        } catch (e: Exception) {
            Log.e(TAG, "❌ audioCodec.startRunning() failed: ${e.message}")
        }
    }

    private fun applyAudioSettingsToStream() {
        stream.audioSetting.bitRate = lastBitrate
        stream.audioSetting.sampleRate = lastSampleRate
        stream.audioSetting.channelCount = lastChannelCount
        stream.hasAudio = true
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
