package com.resideo.flutter_audio_streaming.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.resideo.flutter_audio_streaming.interfaces.NetworkMonitorInterface
import com.resideo.flutter_audio_streaming.interfaces.StreamingMediator
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Monitors network connectivity to handle stream interruptions due to network loss.
 * Follows the same pattern as PhoneCallManager.
 */
class NetworkMonitor(
    private val context: Context
) : NetworkMonitorInterface {

    lateinit var mediator: StreamingMediator


    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMonitoring = false

    override val isNetworkAvailable: Boolean
        get() {
            if (connectivityManager == null) return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                networkInfo?.isConnected == true
            }
        }

    // Debouncing: Track last event time to avoid spurious rapid changes
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var lastNetworkState: Boolean? = null
    private var pendingNetworkEvent: Runnable? = null

    companion object {
        private const val TAG = "NetworkMonitor"
        private const val DEBOUNCE_DELAY_MS = 1500L  // Increased to 1.5s to filter transient wake-up glitches
    }

    override fun startMonitoring() {
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
            // For older APIs, we'll rely on RTMP failure detection only
            return
        }

        isMonitoring = true
        Log.d(TAG, "Network monitoring started")
    }

    override fun stopMonitoring() {
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
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available callback: $network")
                handleNetworkChange(isAvailable = true)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost callback: $network")
                handleNetworkChange(isAvailable = false)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                // We don't wait for VALIDATED capability here to ensure "immediate" events as requested
                Log.d(TAG, "Network capabilities changed - Internet: $hasInternet")
                handleNetworkChange(isAvailable = hasInternet)
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.i(TAG, "ConnectivityManager callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun handleNetworkChange(isAvailable: Boolean) {
        // Cancel any pending event
        pendingNetworkEvent?.let { debounceHandler.removeCallbacks(it) }

        // Check if state actually changed
        if (lastNetworkState == isAvailable) {
            return
        }

        // Debounce slightly to avoid rapid fire, but keep it very short (100ms)
        val event = Runnable {
            if (lastNetworkState == isAvailable) return@Runnable
            
            lastNetworkState = isAvailable

            if (isAvailable) {
                Log.i(TAG, "🌐 Network Available")
                if (::mediator.isInitialized) {
                    mediator.onNetworkAvailable()
                } else {
                    Log.w(TAG, "Mediator not initialized; ignoring network available")
                }
            } else {
                Log.i(TAG, "❌ Network Lost")
                if (::mediator.isInitialized) {
                    mediator.onNetworkLost()
                } else {
                    Log.w(TAG, "Mediator not initialized; ignoring network lost")
                }
            }

            pendingNetworkEvent = null
        }

        pendingNetworkEvent = event
        debounceHandler.postDelayed(event, DEBOUNCE_DELAY_MS)
    }

    // Unified isNetworkAvailable is now a property at the top
}
