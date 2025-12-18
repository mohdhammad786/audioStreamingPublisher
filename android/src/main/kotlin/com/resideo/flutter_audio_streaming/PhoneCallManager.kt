package com.resideo.flutter_audio_streaming

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Monitors phone call states to handle stream interruptions.
 */
class PhoneCallManager(
    private val context: Context,
    private val mediator: StreamingMediator
) : PhoneCallMonitorInterface {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var phoneStateListener: Any? = null 
    private var isListening = false

    override val isCallActive: Boolean
        get() {
            if (telephonyManager == null) return false
            return telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE
        }

    companion object {
        private const val TAG = "PhoneCallManager"
    }

    override fun startMonitoring() {
        if (isListening || telephonyManager == null) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerTelephonyCallback()
            } else {
                registerPhoneStateListener()
            }
            isListening = true
            Log.d(TAG, "Phone monitoring started (API ${Build.VERSION.SDK_INT})")
            
            // Initial check
            if (isCallActive) { // Changed from isCallActive() to isCallActive property
                Log.i(TAG, "Call active upon registration")
                mediator.onPhoneInterruptionBegan()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start phone monitoring: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallback() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallState(state)
            }
        }
        telephonyManager?.registerTelephonyCallback(context.mainExecutor, callback)
        phoneStateListener = callback
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallState(state)
            }
        }
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        phoneStateListener = listener
    }

    override fun stopMonitoring() {
        if (!isListening || phoneStateListener == null || telephonyManager == null) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = phoneStateListener as? TelephonyCallback
                callback?.let { telephonyManager.unregisterTelephonyCallback(it) }
            } else {
                val listener = phoneStateListener as? PhoneStateListener
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
            isListening = false
            phoneStateListener = null
            Log.d(TAG, "Phone monitoring stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop phone monitoring: ${e.message}")
        }
    }

    private fun handleCallState(state: Int) {
        val stateStr = when(state) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "UNKNOWN"
        }
        Log.i(TAG, "Call State Changed: $stateStr")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                mediator.onPhoneInterruptionBegan()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                mediator.onPhoneInterruptionEnded()
            }
        }
    }
}
