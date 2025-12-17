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
    private val onFocusLost: () -> Unit
) : AudioManager.OnAudioFocusChangeListener {

    // Only keeping this logic simple as per Android docs:
    // We request GAIN when we start streaming.
    // If we lose focus, we must stop.

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var hasFocus = false

    companion object {
        private const val TAG = "AudioFocusManager"
    }

    /**
     * Requests permanent audio focus.
     * @return true if focus granted, false otherwise.
     */
    fun requestFocus(): Boolean {
        if (audioManager == null) {
            Log.e(TAG, "AudioManager is null, cannot request focus")
            return false
        }

        val result = audioManager.requestAudioFocus(
            this,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )

        hasFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        
        if (hasFocus) {
            Log.d(TAG, "Audio focus GRANTED")
        } else {
            Log.w(TAG, "Audio focus REJECTED")
        }
        
        return hasFocus
    }

    /**
     * Abandons audio focus.
     */
    fun abandonFocus() {
        if (!hasFocus) {
            return
        }

        audioManager?.abandonAudioFocus(this)
        hasFocus = false
        Log.d(TAG, "Audio focus abandoned")
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Permanent focus loss")
                hasFocus = false
                onFocusLost()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Transient focus loss (notification/call?) - IGNORING")
                // We purposefully ignore transient loss here. 
                // 1. If it's a phone call, PhoneStateListener handles it nicely (pausing stream).
                // 2. If we stop here, we lose the activeUrl and cannot resume.
                // 3. Android might lower volume automatically (ducking), which is fine.
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Focus gained")
                hasFocus = true
                // Reconnection logic is driven by the main controller (AudioStreaming)
            }
        }
    }
}
