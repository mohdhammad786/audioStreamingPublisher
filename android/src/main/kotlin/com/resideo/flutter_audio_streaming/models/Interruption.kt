package com.resideo.flutter_audio_streaming.models

import java.util.Date
import java.util.UUID

/**
 * Base class for all interruptions.
 */
sealed class Interruption(val source: InterruptionSource) {
    val timestamp: Date = Date()
    val id: UUID = UUID.randomUUID()

    override fun toString(): String {
        return "[$source] started at $timestamp"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Interruption) return false
        return source == other.source
    }

    override fun hashCode(): Int {
        return source.hashCode()
    }
}

class PhoneCallInterruption : Interruption(InterruptionSource.PHONE_CALL)
class NetworkInterruption : Interruption(InterruptionSource.NETWORK)
class SystemResourceInterruption : Interruption(InterruptionSource.SYSTEM_RESOURCE)
