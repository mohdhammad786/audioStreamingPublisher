package com.resideo.flutter_audio_streaming

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Manages Audio Focus for the application.
 * Ensures we respect other audio apps and system sounds.
 */
class AudioFocusManager(
    private val context: Context,
    private val onFocusLost: () -> Unit,
    private val onFocusLostTransient: () -> Unit
) : AudioManager.OnAudioFocusChangeListener {

    // ...

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Permanent focus loss")
                hasFocus = false
                onFocusLost()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Transient focus loss (call/notification) - Triggering interruption")
                // CRITICAL OPTIMIZATION: Use this as an early warning for phone calls.
                // It fires faster than PhoneStateListener.
                onFocusLostTransient()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Focus gained")
                hasFocus = true
                // Reconnection logic is driven by the main controller (AudioStreaming)
            }
        }
    }
}
