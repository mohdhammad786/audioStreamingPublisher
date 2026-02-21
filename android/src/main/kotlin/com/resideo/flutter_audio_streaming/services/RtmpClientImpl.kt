package com.resideo.flutter_audio_streaming.services

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.util.Log
import androidx.annotation.RequiresPermission
import com.resideo.flutter_audio_streaming.interfaces.StreamingClient
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.AudioOnlySingleStreamer
import io.github.thibaultbee.streampack.core.interfaces.startStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RtmpClientImpl(
    private val context: Context,
    private val handler: RtmpConnectionHandler
) : StreamingClient {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var streamer: AudioOnlySingleStreamer? = null
    private var streamingActive: Boolean = false
    private var lastUrl: String? = null
    
    // We store config parameters here so we can create the streamer suspend-safely
    private var confBitrate = 64000
    private var confSampleRate = 44100
    private var confIsStereo = false

    override val isStreaming: Boolean
        get() = streamingActive

    override suspend fun prepareAudio(
        bitrate: Int,
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean, // StreamPack supports these intrinsically depending on hardware
        noiseSuppressor: Boolean
    ): Boolean {
        try {
            Log.i(TAG, "prepareAudio: bitrate=$bitrate sampleRate=$sampleRate isStereo=$isStereo")
            this.confBitrate = bitrate
            this.confSampleRate = sampleRate
            this.confIsStereo = isStereo
            
            // Release any previously active streamer to avoid memory/hardware leaks
            streamer?.release()
            
            // We instantiate the streamer. This requires coroutines context as it's a suspend function
            // but our prepareAudio is already suspend
            streamer = AudioOnlySingleStreamer(
                context = context,
                audioSourceFactory = MicrophoneSourceFactory()
            )

            // StreamPack AudioConfig uses startBitrate, sampleRate, and channelConfig
            val audioConfig = AudioConfig(
                startBitrate = bitrate,
                sampleRate = sampleRate,
                channelConfig = if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            )

            streamer?.setAudioConfig(audioConfig)

            // Listen for asynchronous connection drops or errors
            streamer?.let { s ->
                scope.launch {
                    s.throwableFlow.collect { throwable ->
                        if (throwable != null) {
                            Log.e(TAG, "🔴 StreamPack Error Flow emitted:", throwable)
                            // We only notify if we thought we were streaming
                            if (streamingActive) {
                                streamingActive = false
                                handler.notifyDisconnected("StreamPackError", throwable.message ?: "Unknown StreamPack Exception")
                            }
                        }
                    }
                }
                scope.launch {
                    s.isOpenFlow.collect { isOpen ->
                        Log.i(TAG, "🔄 StreamPack isOpenFlow: \$isOpen")
                        if (!isOpen && streamingActive) {
                            Log.e(TAG, "🔴 StreamPack connection closed unexpectedly")
                            streamingActive = false
                            handler.notifyDisconnected("Disconnected", "StreamPack session closed")
                        }
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "prepareAudio error", e)
            return false
        }
    }

    override fun startStream(url: String) {
        lastUrl = url
        scope.launch {
            try {
                Log.i(TAG, "🚀 startStream: Using StreamPack to connect to \$url")
                streamer?.startStream(url)
                streamingActive = true
                Log.i(TAG, "🔗 Session OPEN & Publishing via StreamPack!")
                handler.notifyConnected()
            } catch (e: Exception) {
                Log.e(TAG, "🔴 Session CLOSED unexpectedly", e)
                handler.notifyDisconnected("Failed", e.message ?: "Unknown error")
            }
        }
    }

    override fun stopStream() {
        scope.launch {
            try {
                if (streamingActive) {
                    Log.d(TAG, "stopStream: shutting down streamer")
                    streamer?.stopStream()
                    streamingActive = false
                    Log.d(TAG, "stopStream: done")
                }
                // Release the underlying hardware resources (Mic, Encoders) and clear
                streamer?.release()
                streamer = null
            } catch (e: Exception) {
                Log.e(TAG, "stopStream error", e)
            }
        }
    }

    override fun disableAudio() {
        try {
            streamer?.audioInput?.isMuted = true
        } catch (e: Exception) {
             Log.e(TAG, "disableAudio error", e)
        }
    }

    override fun enableAudio() {
        try {
            streamer?.audioInput?.isMuted = false
        } catch (e: Exception) {
             Log.e(TAG, "enableAudio error", e)
        }
    }

    override fun reTry(delay: Long, reason: String): Boolean {
        Log.w(TAG, "reTry requested due to \$reason, delay=\$delay")
        stopStream()
        lastUrl?.let { startStream(it) }
        return true
    }

    companion object {
        private const val TAG = "RtmpClientImpl"
    }
}
