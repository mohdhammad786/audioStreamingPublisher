package com.resideo.flutter_audio_streaming

import com.pedro.rtplibrary.rtsp.RtspOnlyAudio
import com.pedro.rtsp.utils.ConnectCheckerRtsp

/**
 * Concrete implementation of StreamingClient using Pedro's Library.
 */
class RtspClientImpl(checker: ConnectCheckerRtsp) : StreamingClient {
    private val rtspAudio = RtspOnlyAudio(checker)

    override val isStreaming: Boolean
        get() = rtspAudio.isStreaming

    override fun prepareAudio(bitrate: Int, sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
        return rtspAudio.prepareAudio(bitrate, sampleRate, isStereo, echoCanceler, noiseSuppressor)
    }

    override fun startStream(url: String) {
        rtspAudio.startStream(url)
    }

    override fun stopStream() {
        rtspAudio.stopStream()
    }

    override fun disableAudio() {
        rtspAudio.disableAudio()
    }

    override fun enableAudio() {
        rtspAudio.enableAudio()
    }

    override fun reTry(delay: Long, reason: String): Boolean {
        return rtspAudio.reTry(delay, reason)
    }
}
