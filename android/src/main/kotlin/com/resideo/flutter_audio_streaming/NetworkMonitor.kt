package com.resideo.flutter_audio_streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Monitors network connectivity to handle stream interruptions due to network loss.
 * Follows the same pattern as PhoneCallManager.
 */
class NetworkMonitor(
    private val context: Context,
    private val onNetworkLost: () -> Unit,
    private val onNetworkAvailable: () -> Unit
) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMonitoring = false

    // Debouncing: Track last event time to avoid spurious rapid changes
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var lastNetworkState: Boolean? = null
    private var pendingNetworkEvent: Runnable? = null

    companion object {
        private const val TAG = "NetworkMonitor"
        private const val DEBOUNCE_DELAY_MS = 300L  // Reduced from 500ms for faster response
    }

    fun startMonitoring() {
        if (isMonitoring || connectivityManager == null) {
            if (connectivityManager == null) {
                Log.e(TAG, "ConnectivityManager not available")
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerNetworkCallback()
        } else {
            Log.w(TAG, "Network monitoring requires API 24+. Falling back to reactive detection.")
            // For older APIs, we'll rely on RTSP failure detection only
            return
        }

        isMonitoring = true
        Log.d(TAG, "Network monitoring started")
    }

    fun stopMonitoring() {
        if (!isMonitoring || connectivityManager == null) return

        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
            networkCallback = null
            isMonitoring = false

            // Cancel any pending debounced events
            pendingNetworkEvent?.let { debounceHandler.removeCallbacks(it) }
            pendingNetworkEvent = null
            lastNetworkState = null

            Log.d(TAG, "Network monitoring stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop network monitoring: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // Removed NET_CAPABILITY_VALIDATED - too strict, prevents callbacks during transitions
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                handleNetworkChange(isAvailable = true)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                handleNetworkChange(isAvailable = false)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                Log.d(TAG, "Network capabilities changed - Internet: $hasInternet, Validated: $isValidated")

                // Only check for internet capability, validation is optional
                handleNetworkChange(isAvailable = hasInternet)
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)

            // Don't set lastNetworkState on initialization - this allows first transition to be detected
            // lastNetworkState remains null initially
            val initialState = isNetworkAvailable()
            Log.i(TAG, "Initial network state: ${if (initialState) "Available" else "Unavailable"} (not tracking yet)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun handleNetworkChange(isAvailable: Boolean) {
        // Cancel any pending event
        pendingNetworkEvent?.let { debounceHandler.removeCallbacks(it) }

        // Check if state actually changed
        if (lastNetworkState == isAvailable) {
            Log.d(TAG, "Network state unchanged ($isAvailable), ignoring")
            return
        }

        // Debounce the event to avoid rapid fire changes
        val event = Runnable {
            lastNetworkState = isAvailable

            if (isAvailable) {
                Log.i(TAG, "Network became available (debounced)")
                onNetworkAvailable()
            } else {
                Log.i(TAG, "Network became unavailable (debounced)")
                onNetworkLost()
            }

            pendingNetworkEvent = null
        }

        pendingNetworkEvent = event
        debounceHandler.postDelayed(event, DEBOUNCE_DELAY_MS)
    }

    fun isNetworkAvailable(): Boolean {
        if (connectivityManager == null) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            networkInfo?.isConnected == true
        }
    }
}
