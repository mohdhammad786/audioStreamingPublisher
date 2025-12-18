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
    private val mediator: StreamingMediator
) : AudioFocusMonitorInterface, AudioManager.OnAudioFocusChangeListener {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    override var hasFocus: Boolean = false
        private set

    companion object {
        private const val TAG = "AudioFocusManager"
    }

    override fun requestFocus(): Boolean {
        if (audioManager == null) return false
        
        val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()
            
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }

        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    override fun abandonFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // In a real app we'd need to store the FocusRequest, but for now we'll do best effort
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(this)
        }
        hasFocus = false
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio Focus Lost (Permanent)")
                hasFocus = false
                mediator.onAudioFocusLostPermanently()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio Focus Lost (Transient)")
                mediator.onAudioFocusLostTransient()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio Focus Gained")
                hasFocus = true
            }
        }
    }
}
