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
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
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
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * RtmpClientImpl for HaishinKit 0.18.2.
 *
 * Uses direct RtmpConnection + RtmpStream + MediaMixer (bypasses StreamSession).
 * KEY FIX: Starts the mixer ONLY after PUBLISH_START to ensure the audio codec
 * is running before audio data arrives (avoids data being silently dropped).
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

    // Diagnostics
    private val audioBufferCount = AtomicLong(0)
    private val lastLogTime = AtomicLong(0)

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
            // Clean up any existing mixer
            mixer?.stopRunning()
            mixer?.attachAudio(0, null)
            mixer?.dispose()
            
            val newMixer = MediaMixer(context)
            mixer = newMixer

            val selectedSource = getBestAudioSource()
            val micChannel = if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val micSource = AudioRecordSource(context).apply {
                audioSource = selectedSource
                channel = micChannel
                this.sampleRate = sampleRate
            }

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
            Log.e(TAG, "❌ startStream failed: MediaMixer is null")
            handler.notifyDisconnected("ConnectFailed", "Mixer not initialized")
            return
        }

        // Parse URL
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

        // Create Connection
        val newConnection = RtmpConnection()
        newConnection.addEventListener(Event.RTMP_STATUS, this)
        newConnection.addEventListener(Event.IO_ERROR, this)

        // Create Stream
        val newStream = RtmpStream(context, newConnection)
        newStream.addEventListener(Event.RTMP_STATUS, this)
        
        // Configure audio codec
        newStream.audioSetting.bitRate = confBitrate
        newStream.audioSetting.sampleRate = confSampleRate
        newStream.audioSetting.channelCount = if (confIsStereo) 2 else 1
        Log.i(TAG, "🎤 Audio codec: bitRate=$confBitrate sampleRate=$confSampleRate ch=${if (confIsStereo) 2 else 1}")

        // Wire mixer → stream
        currentMixer.registerOutput(newStream)

        // DO NOT start the mixer yet — wait for PUBLISH_START.
        // Starting it now means audio data arrives before the codec is running,
        // so it gets silently dropped by Stream.append() (isRunning=false).

        // Store references
        this.connection = newConnection
        this.stream = newStream
        audioBufferCount.set(0)

        Log.i(TAG, "🔌 Connecting to $tcUrl ...")
        newConnection.connect(tcUrl)
    }

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
            RtmpConnection.Code.CONNECT_SUCCESS.rawValue -> {
                Log.i(TAG, "✅ NetConnection.Connect.Success")
                stream?.let { s ->
                    pendingStreamName?.let { name ->
                        Log.i(TAG, "📤 Queueing publish('$name')")
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

            RtmpStream.Code.PUBLISH_START.rawValue -> {
                Log.i(TAG, "✅ NetStream.Publish.Start — starting mixer NOW")
                
                // KEY FIX: Start mixer AFTER publish is confirmed.
                // The RtmpStream's readyState is now PUBLISHING, so startRunning()
                // has already been called internally, audioCodec is active,
                // and Stream.isRunning = true. Audio data will flow end-to-end.
                val currentMixer = mixer
                if (currentMixer != null) {
                    currentMixer.startRunning()
                    Log.i(TAG, "🎙️ Mixer started — audio capture loop running, data should flow now")
                } else {
                    Log.e(TAG, "❌ Mixer is null at PUBLISH_START!")
                }
                
                streamingActive = true
                handler.notifyConnected()
                
                // Start a diagnostic logger to confirm data flow
                startDiagnosticLogger()
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

            else -> {
                Log.d(TAG, "ℹ️ Unhandled RTMP code: $code (ignoring)")
            }
        }
    }

    private fun startDiagnosticLogger() {
        // Log connection stats every 2 seconds while streaming
        val diagnosticRunnable = object : Runnable {
            override fun run() {
                if (!streamingActive) return
                val conn = connection
                if (conn != null) {
                    val bytesOut = conn.totalBytesOut
                    val bytesIn = conn.totalBytesIn
                    val connected = conn.isConnected
                    Log.i(TAG, "📊 DIAG: bytesOut=$bytesOut bytesIn=$bytesIn connected=$connected mixerRunning=${mixer?.isRunning?.get()}")
                }
                mainHandler.postDelayed(this, 2000)
            }
        }
        mainHandler.postDelayed(diagnosticRunnable, 1000)
    }

    override fun stopStream() {
        Log.d(TAG, "stopStream: shutting down")
        
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
