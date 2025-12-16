package com.resideo.flutter_audio_streaming

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Monitors phone call states to handle stream interruptions.
 */
class PhoneCallManager(
    private val context: Context,
    private val onInterruptionBegan: () -> Unit,
    private val onInterruptionEnded: () -> Unit
) {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var phoneStateListener: PhoneStateListener? = null
    private var isListening = false

    companion object {
        private const val TAG = "PhoneCallManager"
    }

    fun startMonitoring() {
        if (isListening || telephonyManager == null) return

        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                handleCallState(state)
            }
        }

        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            isListening = true
            Log.d(TAG, "PhoneStateListener registered")
            
            // Initial check
            if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) {
                Log.i(TAG, "Call active upon registration")
                onInterruptionBegan()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register PhoneStateListener: ${e.message}")
        }
    }

    fun stopMonitoring() {
        if (!isListening || phoneStateListener == null || telephonyManager == null) return

        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            isListening = false
            phoneStateListener = null
            Log.d(TAG, "PhoneStateListener unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister PhoneStateListener: ${e.message}")
        }
    }

    private fun handleCallState(state: Int) {
        val stateStr = when(state) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "UNKNOWN"
        }
        Log.d(TAG, "Call State Changed: $stateStr")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                onInterruptionBegan()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                onInterruptionEnded()
            }
        }
    }

    fun isCallActive(): Boolean {
        if (telephonyManager == null) return false
        return telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE
    }
}
