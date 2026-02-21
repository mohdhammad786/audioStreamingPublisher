package com.resideo.flutter_audio_streaming.services

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * RtmpClientImpl for HaishinKit 0.18.2.
 *
 * BYPASSES the StreamSession abstraction (which has an aggressive catch-all
 * that kills the session on any unrecognised RTMP status event) and instead
 * drives RtmpConnection + RtmpStream + MediaMixer directly — the same
 * pattern that worked reliably with 0.16, adapted for 0.18's API surface.
 */
class RtmpClientImpl(
    private val context: Context,
    private val handler: RtmpConnectionHandler
) : StreamingClient, IEventListener {

    private var connection: RtmpConnection? = null
    private var stream: RtmpStream? = null
    private var mixer: MediaMixer? = null
    private var streamingActive: Boolean = false
    private var audioSource: AudioRecordSource? = null
    private var lastUrl: String? = null
    private var pendingStreamName: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Cached audio configuration
    private var confBitrate: Int = 64 * 1024
    private var confSampleRate: Int = 44100
    private var confIsStereo: Boolean = false
    
    // Scope for coroutines
    private var scope = CoroutineScope(Dispatchers.Main + Job())

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
            // Clean up any existing mixer to prevent thread concurrency crashes
            mixer?.stopRunning()
            mixer?.attachAudio(0, null)
            mixer?.dispose()
            
            // Create a brand new mixer for the new session
            val newMixer = MediaMixer(context)
            mixer = newMixer

            val selectedSource = getBestAudioSource()
            
            // Configure AudioRecordSource to match our desired settings.
            // The mic channel and codec channelCount MUST match.
            val micChannel = if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val micSource = AudioRecordSource(context).apply {
                audioSource = selectedSource
                channel = micChannel
                this.sampleRate = sampleRate
            }

            // Register the audio source
            val attachResult = newMixer.attachAudio(0, micSource)
            if (attachResult.isFailure) {
                Log.e(TAG, "Failed to attach audio: ${attachResult.exceptionOrNull()}")
                return false
            }

            this.confBitrate = bitrate
            this.confSampleRate = sampleRate
            this.confIsStereo = isStereo
            this.audioSource = micSource
            
            Log.i(TAG, "Audio prepared: source=$selectedSource bitrate=$bitrate sampleRate=$sampleRate stereo=$isStereo")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "prepareAudio failed: ${e.message}", e)
            return false
        }
    }

    override fun startStream(url: String) {
        lastUrl = url
        Log.i(TAG, "🚀 startStream: Direct RtmpConnection+RtmpStream to $url")

        val currentMixer = mixer ?: run {
            Log.e(TAG, "❌ startStream failed: MediaMixer is null (prepareAudio failed or skipped)")
            handler.notifyDisconnected("ConnectFailed", "Mixer not initialized")
            return
        }

        // Parse URL into tcUrl (connection URL) and streamName (publish name)
        val uri = java.net.URI.create(url)
        val pathSegments = uri.path.split("/").filter { it.isNotEmpty() }
        if (pathSegments.size < 2) {
            Log.e(TAG, "❌ Invalid RTMP URL format: $url")
            handler.notifyDisconnected("ConnectFailed", "Invalid URL format")
            return
        }
        val app = pathSegments.first()
        val streamName = pathSegments.last()
        val port = if (uri.port == -1) 1935 else uri.port
        val tcUrl = "${uri.scheme}://${uri.host}:$port/$app"
        pendingStreamName = streamName
        
        Log.i(TAG, "📡 tcUrl=$tcUrl streamName=$streamName")

        // --- Create Connection ---
        val newConnection = RtmpConnection()
        newConnection.addEventListener(Event.RTMP_STATUS, this)
        newConnection.addEventListener(Event.IO_ERROR, this)

        // --- Create Stream ---
        val newStream = RtmpStream(context, newConnection)
        newStream.addEventListener(Event.RTMP_STATUS, this)
        
        // Configure audio codec settings on the stream
        newStream.audioSetting.bitRate = confBitrate
        newStream.audioSetting.sampleRate = confSampleRate
        newStream.audioSetting.channelCount = if (confIsStereo) 2 else 1
        Log.i(TAG, "🎤 Audio codec: bitRate=$confBitrate sampleRate=$confSampleRate ch=${if (confIsStereo) 2 else 1}")

        // Wire the mixer output to the stream
        currentMixer.registerOutput(newStream)

        // Start the mixer (begins audio capture loop)
        currentMixer.startRunning()
        Log.i(TAG, "🎙️ Mixer started, audio capture loop running")

        // Store references
        this.connection = newConnection
        this.stream = newStream

        // Connect to the RTMP server.
        // The RtmpStream's internal EventListener handles CONNECT_SUCCESS → createStream.
        // Our handleEvent below catches PUBLISH_START to confirm publishing.
        Log.i(TAG, "🔌 Connecting to $tcUrl ...")
        newConnection.connect(tcUrl)
    }

    /**
     * Handles RTMP status events from both the Connection and Stream.
     * This replaces the StreamSession wrapper and gives us full control.
     */
    override fun handleEvent(event: Event) {
        val data = EventUtils.toMap(event)
        val code = data["code"]?.toString() ?: ""
        val description = data["description"]?.toString() ?: ""

        Log.i(TAG, "📨 RTMP Event: code=$code desc=$description type=${event.type}")

        when (event.type) {
            Event.IO_ERROR -> {
                Log.e(TAG, "🔴 IO_ERROR: $description")
                if (streamingActive) {
                    streamingActive = false
                    handler.notifyDisconnected("IO_ERROR", description)
                }
                return
            }
        }

        when (code) {
            // Connection events
            RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                Log.i(TAG, "✅ NetConnection.Connect.Success — createStream will be called by RtmpStream internally")
                // RtmpStream's internal EventListener automatically calls
                // connection.createStream(stream) on CONNECT_SUCCESS.
                // After createStream succeeds, readyState → OPEN, which flushes
                // the queued publish command.
                //
                // We just need to call stream.publish(name) to queue the command.
                stream?.let { s ->
                    pendingStreamName?.let { name ->
                        Log.i(TAG, "� Queueing publish('$name')")
                        s.publish(name)
                    }
                }
            }
            
            RtmpConnection.Code.CONNECT_CLOSED.rawValue -> {
                Log.w(TAG, "🔴 NetConnection.Connect.Closed")
                if (streamingActive) {
                    streamingActive = false
                    handler.notifyDisconnected("Connect.Closed", description)
                }
            }
            
            RtmpConnection.Code.CONNECT_FAILED.rawValue -> {
                Log.e(TAG, "🔴 NetConnection.Connect.Failed: $description")
                streamingActive = false
                handler.notifyDisconnected("Connect.Failed", description)
            }

            // Stream events
            RtmpStream.Code.PUBLISH_START.rawValue -> {
                Log.i(TAG, "✅ NetStream.Publish.Start — AUDIO IS NOW FLOWING!")
                streamingActive = true
                handler.notifyConnected()
            }

            RtmpStream.Code.CONNECT_CLOSED.rawValue -> {
                Log.w(TAG, "⚠️ NetStream.Connect.Closed")
                if (streamingActive) {
                    streamingActive = false
                    handler.notifyDisconnected("Stream.Closed", description)
                }
            }

            RtmpStream.Code.CONNECT_FAILED.rawValue -> {
                Log.e(TAG, "🔴 NetStream.Connect.Failed: $description")
                if (streamingActive) {
                    streamingActive = false
                    handler.notifyDisconnected("Stream.Failed", description)
                }
            }

            // All other events: just log them but DO NOT kill the connection.
            // This is the critical difference from StreamSession which would
            // set readyState=CLOSED on any unrecognized code.
            else -> {
                Log.d(TAG, "ℹ️ Unhandled RTMP code: $code (ignoring, stream continues)")
            }
        }
    }

    override fun stopStream() {
        Log.d(TAG, "stopStream: shutting down connection, stream, and mixer")
        
        val currentMixer = mixer
        val currentStream = stream
        val currentConnection = connection
        mixer = null
        stream = null
        connection = null
        streamingActive = false

        scope.launch {
            try {
                currentMixer?.stopRunning()
                currentStream?.let { currentMixer?.unregisterOutput(it) }
                currentStream?.close()
                currentConnection?.close()
                currentMixer?.attachAudio(0, null)
                currentMixer?.dispose()
                audioSource = null
            } catch (e: Throwable) {
                Log.e(TAG, "Error during stopStream cleanup", e)
            }
        }
        
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
