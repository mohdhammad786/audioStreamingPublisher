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

    private val connection = RtmpConnection()
    private val stream = RtmpStream(context, connection)
    private val mixer = MediaMixer(context)
    private var streamingActive: Boolean = false
    private var audioSource: AudioRecordSource? = null
    private var lastUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        mixer.registerOutput(stream)
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
            // Determine best audio source based on connected devices
            val selectedSource = getBestAudioSource()
            
            val micSource = AudioRecordSource(context).apply {
                audioSource = selectedSource
            }
            
            val attachResult = mixer.attachAudio(0, micSource)
            
            if (attachResult.isFailure) {
                Log.e("RtmpClientImpl", "Failed to attach source (source=$selectedSource): ${attachResult.exceptionOrNull()}")
                return false
            }
            
            audioSource = micSource
            stream.audioSetting.bitRate = bitrate
            stream.audioSetting.sampleRate = sampleRate
            stream.audioSetting.channelCount = if (isStereo) 2 else 1
            stream.hasAudio = true
            
            Log.i("RtmpClientImpl", "Audio prepared successfully with source: $selectedSource")
            return true
        } catch (e: Exception) {
            Log.e("RtmpClientImpl", "Failed to prepare audio: ${e.message}", e)
            return false
        }
    }

    private fun getBestAudioSource(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return MediaRecorder.AudioSource.MIC
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val hasExternalMic = devices.any { 
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET 
        }

        return if (hasExternalMic) {
             Log.i("RtmpClientImpl", "✅ External USB audio device detected. Using AudioSource.DEFAULT to allow OS routing.")
             MediaRecorder.AudioSource.DEFAULT
        } else {
             Log.i("RtmpClientImpl", "ℹ️ No external USB detected. Using AudioSource.MIC.")
             MediaRecorder.AudioSource.MIC
        }
    }

    override fun startStream(url: String) {
        val lastSlash = url.lastIndexOf('/')
        if (lastSlash == -1) {
            Log.e("RtmpClientImpl", "Invalid URL: $url")
            return
        }
        val baseUrl = url.substring(0, lastSlash)
        val streamName = url.substring(lastSlash + 1)

        lastUrl = url
        connection.connect(baseUrl)
        stream.publish(streamName)
        streamingActive = true
    }

    override fun stopStream() {
        try {
            stream.close()
            // Detach audio source from mixer to release resources (suspend function)
            runBlocking {
                mixer.attachAudio(0, null)
            }
            audioSource = null
            Log.i("RtmpClientImpl", "Stopped: Audio source detached")
        } catch (_: Throwable) {
        }
        connection.close()
        streamingActive = false
    }

    override fun disableAudio() {
        stream.hasAudio = false
    }

    override fun enableAudio() {
        stream.hasAudio = true
    }

    override fun reTry(delay: Long, reason: String): Boolean {
        val url = lastUrl
        if (url == null) {
            Log.e("RtmpClientImpl", "Cannot retry: No previous URL")
            return false
        }

        Log.i("RtmpClientImpl", "Retrying stream in ${delay}ms. Reason: $reason")
        
        mainHandler.postDelayed({
            stopStream()
            startStream(url)
        }, delay)
        
        return true
    }

    override fun handleEvent(event: Event) {
        val data = event.data
        if (data is Map<*, *>) {
            val code = data["code"] as? String
            if (code == null) return

            when (code) {
                "NetConnection.Connect.Success" -> handler.notifyConnected()
                "NetConnection.Connect.Closed",
                "NetConnection.Connect.Failed",
                "NetConnection.Connect.Rejected" -> {
                    streamingActive = false
                    val description = data["description"] as? String
                    handler.notifyDisconnected(code, description)
                }
                "NetStream.Publish.BadName",
                "NetStream.Publish.Rejected" -> handler.notifyAuthError(code)
            }
        }
    }
}
