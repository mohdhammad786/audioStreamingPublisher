package com.resideo.flutter_audio_streaming.services

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import android.media.MediaRecorder
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

    // Local SSL-to-TCP proxy for rtmps:// streams.
    // komuxer (embedded in StreamPack) opens a plain TCP socket even for rtmps:// URLs;
    // this proxy wraps the real SSLSocket so TLS is handled externally.
    private var rtmpsProxy: RtmpsTcpProxyServer? = null

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
        echoCanceler: Boolean, // Used to select hardware Mic profile
        noiseSuppressor: Boolean
    ): Boolean {
        try {
            Log.i(TAG, "prepareAudio: bitrate=$bitrate sampleRate=$sampleRate isStereo=$isStereo echoCanceler=$echoCanceler")
            this.confBitrate = bitrate
            this.confSampleRate = sampleRate
            this.confIsStereo = isStereo
            
            // Release any previously active streamer to avoid memory/hardware leaks
            streamer?.release()
            
            // Critical optimization for low-end / median Android hardware (e.g. Redmi):
            // We bypass software processing by initializing Android's native `VOICE_COMMUNICATION` profile 
            // when using built-in mics. If a USB OTG mic is connected, we MUST use DEFAULT/MIC to support it properly.
            val micSource = getBestAudioSource(echoCanceler, noiseSuppressor)

            // We instantiate the streamer. This requires coroutines context as it's a suspend function
            // but our prepareAudio is already suspend
            streamer = AudioOnlySingleStreamer(
                context = context,
                audioSourceFactory = MicrophoneSourceFactory(
                    audioSource = micSource,
                    effects = emptySet() // Explicitly disable software AEC & NS to avoid double processing dropping frames
                )
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
                val effectiveUrl = if (url.startsWith("rtmps://", ignoreCase = true)) {
                    // komuxer does not implement TLS for rtmps:// — it always opens a plain TCP
                    // socket. We start a local SSLSocket proxy and give StreamPack the plain
                    // rtmp://127.0.0.1:<port>/... URL so TLS is handled here in the JVM.
                    val withoutScheme = url.substring("rtmps://".length)
                    val slashIdx = withoutScheme.indexOf('/')
                    val hostPort = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
                    val path     = if (slashIdx >= 0) withoutScheme.substring(slashIdx) else "/"
                    val colonIdx = hostPort.lastIndexOf(':')
                    val remoteHost = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
                    val remotePort = if (colonIdx >= 0) hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443 else 443

                    // Stop any stale proxy from a previous attempt
                    rtmpsProxy?.stop()
                    val proxy = RtmpsTcpProxyServer().also { rtmpsProxy = it }
                    val localPort = proxy.start(remoteHost, remotePort)

                    val rewritten = "rtmp://127.0.0.1:$localPort$path"
                    Log.i(TAG, "🔐 RTMPS proxy: $url → $rewritten")
                    rewritten
                } else {
                    url
                }

                Log.i(TAG, "🚀 startStream: Using StreamPack to connect to $effectiveUrl")
                streamer?.startStream(effectiveUrl)
                streamingActive = true
                Log.i(TAG, "🔗 Session OPEN & Publishing via StreamPack!")
                handler.notifyConnected()
            } catch (e: Exception) {
                Log.e(TAG, "🔴 Session CLOSED unexpectedly", e)
                rtmpsProxy?.stop()
                rtmpsProxy = null
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
            } finally {
                // Always tear down the RTMPS proxy (no-op for plain rtmp://)
                rtmpsProxy?.stop()
                rtmpsProxy = null
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
        Log.w(TAG, "reTry requested due to $reason, delay=$delay")
        // Stop any running proxy before the new startStream creates a fresh one
        rtmpsProxy?.stop()
        rtmpsProxy = null
        stopStream()
        lastUrl?.let { startStream(it) }
        return true
    }

    private fun getBestAudioSource(echoCanceler: Boolean, noiseSuppressor: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return MediaRecorder.AudioSource.MIC
        
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val hasExternalMic = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        
        return if (hasExternalMic) {
            Log.i(TAG, "✅ External USB mic detected — bypassing hardware DSP and using AudioSource.DEFAULT")
            MediaRecorder.AudioSource.DEFAULT
        } else if (echoCanceler || noiseSuppressor) {
            Log.i(TAG, "ℹ️ Built-in mic with AEC/NS — using AudioSource.VOICE_COMMUNICATION")
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            Log.i(TAG, "ℹ️ Built-in raw mic — using AudioSource.MIC")
            MediaRecorder.AudioSource.MIC
        }
    }

    companion object {
        private const val TAG = "RtmpClientImpl"
    }
}
