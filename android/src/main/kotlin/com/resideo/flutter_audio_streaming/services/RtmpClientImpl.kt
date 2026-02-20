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
 * CRITICAL CHAIN for audio to flow:
 *   mixer.attachAudio(source) → mixer.hasAudio = true
 *   mixer.registerOutput(stream) → stream.dataSource = mixer
 *   stream.hasAudio getter returns mixer.hasAudio (via dataSource)
 *   When readyState=PUBLISHING → Stream.startRunning() checks hasAudio
 *   If hasAudio=true → audioCodec.startRunning() → audio flows
 *   If hasAudio=false → audioCodec NEVER starts → server times out
 *
 * Therefore: mixer must have audio attached BEFORE publish succeeds.
 * stopStream() must NOT detach audio from mixer — only close stream/connection.
 * Audio source is re-attached in prepareAudio() which is called before each session.
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
        connection.addEventListener(Event.RTMP_STATUS, this)
        stream.addEventListener(Event.RTMP_STATUS, this)
        mixer.registerOutput(stream)
        mixer.startRunning()
    }

    /**
     * Creates fresh connection + stream pair.
     * Matches the RtmpStreamSession.init{} ordering:
     * 1. Create connection, add OUR listener
     * 2. Create stream, add OUR listener
     * 3. Wire mixer
     */
    private fun createFreshSession() {
        Log.d(TAG, "createFreshSession")
        try { connection.removeEventListener(Event.RTMP_STATUS, this) } catch (_: Throwable) {}
        try { stream.removeEventListener(Event.RTMP_STATUS, this) } catch (_: Throwable) {}
        try { mixer.unregisterOutput(stream) } catch (_: Throwable) {}

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

        // Publish is called inside Connect.Success handler (official 0.17.0 pattern)
        connection.connect(baseUrl)
        Log.i(TAG, "startStream: connecting to $baseUrl (publish on Connect.Success)")
    }

    override fun stopStream() {
        pendingStreamName = null
        try {
            stream.close()
            // DO NOT detach audio from mixer here!
            // mixer.hasAudio must remain true so that Stream.startRunning()
            // calls audioCodec.startRunning() on the next session.
            // Audio will be re-attached in the next prepareAudio() call.
            Log.i(TAG, "stopStream: stream closed")
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
    // IEventListener — matches RtmpStreamSession.handleEvent()
    // -------------------------------------------------------------------------

    override fun handleEvent(event: Event) {
        val data = EventUtils.toMap(event)
        val code = data["code"]?.toString() ?: return

        Log.w(TAG, "⚡ RTMP EVENT: $code | data=$data")

        when (code) {
            RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                Log.i(TAG, "🔗 Connected — calling publish('$pendingStreamName')")
                pendingStreamName?.let { stream.publish(it) }
                handler.notifyConnected()
            }

            RtmpStream.Code.PUBLISH_START.rawValue -> {
                Log.i(TAG, "🎙️ Publish.Start — stream is live")
                // Dump full audio pipeline state after 2 seconds
                mainHandler.postDelayed({ dumpAudioChainState() }, 2000)
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

    /**
     * 🔍 DIAGNOSTIC: Dumps the full audio pipeline state via reflection.
     * Remove this after the bug is fixed.
     */
    private fun dumpAudioChainState() {
        try {
            Log.e(TAG, "═══ AUDIO CHAIN DIAGNOSTIC ═══")
            Log.e(TAG, "stream.hasAudio = ${stream.hasAudio}")
            Log.e(TAG, "stream class = ${stream.javaClass.name}")

            // Check Stream.isRunning
            var cls: Class<*>? = stream.javaClass
            while (cls != null) {
                try {
                    val isRunningField = cls.getDeclaredField("isRunning")
                    isRunningField.isAccessible = true
                    val isRunningVal = isRunningField.get(stream)
                    Log.e(TAG, "stream.isRunning (${cls.simpleName}) = $isRunningVal")
                    break
                } catch (_: NoSuchFieldException) { cls = cls.superclass }
            }

            // Dump ALL field names in the class hierarchy
            cls = stream.javaClass
            while (cls != null && cls != Any::class.java) {
                val fieldNames = cls.declaredFields.map { it.name }
                Log.e(TAG, "Fields in ${cls.simpleName}: $fieldNames")
                cls = cls.superclass
            }

            // Try to find audioCodec via various possible field names
            cls = stream.javaClass
            while (cls != null && cls != Any::class.java) {
                for (field in cls.declaredFields) {
                    if (field.name.contains("audio", ignoreCase = true) ||
                        field.name.contains("codec", ignoreCase = true)) {
                        field.isAccessible = true
                        val value = field.get(stream)
                        Log.e(TAG, "  ${cls.simpleName}.${field.name} = $value (type=${field.type.simpleName})")
                        // If it's a Lazy, try to get its value
                        if (value is Lazy<*>) {
                            val lazyVal = value.value
                            Log.e(TAG, "    └─ Lazy.value = $lazyVal")
                            // Check if it has isRunning
                            try {
                                val irField = lazyVal?.javaClass?.getDeclaredField("isRunning")
                                    ?: lazyVal?.javaClass?.superclass?.getDeclaredField("isRunning")
                                irField?.isAccessible = true
                                Log.e(TAG, "    └─ isRunning = ${irField?.get(lazyVal)}")
                            } catch (_: Exception) {}
                        }
                    }
                }
                cls = cls.superclass
            }

            // Check mixer state
            Log.e(TAG, "mixer.hasAudio = (checking via dataSource)")
            val dsField = stream.javaClass.superclass?.getDeclaredField("dataSource")
                ?: stream.javaClass.getDeclaredField("dataSource")
            dsField.isAccessible = true
            val ds = dsField.get(stream)
            Log.e(TAG, "stream.dataSource = $ds (null=${ds == null})")

            Log.e(TAG, "═══ END DIAGNOSTIC ═══")
        } catch (e: Exception) {
            Log.e(TAG, "Diagnostic failed: ${e.message}", e)
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
