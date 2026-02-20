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
 * MINIMAL delta from the working 0.16.0 code.
 *
 * The ONLY change from 0.16.0 → 0.17.0 is:
 *   mixer.startRunning() added to init{}
 *
 * In 0.16.0, MediaMixer.init{} called doAudio() automatically.
 * In 0.17.0, the audio capture loop requires an explicit startRunning() call.
 * Everything else is IDENTICAL to the working 0.16.0 version.
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

    init {
        mixer.registerOutput(stream)
        mixer.startRunning()  // ← THE ONLY ADDITION for 0.17.0
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
            val selectedSource = getBestAudioSource()

            val micSource = AudioRecordSource(context).apply {
                audioSource = selectedSource
            }

            val attachResult = mixer.attachAudio(0, micSource)

            if (attachResult.isFailure) {
                Log.e(TAG, "Failed to attach source (source=$selectedSource): ${attachResult.exceptionOrNull()}")
                return false
            }

            audioSource = micSource
            stream.audioSetting.bitRate = bitrate
            stream.audioSetting.sampleRate = sampleRate
            stream.audioSetting.channelCount = if (isStereo) 2 else 1
            stream.hasAudio = true

            Log.i(TAG, "Audio prepared: source=$selectedSource bitrate=$bitrate sampleRate=$sampleRate stereo=$isStereo")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "prepareAudio failed: ${e.message}", e)
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
        connection.connect(baseUrl)
        stream.publish(streamName)
        streamingActive = true
        Log.i(TAG, "startStream: $baseUrl / $streamName")
    }

    override fun stopStream() {
        try {
            stream.close()
            runBlocking { mixer.attachAudio(0, null) }
            audioSource = null
            Log.i(TAG, "stopStream: audio source detached")
        } catch (_: Throwable) {}
        connection.close()
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

    override fun handleEvent(event: Event) {
        val data = event.data
        if (data is Map<*, *>) {
            val code = data["code"] as? String ?: return
            Log.w(TAG, "⚡ RTMP EVENT: $code")
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
