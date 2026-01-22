package com.resideo.flutter_audio_streaming

import com.pedro.rtplibrary.rtmp.RtmpOnlyAudio
import com.pedro.rtmp.utils.ConnectCheckerRtmp

/**
 * Concrete implementation of StreamingClient using Pedro's RTMP-only audio client.
 *
 * This mirrors the previous RTSP client wrapper but swaps the underlying protocol implementation to RTMP.
 * The public API remains defined by StreamingClient, so business logic stays unchanged.
 */
class RtmpClientImpl(checker: ConnectCheckerRtmp) : StreamingClient {
    private val rtmpAudio = RtmpOnlyAudio(checker)

    override val isStreaming: Boolean
        get() = rtmpAudio.isStreaming

    override fun prepareAudio(
        bitrate: Int,
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean {
        return rtmpAudio.prepareAudio(
            bitrate,
            sampleRate,
            isStereo,
            echoCanceler,
            noiseSuppressor
        )
    }

    override fun startStream(url: String) {
        rtmpAudio.startStream(url)
    }

    override fun stopStream() {
        rtmpAudio.stopStream()
    }

    override fun disableAudio() {
        rtmpAudio.disableAudio()
    }

    override fun enableAudio() {
        rtmpAudio.enableAudio()
    }

    override fun reTry(delay: Long, reason: String): Boolean {
        return rtmpAudio.reTry(delay, reason)
    }
}

