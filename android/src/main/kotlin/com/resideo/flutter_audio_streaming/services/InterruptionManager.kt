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
    fun tryAcquireAudioFocus(): Boolean
    fun transitionTo(event: StreamEvent): Boolean
    fun getStreamState(): StreamState
    fun runOnMainThread(block: () -> Unit)
}

class InterruptionManager(
    private val context: StreamingContext,
    private val dartMessenger: DartMessenger?,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    lateinit var delegate: InterruptionDelegate

    companion object {
        private const val TAG = "InterruptionManager"
        private const val NETWORK_INTERRUPTION_TIMEOUT_MS = 30000L
        private const val SYSTEM_INTERRUPTION_TIMEOUT_MS = 30000L // 30s timeout for Camera/Phone
        private const val FOCUS_POLLING_INTERVAL_MS = 2000L // Poll every 2s
    }

    // Professional Stack-based storage
    private val interruptions = mutableListOf<Interruption>()
    private val lock = Any()
    
    private var interruptionTimerStartedAt: Long = 0L
    private var interruptionDeadlineMs: Long = 0L
    private val mainHandler = handler
    private var interruptionRunnable: Runnable? = null
    private var focusPollingRunnable: Runnable? = null
    
    // ... (rest of class)

    // ... inside handleInterruptionBeganInternal ...
    private fun handleInterruptionBeganInternal(source: InterruptionSource) {
        if (!::delegate.isInitialized) {
            Log.w(TAG, "Delegate not initialized; ignoring interruption began: $source")
            return
        }
        
        // Record start time if this is the first interruption in the stack
        if (interruptions.isEmpty()) {
            interruptionStartedAt = System.currentTimeMillis()
        }
        
        synchronized(lock) {
            val interruption = when (source) {
                InterruptionSource.PHONE_CALL -> PhoneCallInterruption()
                InterruptionSource.NETWORK -> NetworkInterruption()
                InterruptionSource.SYSTEM_RESOURCE -> SystemResourceInterruption()
                else -> return
            }

            // Check for duplicates
            if (interruptions.none { it.source == source }) {
                interruptions.add(interruption)
                Log.i(TAG, "⏸️ Added $interruption - Stack: ${interruptions.map { it.source }}")
                
                // Start polling if Phone or System interruption
                if (source == InterruptionSource.PHONE_CALL || source == InterruptionSource.SYSTEM_RESOURCE) {
                    startAudioFocusPolling()
                }
            } else {
                Log.d(TAG, "⏸️ $source already in stack - ignoring duplicate")
            }

            updateStateAndTimer()
        }
    }

    private fun handleInterruptionEndedInternal(source: InterruptionSource) {
        if (!::delegate.isInitialized) {
            Log.w(TAG, "Delegate not initialized; ignoring interruption ended: $source")
            return
        }
        synchronized(lock) {
            val removed = interruptions.removeIf { it.source == source }
            if (removed) {
                Log.i(TAG, "⏸️ Removed $source - Stack: ${interruptions.map { it.source }}")
                // Stop polling if no more Phone/System interruptions
                if (interruptions.none { it.source == InterruptionSource.PHONE_CALL || it.source == InterruptionSource.SYSTEM_RESOURCE }) {
                    stopAudioFocusPolling()
                }
            } else {
                Log.w(TAG, "⏸️ Attempted to remove $source but it was not in stack")
            }

            updateStateAndTimer()
        }
    }
    
    // ... (rest of methods) ...

    fun reset() {
        Log.i(TAG, "Resetting InterruptionManager")
        synchronized(lock) {
            interruptions.clear()
            cancelInterruptionTimeout() // This removes callbacks and clears vars
            stopAudioFocusPolling()
            context.currentInterruptionSource = InterruptionSource.NONE
            context.reconnectionSource = InterruptionSource.NONE
            context.isExpectingSafetyDisconnect = false
        }
    }

    // ...

    private fun startInterruptionTimeout(timeoutMs: Long) {
        // If timer already running, check if we need to update it?
        // Logic: If timer is running, and we switch source, do we reset?
        // iOS Logic: "Interruption already in progress - preserving deadline"
        
        if (interruptionTimerStartedAt != 0L && interruptionDeadlineMs != 0L) {
            Log.d(TAG, "⏳ Timer already running - preserving deadline")
            return
        }
        
        cancelInterruptionTimeout() // Safety clear
        
        val now = System.currentTimeMillis()
        interruptionTimerStartedAt = now
        interruptionDeadlineMs = now + timeoutMs
        
        val remainingMs = interruptionDeadlineMs - now
        
        interruptionRunnable = Runnable {
            synchronized(lock) {
                Log.w(TAG, "❌ Interruption timeout expired (source=${context.currentInterruptionSource})")
                
                context.lastError = "Stream stopped due to prolonged interruption"
                delegate.transitionTo(StreamEvent.TimeoutExpired)  
                
                interruptions.clear()
                cancelInterruptionTimeout()
                stopAudioFocusPolling()
                context.currentInterruptionSource = InterruptionSource.NONE
                context.reconnectionSource = InterruptionSource.NONE
            }
        }
        
        Log.i(TAG, "⏳ Timer started: calculated remaining=${remainingMs}ms (requested timeout=${timeoutMs}ms)")
        mainHandler.postDelayed(interruptionRunnable!!, remainingMs.coerceAtLeast(0L))
    }
    
    // Polling Logic
    private fun startAudioFocusPolling() {
        if (focusPollingRunnable != null) return

        Log.i(TAG, "🔊 Starting audio focus polling (every ${FOCUS_POLLING_INTERVAL_MS}ms)")
        focusPollingRunnable = object : Runnable {
            override fun run() {
                synchronized(lock) {
                    // Check if we still have Phone/System interruption
                    val needsFocus = interruptions.any { it.source == InterruptionSource.PHONE_CALL || it.source == InterruptionSource.SYSTEM_RESOURCE }
                    if (!needsFocus) {
                        stopAudioFocusPolling()
                        return
                    }
                    
                    try {
                        Log.d(TAG, "🔊 Polling: checking audio focus...")
                        if (delegate.tryAcquireAudioFocus()) {
                            Log.i(TAG, "🔊 Polling: Audio focus acquired! Recovering...")
                            handlePhoneInterruptionEnded() // Handles simple case, assuming Phone/Focus is the reason
                            // If SystemResource was the reason, we might need handleSystemInterruptionEnded()
                            // But usually focus logic maps to PhoneInterruption APIs here.
                            // If both are present, handlePhoneInterruptionEnded only removes PHONE_CALL. 
                            // SystemResource might need separate handling if it doesn't auto-clear.
                            // However, Focus Gain usually implies we can resume audio.
                            
                            // Also clear SystemResource if focus is regained?
                            if (interruptions.any { it.source == InterruptionSource.SYSTEM_RESOURCE }) {
                                handleSystemInterruptionEnded()
                            }
                            
                            stopAudioFocusPolling()
                        } else {
                            // Schedule next poll
                            mainHandler.postDelayed(this, FOCUS_POLLING_INTERVAL_MS)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in focus polling: ${e.message}")
                        stopAudioFocusPolling()
                    }
                }
            }
        }
        mainHandler.postDelayed(focusPollingRunnable!!, FOCUS_POLLING_INTERVAL_MS)
    }

    private fun stopAudioFocusPolling() {
        if (focusPollingRunnable != null) {
            Log.d(TAG, "🔊 Stopping audio focus polling")
            mainHandler.removeCallbacks(focusPollingRunnable!!)
            focusPollingRunnable = null
        }
    }
    
    // Track actual interruption start time for accurate countdown
    private var interruptionStartedAt: Long = 0L

    // Phone Call Interruption Handlers
    fun handlePhoneInterruptionBegan() {
        Log.i(TAG, "📱 Phone Call Interruption Detected")
        handleInterruptionBeganInternal(InterruptionSource.PHONE_CALL)
    }

    fun handlePhoneInterruptionEnded() {
        Log.i(TAG, "📱 Phone Call Interruption Ended")
        handleInterruptionEndedInternal(InterruptionSource.PHONE_CALL)
    }

    // Network Interruption Handlers
    fun handleNetworkLost() {
        Log.i(TAG, "🌐 Network Lost Detected")
        handleInterruptionBeganInternal(InterruptionSource.NETWORK)
    }

    fun handleNetworkAvailable() {
        Log.i(TAG, "🌐 Network Available")
        // Delay logic for network stability is now handled in end logic or can be kept here
        // For consistency with iOS, we process the end immediately but we can keep the stabilization
        // if strictly required. However, for stack logic, we just remove the network interruption.
        
        // Stabilization Logic (Optional but good for Android)
        mainHandler.postDelayed({
             handleInterruptionEndedInternal(InterruptionSource.NETWORK)
        }, 1000)
    }
    
    // System Resource Interruption Handlers
    fun handleSystemInterruptionBegan() {
        Log.i(TAG, "⚠️ System Resource Interruption Detected")
        handleInterruptionBeganInternal(InterruptionSource.SYSTEM_RESOURCE)
    }
    
    fun handleSystemInterruptionEnded() {
        Log.i(TAG, "⚠️ System Resource Interruption Ended")
        handleInterruptionEndedInternal(InterruptionSource.SYSTEM_RESOURCE)
    }


    private fun updateStateAndTimer() {
        // 1. Determine Effective Source (Priority Logic)
        val effectiveSource = when {
            interruptions.any { it.source == InterruptionSource.PHONE_CALL } -> InterruptionSource.PHONE_CALL
            interruptions.any { it.source == InterruptionSource.SYSTEM_RESOURCE } -> InterruptionSource.SYSTEM_RESOURCE
            interruptions.any { it.source == InterruptionSource.NETWORK } -> InterruptionSource.NETWORK
            else -> InterruptionSource.NONE
        }
        
        Log.i(TAG, "🔄 UpdateState: Effective Source = $effectiveSource")
        
        // 2. Update Context
        if (effectiveSource == InterruptionSource.NONE && context.currentInterruptionSource != InterruptionSource.NONE) {
             // We are clearing the interruption, save it for the event mapper
             context.reconnectionSource = context.currentInterruptionSource
        }
        context.currentInterruptionSource = effectiveSource


        // 3. Handle Transitions
        if (effectiveSource != InterruptionSource.NONE) {
            if (delegate.getStreamState() != StreamState.INTERRUPTED) {
                try {
                    // CRITICAL FIX: Set safety flag BEFORE any operations that could trigger disconnect
                    // This prevents race conditions where disconnect callback fires before state changes
                    context.isExpectingSafetyDisconnect = true
                    Log.d(TAG, "Set isExpectingSafetyDisconnect=true BEFORE interruption handling")
                    
                    val transitioned = delegate.transitionTo(StreamEvent.InterruptionBegan)
                    if (transitioned) {
                        try {
                            delegate.stopStreamForInterruption()
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error stopping stream for interruption: ${e.message}")
                        }
                        // Do NOT abandon audio focus here. We need it to detect when the interruption (e.g. Music) ends.
                        // delegate.abandonAudioFocus()
                        
                        // Event is sent by StreamStateMachine -> FlutterEventMapper upon state transition
                    }
                    
                    // Reset flag after a delay to catch any async disconnect callbacks
                    mainHandler.postDelayed({
                        context.isExpectingSafetyDisconnect = false
                        Log.d(TAG, "Reset isExpectingSafetyDisconnect=false after delay in InterruptionManager")
                    }, 500)
                } catch (e: Throwable) {
                    context.isExpectingSafetyDisconnect = false
                    Log.e(TAG, "Error handling interruption began: ${e.message}")
                }
            }
        } else {
            // No interruptions -> Resume
            if (delegate.getStreamState() == StreamState.INTERRUPTED) {
                Log.i(TAG, "✅ All interruptions cleared - Resuming")
                cancelInterruptionTimeout()
                
                // Always try to reconnect, even in background (Foreground Service handles lifecycle)
                delegate.reconnectStream()
            }
        }

        // 4. Timer Logic
        updateTimer(effectiveSource)
    }
    
    // Removed sendInterruptionEvent to prevent duplicate events (handled by FlutterEventMapper)

    private fun updateTimer(source: InterruptionSource) {
        when (source) {
            InterruptionSource.NONE -> {
                cancelInterruptionTimeout()
            }
            InterruptionSource.PHONE_CALL, InterruptionSource.SYSTEM_RESOURCE -> {
                 // 30s Timeout (As requested by user: "check if we are using same logic... wait 30s")
                 // iOS uses 30s for Phone/System now.
                 startInterruptionTimeout(SYSTEM_INTERRUPTION_TIMEOUT_MS)
            }
            InterruptionSource.NETWORK -> {
                 // 30s Timeout
                 startInterruptionTimeout(NETWORK_INTERRUPTION_TIMEOUT_MS)
            }
        }
    }



    private fun cancelInterruptionTimeout() {
        if (interruptionRunnable != null) {
            mainHandler.removeCallbacks(interruptionRunnable!!)
            interruptionRunnable = null
        }
        interruptionTimerStartedAt = 0L
        interruptionDeadlineMs = 0L
    }
    
    // Helper for UI
    fun getRemainingInterruptionSeconds(): Int {
        if (interruptionStartedAt == 0L) return 30 // Default start
        val elapsed = System.currentTimeMillis() - interruptionStartedAt
        val totalTimeoutMs = 30000L
        return ((totalTimeoutMs - elapsed) / 1000).toInt().coerceIn(0, 30)
    }

    // Deprecated / Removed old boolean flags fields
    // @Volatile var isNetworkLost = false -> replaced by stack
    // @Volatile var isPhoneCallActive = false -> replaced by stack
    
    fun handleResumeFromInterruption(isCallActive: Boolean) {
        // Fix for Camera/External App Interruption
        // If we are interrupted by "PhoneCall" (which includes Audio Focus loss)
        // but there is no actual GSM call, and we just resumed, try to resume streaming.
        
        synchronized(lock) {
            val isPhoneInterrupted = interruptions.any { it.source == InterruptionSource.PHONE_CALL }
            
            if (delegate.getStreamState() == StreamState.INTERRUPTED && isPhoneInterrupted) {
                 if (!isCallActive) {
                     Log.i(TAG, "Resumed while interrupted by Phone/Focus - Call not active - ending Phone interruption")
                     // Remove Phone Call from stack
                     handlePhoneInterruptionEnded()
                     return
                 }
            }
            
            // Also check for System Resource
            val isSystemInterrupted = interruptions.any { it.source == InterruptionSource.SYSTEM_RESOURCE }
            if (delegate.getStreamState() == StreamState.INTERRUPTED && isSystemInterrupted) {
                Log.i(TAG, "Resumed while interrupted by System Resource - ending System interruption")
                handleSystemInterruptionEnded()
                return
            }
        }

        if (context.pendingReconnectOnResume) {
            Log.d(TAG, "Resumed with pending reconnect")
            context.pendingReconnectOnResume = false
            delegate.reconnectStream()
        }
    }


}
