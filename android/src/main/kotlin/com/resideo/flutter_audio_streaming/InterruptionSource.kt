package com.resideo.flutter_audio_streaming

/**
 * Tracks the source of stream interruption.
 * Used to determine timeout duration and event types.
 */
enum class InterruptionSource {
    /**
     * No active interruption.
     */
    NONE,

    /**
     * Stream interrupted by phone call (30s timeout).
     */
    PHONE_CALL,

    /**
     * Stream interrupted by network loss (25s timeout).
     */
    NETWORK
}
