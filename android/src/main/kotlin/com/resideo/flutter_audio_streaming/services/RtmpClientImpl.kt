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
 * DIAGNOSTIC BUILD — traces every step of the audio pipeline to find why
 * audio data doesn't reach the RTMP server with HaishinKit 0.17.0.
 */
class RtmpClientImpl(
    private val context: Context,
    private val handler: RtmpConnectionHandler
) : StreamingClient, IEventListener {

    private val connection = RtmpConnection()
    private val stream = RtmpStream(context, connection)
    private val mixer = MediaMixer(context)
    private var streamingActive: Boolean = false
    private var audioSource: AudioRecordSource? = null
    private var lastUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Diagnostic
    private var diagLogCounter = 0
    private val diagHandler = Handler(Looper.getMainLooper())
    private var diagRunnable: Runnable? = null

    init {
        Log.i(TAG, "🔨 INIT: mixer.hasAudio=${mixer.hasAudio}, mixer.isRunning=${mixer.isRunning}")
        
        mixer.registerOutput(stream)
        Log.i(TAG, "🔨 INIT: After registerOutput — stream.hasAudio=${stream.hasAudio}")
        
        mixer.startRunning()
        Log.i(TAG, "🔨 INIT: After startRunning — mixer.isRunning=${mixer.isRunning}")
        
        connection.addEventListener(Event.RTMP_STATUS, this)
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
            Log.i(TAG, "🔧 prepareAudio: BEFORE — mixer.hasAudio=${mixer.hasAudio}")

            val selectedSource = getBestAudioSource()
            val micSource = AudioRecordSource(context).apply {
                audioSource = selectedSource
            }

            val attachResult = mixer.attachAudio(0, micSource)
            if (attachResult.isFailure) {
                Log.e(TAG, "❌ attachAudio FAILED: ${attachResult.exceptionOrNull()}")
                return false
            }

            audioSource = micSource
            
            Log.i(TAG, "✅ prepareAudio: AFTER — mixer.hasAudio=${mixer.hasAudio}")
            Log.i(TAG, "🔧 AudioRecord state=${micSource.audioRecord?.state}, recording=${micSource.audioRecord?.recordingState}")

            stream.audioSetting.bitRate = bitrate
            stream.audioSetting.sampleRate = sampleRate
            stream.audioSetting.channelCount = if (isStereo) 2 else 1
            stream.hasAudio = true

            Log.i(TAG, "🔧 prepareAudio: stream.hasAudio=${stream.hasAudio}, bitrate=$bitrate, sampleRate=$sampleRate")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ prepareAudio EXCEPTION: ${e.message}", e)
            return false
        }
    }

    override fun startStream(url: String) {
        val lastSlash = url.lastIndexOf('/')
        if (lastSlash == -1) {
            Log.e(TAG, "Invalid URL: $url")
            return
        }
        val baseUrl = url.substring(0, lastSlash)
        val streamName = url.substring(lastSlash + 1)

        lastUrl = url
        
        Log.i(TAG, "🚀 startStream: connecting to $baseUrl")
        Log.i(TAG, "🚀 BEFORE connect — conn=${connection.isConnected}, stream.hasAudio=${stream.hasAudio}, mixer.running=${mixer.isRunning}, mixer.hasAudio=${mixer.hasAudio}")
        Log.i(TAG, "🚀 audioRecord state=${audioSource?.audioRecord?.state}, recording=${audioSource?.audioRecord?.recordingState}")
        
        connection.connect(baseUrl)
        stream.publish(streamName)
        streamingActive = true
        
        Log.i(TAG, "🚀 startStream: publish('$streamName') queued")
        startDiagnosticLogger()
    }

    override fun stopStream() {
        stopDiagnosticLogger()
        try {
            stream.close()
            runBlocking { mixer.attachAudio(0, null) }
            audioSource = null
        } catch (_: Throwable) {}
        connection.close()
        streamingActive = false
        Log.d(TAG, "stopStream: done")
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

    override fun handleEvent(event: Event) {
        val data = event.data
        if (data is Map<*, *>) {
            val code = data["code"] as? String ?: return
            Log.w(TAG, "⚡ RTMP EVENT: $code | data=$data")
            when (code) {
                "NetConnection.Connect.Success" -> {
                    Log.i(TAG, "🔗 Connect.Success — stream.hasAudio=${stream.hasAudio}, mixer.running=${mixer.isRunning}, mixer.hasAudio=${mixer.hasAudio}")
                    handler.notifyConnected()
                }
                "NetStream.Publish.Start" -> {
                    Log.i(TAG, "🎙️ Publish.Start! stream.hasAudio=${stream.hasAudio}, audioRecord.recording=${audioSource?.audioRecord?.recordingState}")
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
    
    // ========= DIAGNOSTIC PERIODIC LOGGER =========
    private fun startDiagnosticLogger() {
        diagLogCounter = 0
        diagRunnable = object : Runnable {
            override fun run() {
                diagLogCounter++
                if (diagLogCounter > 10) return
                try {
                    val ar = audioSource?.audioRecord
                    Log.i(TAG, "📊 DIAG[$diagLogCounter/10]: " +
                        "active=$streamingActive " +
                        "conn=${connection.isConnected} " +
                        "hasAudio=${stream.hasAudio} " +
                        "mixerRun=${mixer.isRunning} " +
                        "mixerAudio=${mixer.hasAudio} " +
                        "arState=${ar?.state} " +
                        "arRec=${ar?.recordingState}" +
                        "")
                } catch (e: Exception) {
                    Log.e(TAG, "📊 DIAG[$diagLogCounter]: ${e.message}")
                }
                diagHandler.postDelayed(this, 2000)
            }
        }
        diagHandler.postDelayed(diagRunnable!!, 1000)
    }
    
    private fun stopDiagnosticLogger() {
        diagRunnable?.let { diagHandler.removeCallbacks(it) }
        diagRunnable = null
    }

    companion object {
        private const val TAG = "RtmpClientImpl"
    }
}
