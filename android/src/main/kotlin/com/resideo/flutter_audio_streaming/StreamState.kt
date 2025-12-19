package com.resideo.flutter_audio_streaming

/**
 * Represents the various states of the RTSP Audio Stream.
 * Using a state machine helps prevent invalid transitions and race conditions.
 */
enum class StreamState {
    /**
     * Initial state or stopped state. No active stream, no resources held.
     */
    IDLE,

    /**
     * Preparing components (AudioRecorder, Encoder, RTSP Client).
     */
    PREPARING,

    /**
     * Actively attempting to establish connection with the server.
     */
    CONNECTING,

    /**
     * Actively streaming audio to the server.
     */
    STREAMING,

    /**
     * Stream is paused/stopped due to an interruption (e.g., Phone Call).
     * We are waiting for the interruption to end to attempt reconnection.
     */
    INTERRUPTED,

    /**
     * Actively attempting to reconnect after an interruption or intermittent failure.
     */
    RECONNECTING,

    /**
     * A fatal error occurred, or the stream failed to reconnect within the timeout.
     */
    FAILED
}
