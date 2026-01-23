package com.resideo.flutter_audio_streaming.services

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.resideo.flutter_audio_streaming.models.*
import com.resideo.flutter_audio_streaming.utils.DartMessenger

interface InterruptionDelegate {
    fun stopStreamForInterruption()
    fun reconnectStream()
    fun abandonAudioFocus()
    fun transitionTo(event: StreamEvent): Boolean
    fun getStreamState(): StreamState
    fun runOnMainThread(block: () -> Unit)
}

class InterruptionManager(
    private val context: StreamingContext,
    private val dartMessenger: DartMessenger?
) {
    lateinit var delegate: InterruptionDelegate

    companion object {
        private const val TAG = "InterruptionManager"
        private const val NETWORK_INTERRUPTION_TIMEOUT_MS = 30000L
    }

    @Volatile var isNetworkLost = false
    @Volatile var isPhoneCallActive = false
    
    private var interruptionTimerStartedAt: Long = 0L
    private var interruptionDeadlineMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var interruptionRunnable: Runnable? = null

    // Phone Call Interruption Handlers
    fun handlePhoneInterruptionBegan() {
        Log.i(TAG, "📱 Phone Call Interruption Detected")
        
        if (isPhoneCallActive) return
        isPhoneCallActive = true
        
        // If we are already interrupted by network, phone takes over UI priority
        if (delegate.getStreamState() == StreamState.INTERRUPTED) {
            Log.i(TAG, "Already interrupted (likely network) - updating source to phone")
            context.currentInterruptionSource = InterruptionSource.PHONE_CALL
            
            // Send event immediately as requested for higher responsiveness
            delegate.runOnMainThread {
                val extras = mapOf("remainingSeconds" to getRemainingInterruptionSeconds())
                dartMessenger?.send(DartMessenger.EventType.AUDIO_INTERRUPTED, "Phone call active", extras)
            }
            return
        }

        context.currentInterruptionSource = InterruptionSource.PHONE_CALL
        handleInterruptionBeganInternal()
    }

    fun handlePhoneInterruptionEnded() {
        Log.i(TAG, "📱 Phone Call Interruption Ended")

        if (!isPhoneCallActive) return
        isPhoneCallActive = false

        // Scenario 3: If network is still lost, stay interrupted but switch source
        if (isNetworkLost) {
            Log.w(TAG, "Phone ended but network still lost - staying interrupted as NETWORK")
            context.currentInterruptionSource = InterruptionSource.NETWORK
            // Do NOT restart timer here - preserve the existing deadline (time conservation)
            
            delegate.runOnMainThread {
                dartMessenger?.send(DartMessenger.EventType.NETWORK_INTERRUPTED, "Phone call ended but network still lost")
            }
            return
        }

        handleInterruptionEndedInternal()
    }

    // Network Interruption Handlers
    fun handleNetworkLost() {
        Log.i(TAG, "🌐 Network Lost Detected")

        if (isNetworkLost) return
        isNetworkLost = true

        // If phone call is active, network loss is recorded but phone keeps UI priority
        if (isPhoneCallActive) {
            Log.d(TAG, "Network lost during phone call - recorded but phone has priority")
            return
        }

        // Otherwise, trigger interruption as NETWORK
        context.currentInterruptionSource = InterruptionSource.NETWORK
        handleInterruptionBeganInternal()
    }

    fun handleNetworkAvailable() {
        Log.i(TAG, "🌐 Network Available")

        if (!isNetworkLost) return
        isNetworkLost = false

        // If phone call is still active, we are still interrupted
        if (isPhoneCallActive) {
            Log.d(TAG, "Network restored but phone call still active - staying interrupted")
            return
        }

        // Only proceed if we were actually interrupted by network
        if (context.currentInterruptionSource == InterruptionSource.NETWORK) {
            Log.i(TAG, "🌐 Ending network interruption - initiating stabilization")
            
            // CRITICAL FIX: Cancel timer IMMEDIATELY to prevent race conditions
            cancelInterruptionTimeout()
            
            // CRITICAL FIX: Add stabilization delay (1000ms) for Android network stack
            mainHandler.postDelayed({
                // Re-verify flags after delay
                if (isNetworkLost) {
                    Log.w(TAG, "Network became lost again during stabilization - restarting timer")
                    startInterruptionTimeout() // Restart timer
                    return@postDelayed
                }
                
                if (isPhoneCallActive || delegate.getStreamState() != StreamState.INTERRUPTED) {
                    Log.w(TAG, "State changed during stabilization - aborting")
                    return@postDelayed
                }
                
                handleInterruptionEndedInternal()
            }, 1000)
        }
    }

    // Common Interruption Handlers (Internal)
    private fun handleInterruptionBeganInternal() {
        Log.i(TAG, "handleInterruptionBeganInternal - Source: ${context.currentInterruptionSource}, State: ${delegate.getStreamState()}")

        // If already interrupted, don't restart logic, just update timers if needed
        if (delegate.getStreamState() == StreamState.INTERRUPTED) {
            Log.d(TAG, "Already in INTERRUPTED state")
            return
        }

        val shifted = delegate.transitionTo(StreamEvent.InterruptionBegan)
        if (!shifted) return

        // 1. Release Mic/Resources Immediately
        delegate.stopStreamForInterruption()

        // 2. Abandon audio focus
        delegate.abandonAudioFocus()

        // 3. Start Interruption Timeout (Scenario 2: 30s timer)
        startInterruptionTimeout()
    }

    fun handleInterruptionEndedInternal() {
        Log.i(TAG, "handleInterruptionEndedInternal - Source: ${context.currentInterruptionSource}, State: ${delegate.getStreamState()}")

        if (delegate.getStreamState() != StreamState.INTERRUPTED) {
            Log.d(TAG, "Ignoring end - not in INTERRUPTED state")
            return
        }

        // Cancel timeout
        cancelInterruptionTimeout()

        // Attempt Reconnection
        if (context.isInForeground) {
            Log.i(TAG, "End of all interruptions - triggering reconnection")
            delegate.reconnectStream()
        } else {
            Log.i(TAG, "End of interruptions but app in background - pending reconnect")
            context.pendingReconnectOnResume = true
        }
    }

    private fun startInterruptionTimeout() {
        cancelInterruptionTimeout()
        val now = System.currentTimeMillis()
        if (interruptionTimerStartedAt == 0L || interruptionDeadlineMs == 0L) {
            interruptionTimerStartedAt = now
            interruptionDeadlineMs = now + NETWORK_INTERRUPTION_TIMEOUT_MS
        }
        val remainingMs = interruptionDeadlineMs - now
        interruptionRunnable = Runnable {
            Log.w(TAG, "Interruption timeout expired (source=${context.currentInterruptionSource}) - Aborting reconnection")
            // Notify delegate to fail
            delegate.runOnMainThread {
                 dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Stream stopped due to prolonged interruption")
            }
            delegate.transitionTo(StreamEvent.ReconnectionFailed)
        }
        mainHandler.postDelayed(interruptionRunnable!!, remainingMs.coerceAtLeast(0L))
        Log.d(TAG, "Interruption timer started: remaining=${remainingMs}ms (source=${context.currentInterruptionSource})")
    }

    fun cancelInterruptionTimeout() {
        interruptionRunnable?.let {
            mainHandler.removeCallbacks(it)
            interruptionRunnable = null
            Log.d(TAG, "Interruption timer cancelled (source=${context.currentInterruptionSource})")
        }
        interruptionTimerStartedAt = 0L
        interruptionDeadlineMs = 0L
    }
    
    fun getRemainingInterruptionSeconds(): Int {
        if (interruptionDeadlineMs == 0L) return 30
        val remaining = (interruptionDeadlineMs - System.currentTimeMillis()) / 1000
        return remaining.toInt().coerceAtLeast(0)
    }
    
    fun handleResumeFromInterruption(isCallActive: Boolean) {
        // Fix for Camera/External App Interruption
        // If we are interrupted by "PhoneCall" (which includes Audio Focus loss)
        // but there is no actual GSM call, and we just resumed, try to resume streaming.
        if (delegate.getStreamState() == StreamState.INTERRUPTED && context.currentInterruptionSource == InterruptionSource.PHONE_CALL) {
             if (!isCallActive) {
                 Log.i(TAG, "Resumed while interrupted by AudioFocus/Camera - attempting resume")
                 
                 // Ensure flag is cleared since we know call is inactive
                 isPhoneCallActive = false
                 
                 // Check network status before resuming
                 if (isNetworkLost) {
                     Log.w(TAG, "Phone ended but network still lost - staying interrupted as NETWORK")
                     context.currentInterruptionSource = InterruptionSource.NETWORK
                     handleNetworkLost() // Ensure timer is running
                     handlePhoneInterruptionEnded()
                 } else {
                     handleInterruptionEndedInternal()
                 }
                 return
             }
        }

        if (context.pendingReconnectOnResume) {
            Log.d(TAG, "Resumed with pending reconnect")
            context.pendingReconnectOnResume = false
            delegate.reconnectStream()
        }
    }

    fun reset() {
        cancelInterruptionTimeout()
        isNetworkLost = false
        isPhoneCallActive = false
        context.currentInterruptionSource = InterruptionSource.NONE
    }
}
