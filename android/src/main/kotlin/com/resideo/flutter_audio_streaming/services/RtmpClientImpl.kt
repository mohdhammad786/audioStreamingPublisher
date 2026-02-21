package com.resideo.flutter_audio_streaming.services

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.rtmp.RtmpStreamSessionFactory
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
    private var mixer: MediaMixer? = null
    private var streamingActive: Boolean = false
    private var audioSource: AudioRecordSource? = null
    private var lastUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Cached audio configuration
    private var confBitrate: Int = 64 * 1024
    private var confSampleRate: Int = 44100
    private var confIsStereo: Boolean = true
    
    // Scope to collect the session state flow
    private var sessionScope = CoroutineScope(Dispatchers.Main + Job())

    init {
        StreamSession.Builder.registerFactory(RtmpStreamSessionFactory)
        Log.i(TAG, "INIT: RtmpClientImpl initialized")
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
            // Clean up any existing mixer to prevent thread concurrency crashes
            mixer?.stopRunning()
            mixer?.attachAudio(0, null)
            mixer?.dispose()
            
            // Create a brand new mixer for the new session
            val newMixer = MediaMixer(context)
            mixer = newMixer

            val selectedSource = getBestAudioSource()
            
            // CRITICAL FIX: Configure AudioRecordSource to match our desired settings.
            // The AudioRecordSource channel determines what the mic ACTUALLY records.
            // The codec channelCount must MATCH this, otherwise the encoder receives
            // mismatched PCM data and produces no output → server timeout.
            val micChannel = if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val micSource = AudioRecordSource(context).apply {
                audioSource = selectedSource
                channel = micChannel
                this.sampleRate = sampleRate
            }

            // Register the audio source before the mixer's internal loop starts
            val attachResult = newMixer.attachAudio(0, micSource)
            if (attachResult.isFailure) {
                Log.e(TAG, "Failed to attach audio: ${attachResult.exceptionOrNull()}")
                return false
            }

            this.confBitrate = bitrate
            this.confSampleRate = sampleRate
            this.confIsStereo = isStereo
            this.audioSource = micSource
            
            Log.i(TAG, "Audio prepared: source=$selectedSource bitrate=$bitrate sampleRate=$sampleRate stereo=$isStereo micChannel=${if (isStereo) "STEREO" else "MONO"}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "prepareAudio failed: ${e.message}", e)
            return false
        }
    }

    override fun startStream(url: String) {
        lastUrl = url
        Log.i(TAG, "🚀 startStream: Using StreamSession to connect to $url")

        val currentMixer = mixer ?: run {
            Log.e(TAG, "❌ startStream failed: MediaMixer is null (prepareAudio failed or skipped)")
            handler.notifyDisconnected("StreamSession.ConnectFailed", "Mixer not initialized")
            return
        }

        // In 0.17.0+, we MUST build a new StreamSession for every connection
        val newSession = StreamSession.Builder(context, Uri.parse(url)).build()
        
        // Wire the mixer output to the new session's stream BEFORE starting the loop.
        // After this call, stream.hasAudio delegates to mixer.hasAudio (audioSources.isNotEmpty())
        // so there is NO need to set stream.hasAudio = true manually.
        currentMixer.registerOutput(newSession.stream)
        
        // CRITICAL FIX: Only set bitRate on the codec. channelCount and sampleRate
        // are already configured on the AudioRecordSource (in prepareAudio) and the
        // codec defaults (44100Hz, MONO) match what AudioRecordSource records.
        // Setting channelCount=2 when AudioRecordSource records MONO causes the AAC
        // encoder to receive mismatched PCM data → no output frames → server timeout.
        audioSource?.let {
            newSession.stream.audioSetting.bitRate = confBitrate
            // Match codec settings to what AudioRecordSource is actually recording
            newSession.stream.audioSetting.sampleRate = confSampleRate
            newSession.stream.audioSetting.channelCount = if (confIsStereo) 2 else 1
            Log.i(TAG, "🎤 Audio codec configured: bitRate=$confBitrate sampleRate=$confSampleRate channelCount=${if (confIsStereo) 2 else 1}")
        }
        
        // NOW start the mixer safely. This guarantees that internal collections
        // (audioSources and outputs) are fully populated BEFORE the IO loop starts,
        // avoiding ConcurrentModificationExceptions.
        currentMixer.startRunning()

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
            Log.i(TAG, "🔌 Calling session.connect()...")
            newSession.connect().onSuccess {
                Log.i(TAG, "✅ session.connect() completed successfully")
            }.onFailure { error ->
                Log.e(TAG, "❌ Session connect failed: ${error.message}", error)
                streamingActive = false
                handler.notifyDisconnected("StreamSession.ConnectFailed", error.message)
            }
        }
    }

    override fun stopStream() {
        Log.d(TAG, "stopStream: shutting down session and mixer")
        
        val currentMixer = mixer
        val currentSession = session
        mixer = null // Detach immediately
        session = null
        streamingActive = false

        // Single coroutine for shutdown to avoid race conditions between
        // parallel coroutines interfering with each other's cleanup.
        sessionScope.launch {
            try {
                // 1. Stop the mixer's IO loop first so no more audio is fed
                currentMixer?.stopRunning()
                
                // 2. Close the RTMP session
                currentSession?.close()
                
                // 3. Unregister the stream output from the mixer
                currentSession?.let { currentMixer?.unregisterOutput(it.stream) }
                
                // 4. Detach audio source and dispose mixer
                currentMixer?.attachAudio(0, null)
                currentMixer?.dispose()
                audioSource = null
            } catch (e: Throwable) {
                Log.e(TAG, "Error during stopStream cleanup", e)
            }
        }
        
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
