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
import com.haishinkit.rtmp.Responder
import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpStream
import com.haishinkit.rtmp.event.Event
import com.haishinkit.rtmp.event.IEventListener
import com.resideo.flutter_audio_streaming.interfaces.StreamingClient
import kotlinx.coroutines.runBlocking

/**
 * RtmpClientImpl for HaishinKit 0.17.0.
 *
 * Creates RtmpStream before connecting (the old/stable approach).
 * After Connect.Success, also sends a MANUAL createStream call
 * via the public connection.call() API to verify server response.
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
        mixer.startRunning()
        connection.addEventListener(Event.RTMP_STATUS, this)
        Log.i(TAG, "INIT: stream+mixer created, listener registered")
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
        
        Log.i(TAG, "🚀 startStream: connecting to $baseUrl (streamName=$streamName)")
        
        connection.connect(baseUrl)
        stream.publish(streamName)
        streamingActive = true
    }

    override fun stopStream() {
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
                    Log.i(TAG, "🔗 Connect.Success — isConnected=${connection.isConnected}")
                    
                    // The core bug is that HaishinKit 0.17.0+ (on JitPack) fails to internally
                    // call createStream on Connect.Success. We must drive the RTMP protocol manually.
                    Log.i(TAG, "🛠️ Manually driving createStream protocol...")
                    
                    connection.call(
                        "createStream",
                        object : Responder {
                            override fun onResult(arguments: List<Any?>) {
                                val streamId = (arguments.getOrNull(0) as? Double)?.toInt() ?: return
                                Log.i(TAG, "✅ Server assigned Stream ID: $streamId")
                                
                                try {
                                    // 1. Set stream.id
                                    val idField = RtmpStream::class.java.getDeclaredField("id")
                                    idField.isAccessible = true
                                    idField.setInt(stream, streamId)
                                    
                                    // 2. Add to connection.streams map
                                    val streamsField = RtmpConnection::class.java.getDeclaredField("streams")
                                    streamsField.isAccessible = true
                                    val streamsMap = streamsField.get(connection) as MutableMap<Int, RtmpStream>
                                    streamsMap[streamId] = stream
                                    
                                    // 3. Set stream.readyState = OPEN.
                                    // CRITICAL: We MUST invoke the setter method, NOT set the backing field!
                                    // The Kotlin setter contains the logic that flushes the queued publish() message!
                                    val readyStateEnumClass = Class.forName("com.haishinkit.rtmp.RtmpStream\$ReadyState")
                                    val openEnumValue = readyStateEnumClass.enumConstants?.firstOrNull { it.toString() == "OPEN" }
                                    
                                    if (openEnumValue != null) {
                                        // Find the setReadyState method (Kotlin generates this for the var property)
                                        // Note: internal setters might have name mangling in Java (e.g. setReadyState$haishinkit_release)
                                        // So we search for any method starting with "setReadyState"
                                        val setReadyStateMethod = RtmpStream::class.java.methods.firstOrNull { 
                                            it.name.startsWith("setReadyState") && it.parameterTypes.size == 1 
                                        }
                                        
                                        if (setReadyStateMethod != null) {
                                            setReadyStateMethod.isAccessible = true
                                            setReadyStateMethod.invoke(stream, openEnumValue)
                                            Log.i(TAG, "✅ Forced stream readyState to OPEN via setter (${setReadyStateMethod.name}).")
                                        } else {
                                            Log.e(TAG, "❌ Could not find setReadyState method!")
                                        }
                                    } else {
                                        Log.e(TAG, "❌ Could not find ReadyState.OPEN enum value")
                                    }
                                    
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Reflection manually routing stream failed", e)
                                }
                            }
                            override fun onStatus(arguments: List<Any?>) {
                                Log.e(TAG, "❌ createStream failed: $arguments")
                            }
                        }
                    )
                    
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
