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
import com.haishinkit.rtmp.event.IEventListener
import com.resideo.flutter_audio_streaming.interfaces.StreamingClient
import kotlinx.coroutines.runBlocking

/**
 * HaishinKit 0.17.0 connection lifecycle notes:
 *
 * PROBLEM: In 0.17.0, RtmpStream.EventListener calls connection.createStream(stream)
 * inside the Connect.Success event handler. connection.createStream() calls connection.call()
 * which checks `if (!isConnected) return`. If socket.isConnected is still false at the
 * moment the event is dispatched to listeners (a timing issue), createStream is silently
 * dropped → server receives nothing → closes connection after ~10 seconds.
 *
 * FIX: Create the RtmpStream INSIDE our Connect.Success handler, where we KNOW the
 * connection is established. RtmpStream.init{} contains:
 *   `if (connection.isConnected) { connection.createStream(this) }`
 * which fires createStream directly and reliably at that moment.
 *
 * This means:
 * - startStream() only creates RtmpConnection and calls connect()
 * - RtmpStream is created in onConnected() (our Connect.Success callback)
 * - publish() is called after the stream is wired up
 */
class RtmpClientImpl(
    private val context: Context,
    private val handler: RtmpConnectionHandler
) : StreamingClient, IEventListener {

    // MediaMixer: long-lived, started once. Never call stopRunning() — see class doc.
    private val mixer = MediaMixer(context)

    // Connection is recreated per session (0.17.0: close() permanently removes streams).
    // Stream is created AFTER Connect.Success (see class doc).
    private var connection: RtmpConnection = RtmpConnection()
    private var stream: RtmpStream = RtmpStream(context, connection)

    // Stored between startStream() and Connect.Success so publish() can be called
    // once the stream is created inside onConnected().
    private var pendingStreamName: String? = null

    // Audio settings stored to re-apply to each fresh stream.
    private var lastBitrate: Int = 64 * 1024
    private var lastSampleRate: Int = 44100
    private var lastChannelCount: Int = 1

    private var streamingActive: Boolean = false
    private var audioSource: AudioRecordSource? = null
    private var lastUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        mixer.registerOutput(stream)
        mixer.startRunning()
        connection.addEventListener(Event.RTMP_STATUS, this)
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
                Log.e(TAG, "Failed to attach audio source: ${attachResult.exceptionOrNull()}")
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

        // Prepare a fresh connection. Do NOT create RtmpStream yet —
        // it will be created inside onConnected() once isConnected=true.
        try { connection.removeEventListener(Event.RTMP_STATUS, this) } catch (_: Throwable) {}
        connection = RtmpConnection()
        connection.addEventListener(Event.RTMP_STATUS, this)

        pendingStreamName = streamName
        lastUrl = url
        connection.connect(baseUrl)
        streamingActive = true

        Log.i(TAG, "startStream: connecting to $baseUrl / stream=$streamName")
    }

    /**
     * Called when Connect.Success fires. At this point connection.isConnected = true.
     * We create the RtmpStream HERE so its init{} can call createStream directly
     * via the `if (connection.isConnected)` branch — bypassing the event-listener
     * timing race that caused createStream to be silently dropped.
     */
    private fun onConnected() {
        Log.d(TAG, "onConnected: creating RtmpStream with live connection")
        try { mixer.unregisterOutput(stream) } catch (_: Throwable) {}

        // Creating stream when connection.isConnected = true →
        // RtmpStream.init{} calls connection.createStream(this) directly
        stream = RtmpStream(context, connection)
        applyAudioSettingsToStream()
        mixer.registerOutput(stream)

        // Queue the publish — will be sent when createStream response sets readyState=OPEN
        val streamName = pendingStreamName
        if (streamName != null) {
            stream.publish(streamName)
            pendingStreamName = null
        } else {
            Log.e(TAG, "onConnected: pendingStreamName is null — publish skipped")
        }
        Log.d(TAG, "onConnected: stream wired and publish queued")
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
    // IEventListener
    // -------------------------------------------------------------------------

    override fun handleEvent(event: Event) {
        val data = event.data
        if (data is Map<*, *>) {
            val code = data["code"] as? String ?: return
            Log.w(TAG, "⚡ RTMP EVENT: $code | desc=${data["description"]}")
            when (code) {
                "NetConnection.Connect.Success" -> {
                    // Create stream NOW (isConnected = true) then notify
                    onConnected()
                    handler.notifyConnected()
                }
                "NetConnection.Connect.Closed",
                "NetConnection.Connect.Failed",
                "NetConnection.Connect.Rejected" -> {
                    streamingActive = false
                    handler.notifyDisconnected(code, data["description"] as? String)
                }
                "NetStream.Publish.BadName",
                "NetStream.Publish.Rejected" -> handler.notifyAuthError(code)
                "NetStream.Publish.Start" -> startAudioCodecForEncoding()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * HaishinKit 0.17.0: RtmpStream.startRunning() sets Stream.isRunning=true but
     * never calls audioCodec.startRunning() in encode mode. AudioCodec.append()
     * silently drops all audio when isRunning=false. We start the codec via reflection.
     */
    private fun startAudioCodecForEncoding() {
        Log.i(TAG, "NetStream.Publish.Start — starting audio codec for encoding")
        try {
            var cls: Class<*>? = stream.javaClass
            while (cls != null) {
                try {
                    val f = cls.getDeclaredField("audioCodec")
                    f.isAccessible = true
                    val codec = f.get(stream)
                    codec?.javaClass?.getMethod("startRunning")?.invoke(codec)
                    Log.i(TAG, "✅ audioCodec.startRunning() called via ${cls.simpleName}")
                    break
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
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
