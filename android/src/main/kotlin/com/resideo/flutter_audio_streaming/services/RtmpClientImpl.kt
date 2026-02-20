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

class RtmpClientImpl(
    private val context: Context,
    private val handler: RtmpConnectionHandler
) : StreamingClient, IEventListener {

    // -------------------------------------------------------------------------
    // MediaMixer: long-lived, reused across all sessions.
    //
    // HaishinKit 0.17.0 change: the audio capture loop no longer auto-starts in
    // init{}. We call startRunning() once here and leave it running permanently.
    //
    // IMPORTANT: Never call mixer.stopRunning() — after stopRunning() sets
    // keepAlive=false, a subsequent startRunning() launches a coroutine that
    // exits immediately (while(keepAlive) is false). We cannot restart it.
    // Instead, we merely detach the audio source on stop; the loop idles with
    // 1-second delays until a new source is attached on the next session.
    // -------------------------------------------------------------------------
    private val mixer = MediaMixer(context)

    // -------------------------------------------------------------------------
    // RtmpConnection + RtmpStream: must be created fresh for each session.
    //
    // HaishinKit 0.17.0 change: RtmpConnection.close() iterates its internal
    // `streams: ConcurrentHashMap` and calls streams.remove() on each entry.
    // After close(), the stream is permanently gone from that map. Reusing the
    // same objects means stream.publish() silently fails on reconnect (the
    // stream is no longer registered), the RTMP server times out, and we receive
    // NetConnection.Connect.Closed → spurious NETWORK_INTERRUPTED.
    // -------------------------------------------------------------------------
    private var connection: RtmpConnection = RtmpConnection()
    private var stream: RtmpStream = RtmpStream(context, connection)

    // Audio settings stored so they can be re-applied to the fresh stream that
    // createFreshSession() creates (the stream that prepareAudio() configured
    // is discarded when startStream() calls createFreshSession()).
    private var lastBitrate: Int = 128 * 1024
    private var lastSampleRate: Int = 44100
    private var lastChannelCount: Int = 1

    private var streamingActive: Boolean = false
    private var audioSource: AudioRecordSource? = null
    private var lastUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Wire initial stream into mixer and start the audio capture loop once.
        mixer.registerOutput(stream)
        mixer.startRunning()
        connection.addEventListener(Event.RTMP_STATUS, this)
    }

    // -------------------------------------------------------------------------
    // Creates fresh RtmpConnection + RtmpStream for a new stream session.
    // The old objects are cleaned up before being replaced.
    // -------------------------------------------------------------------------
    private fun createFreshSession() {
        Log.d(TAG, "createFreshSession: recycling connection + stream")

        try { connection.removeEventListener(Event.RTMP_STATUS, this) } catch (_: Throwable) {}
        try { mixer.unregisterOutput(stream) } catch (_: Throwable) {}

        connection = RtmpConnection()
        stream = RtmpStream(context, connection)

        mixer.registerOutput(stream)
        connection.addEventListener(Event.RTMP_STATUS, this)

        Log.d(TAG, "createFreshSession: fresh session ready")
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

            // Store settings — will be re-applied to the fresh stream in startStream()
            lastBitrate = bitrate
            lastSampleRate = sampleRate
            lastChannelCount = if (isStereo) 2 else 1

            // Also apply to the current stream in case startStream is called without
            // a fresh session (e.g. first ever start where session was created in init).
            applyAudioSettingsToStream()

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

        // Recreate connection + stream so publish() always succeeds (0.17.0 requirement)
        createFreshSession()

        // Re-apply audio settings to the brand-new stream object
        applyAudioSettingsToStream()

        lastUrl = url
        connection.connect(baseUrl)
        stream.publish(streamName)
        streamingActive = true

        Log.i(TAG, "startStream: connecting to $baseUrl / stream=$streamName")
    }

    override fun stopStream() {
        try {
            stream.close()
            // Detach audio source to release the microphone hardware.
            // Do NOT call mixer.stopRunning() — see class-level comment.
            runBlocking { mixer.attachAudio(0, null) }
            audioSource = null
            Log.i(TAG, "stopStream: audio source detached")
        } catch (_: Throwable) {}
        try {
            connection.close()
        } catch (_: Throwable) {}
        streamingActive = false
    }

    override fun disableAudio() {
        stream.hasAudio = false
    }

    override fun enableAudio() {
        stream.hasAudio = true
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

    // -------------------------------------------------------------------------
    // IEventListener — handles RTMP status events from RtmpConnection
    // -------------------------------------------------------------------------

    override fun handleEvent(event: Event) {
        val data = event.data
        if (data is Map<*, *>) {
            val code = data["code"] as? String ?: return
            when (code) {
                "NetConnection.Connect.Success" -> handler.notifyConnected()
                "NetConnection.Connect.Closed",
                "NetConnection.Connect.Failed",
                "NetConnection.Connect.Rejected" -> {
                    streamingActive = false
                    handler.notifyDisconnected(code, data["description"] as? String)
                }
                "NetStream.Publish.BadName",
                "NetStream.Publish.Rejected" -> handler.notifyAuthError(code)
                // HaishinKit 0.17.0 fix: when publish is acknowledged by the server,
                // readyState=PUBLISHING has fired (startRunning() sets isRunning=true on Stream)
                // but audioCodec.startRunning() is NOT called in the encode path.
                // We must start it here so AudioCodec.append() stops dropping audio.
                "NetStream.Publish.Start" -> startAudioCodecForEncoding()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * HaishinKit 0.17.0: starts the audio encoder via reflection.
     *
     * In 0.17.0, RtmpStream.startRunning() (called when readyState=PUBLISHING) sets
     * Stream.isRunning=true but never calls audioCodec.startRunning(). The configure()
     * method that starts the codec is only called in the decode path. For the encode
     * (publish) path we must start it explicitly. Since audioCodec is a private field,
     * we use reflection to access it and call startRunning().
     */
    private fun startAudioCodecForEncoding() {
        try {
            // audioCodec is declared in Stream (parent of RtmpStream) as:
            //   protected val audioCodec by lazy { AudioCodec() }
            val audioCodecField = stream.javaClass.superclass  // RtmpStream → Stream
                ?.getDeclaredField("audioCodec")
                ?: stream.javaClass.getDeclaredField("audioCodec")
            audioCodecField.isAccessible = true
            val audioCodec = audioCodecField.get(stream)
            val startRunning = audioCodec?.javaClass?.getMethod("startRunning")
            startRunning?.invoke(audioCodec)
            Log.i(TAG, "✅ audioCodec.startRunning() called — encode path ready")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start audioCodec via reflection: ${e.message}")
            // Fallback: try via superclass chain
            try {
                var cls: Class<*>? = stream.javaClass
                while (cls != null) {
                    try {
                        val f = cls.getDeclaredField("audioCodec")
                        f.isAccessible = true
                        val codec = f.get(stream)
                        codec?.javaClass?.getMethod("startRunning")?.invoke(codec)
                        Log.i(TAG, "✅ audioCodec.startRunning() via superclass ${cls.simpleName}")
                        break
                    } catch (_: NoSuchFieldException) {
                        cls = cls.superclass
                    }
                }
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Fallback also failed: ${e2.message}")
            }
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
