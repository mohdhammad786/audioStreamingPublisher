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
 * RtmpClientImpl for HaishinKit 0.17.0.
 *
 * KEY INSIGHT: RtmpStream.init{} calls connection.createStream(stream) ONLY if
 * connection.isConnected is true at construction time. Previously we created the
 * stream before connecting, so createStream was never called.
 * The internal EventListener that should call createStream on Connect.Success
 * doesn't fire reliably in the JitPack build.
 *
 * FIX: Defer RtmpStream creation until AFTER Connect.Success, when
 * connection.isConnected is true. This makes RtmpStream.init{} call
 * createStream immediately → readyState=OPEN → publish message sent
 * → Publish.Start → audio codec starts → audio data flows.
 */
class RtmpClientImpl(
    private val context: Context,
    private val handler: RtmpConnectionHandler
) : StreamingClient, IEventListener {

    private val connection = RtmpConnection()
    private val mixer = MediaMixer(context)
    
    // Stream is created lazily after connection succeeds
    private var stream: RtmpStream? = null
    private var streamingActive: Boolean = false
    private var audioSource: AudioRecordSource? = null
    private var lastUrl: String? = null
    private var pendingStreamName: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        mixer.startRunning()
        connection.addEventListener(Event.RTMP_STATUS, this)
        Log.i(TAG, "INIT: mixer running, connection listener registered")
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
            Log.i(TAG, "AudioRecord state=${micSource.audioRecord?.state}, recording=${micSource.audioRecord?.recordingState}")
            
            // Save settings — they'll be applied to the stream when it's created
            savedBitrate = bitrate
            savedSampleRate = sampleRate
            savedIsStereo = isStereo
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "prepareAudio failed: ${e.message}", e)
            return false
        }
    }
    
    // Audio settings saved for deferred stream configuration
    private var savedBitrate: Int = 64000
    private var savedSampleRate: Int = 44100
    private var savedIsStereo: Boolean = false

    override fun startStream(url: String) {
        val lastSlash = url.lastIndexOf('/')
        if (lastSlash == -1) {
            Log.e(TAG, "Invalid URL: $url")
            return
        }
        val baseUrl = url.substring(0, lastSlash)
        val streamName = url.substring(lastSlash + 1)

        lastUrl = url
        pendingStreamName = streamName
        
        Log.i(TAG, "🚀 startStream: connecting to $baseUrl (streamName=$streamName)")
        Log.i(TAG, "🚀 mixer.running=${mixer.isRunning}, mixer.hasAudio=${mixer.hasAudio}")
        
        // Step 1: Connect. Stream will be created in handleEvent on Connect.Success.
        connection.connect(baseUrl)
        streamingActive = true
    }

    override fun stopStream() {
        try {
            stream?.close()
            stream?.let { mixer.unregisterOutput(it) }
            stream = null
            runBlocking { mixer.attachAudio(0, null) }
            audioSource = null
        } catch (_: Throwable) {}
        connection.close()
        streamingActive = false
        pendingStreamName = null
        Log.d(TAG, "stopStream: done")
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

    override fun handleEvent(event: Event) {
        val data = event.data
        if (data is Map<*, *>) {
            val code = data["code"] as? String ?: return
            Log.w(TAG, "⚡ RTMP EVENT: $code")
            when (code) {
                "NetConnection.Connect.Success" -> {
                    Log.i(TAG, "🔗 Connect.Success — connection.isConnected=${connection.isConnected}")
                    
                    // Step 2: NOW create the RtmpStream.
                    // Since connection.isConnected is true, RtmpStream.init{} will
                    // call connection.createStream(stream) IMMEDIATELY.
                    val newStream = RtmpStream(context, connection)
                    stream = newStream
                    
                    // Register stream as mixer output so audio data flows through it
                    mixer.registerOutput(newStream)
                    
                    // Configure audio settings on the stream
                    newStream.audioSetting.bitRate = savedBitrate
                    newStream.audioSetting.sampleRate = savedSampleRate
                    newStream.audioSetting.channelCount = if (savedIsStereo) 2 else 1
                    newStream.hasAudio = true
                    
                    Log.i(TAG, "� Stream created — hasAudio=${newStream.hasAudio}")
                    
                    // Step 3: Publish. If readyState is already OPEN (createStream
                    // completed synchronously), this sends the publish message
                    // immediately. Otherwise it queues for replay when OPEN.
                    pendingStreamName?.let { name ->
                        newStream.publish(name)
                        Log.i(TAG, "🔗 publish('$name') called")
                    }
                    
                    handler.notifyConnected()
                }
                "NetStream.Publish.Start" -> {
                    Log.i(TAG, "🎙️ Publish.Start! Audio should now be flowing.")
                }
                "NetConnection.Connect.Closed",
                "NetConnection.Connect.Failed",
                "NetConnection.Connect.Rejected" -> {
                    Log.e(TAG, "🔴 Disconnect: $code")
                    streamingActive = false
                    handler.notifyDisconnected(code, data["description"] as? String)
                }
                "NetStream.Publish.BadName",
                "NetStream.Publish.Rejected" -> handler.notifyAuthError(code)
                else -> Log.d(TAG, "📌 Event: $code")
            }
        }
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
