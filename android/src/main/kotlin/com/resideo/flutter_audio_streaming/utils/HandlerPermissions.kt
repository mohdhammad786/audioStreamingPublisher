package com.resideo.flutter_audio_streaming.utils

import android.Manifest.permission
import android.app.Activity
import android.util.Log
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener


class HandlerPermissions {
    interface PermissionStuff {
        fun adddListener(listener: RequestPermissionsResultListener);
    }

    interface ResultCallback {
        fun onResult(errorCode: String?, errorDescription: String?)
    }

    private var ongoing = false

    fun requestPermissions(
        activity: Activity,
        permissionsRegistry: PermissionStuff,
        callback: ResultCallback
    ) {
        if (ongoing) {
            callback.onResult("permissionRequest", "Permission request ongoing")
            return
        }
        
        ongoing = true
        
        // Register listener once
        permissionsRegistry.adddListener(
            RequestPermissionsListener(
                object : ResultCallback {
                    override fun onResult(errorCode: String?, errorDescription: String?) {
                        if (errorCode == null) {
                            // Permission granted, proceed to next
                            requestNext(activity, callback)
                        } else {
                            // Permission denied, stop sequence
                            ongoing = false
                            callback.onResult(errorCode, errorDescription)
                        }
                    }
                })
        )
        
        // Start sequence
        requestNext(activity, callback)
    }

    private fun requestNext(activity: Activity, callback: ResultCallback) {
        val permissionToRequest: String? = when {
            !hasAudioPermission(activity) -> permission.RECORD_AUDIO
            !hasPhoneStatePermission(activity) -> permission.READ_PHONE_STATE
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(activity) -> permission.POST_NOTIFICATIONS
            else -> null
        }

        if (permissionToRequest != null) {
            Log.i("HandlerPermissions", "Requesting sequential permission: $permissionToRequest")
            ActivityCompat.requestPermissions(activity, arrayOf(permissionToRequest), AUDIO_REQUEST_ID)
        } else {
            // All done
            Log.i("HandlerPermissions", "All permissions granted!")
            ongoing = false
            callback.onResult(null, null)
        }
    }
    
    // Checkers
    private fun hasAudioPermission(activity: Activity): Boolean {
        return (ContextCompat.checkSelfPermission(activity, permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED)
    }

    private fun hasPhoneStatePermission(activity: Activity): Boolean {
        return (ContextCompat.checkSelfPermission(activity, permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED)
    }
    
    private fun hasNotificationPermission(activity: Activity): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            (ContextCompat.checkSelfPermission(activity, permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED)
        } else {
            true
        }
    }

    private fun hasWriteExternalStoragePermission(activity: Activity): Boolean {
        return (ContextCompat.checkSelfPermission(activity, permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED)
    }

    private fun hasWakeLockPermission(activity: Activity): Boolean = true


    @VisibleForTesting
    internal class RequestPermissionsListener @VisibleForTesting constructor(val callback: ResultCallback) :
        RequestPermissionsResultListener {
        
        // Note: In sequential mode, we do NOT want "alreadyCalled" to block subsequent calls 
        // because we might request permissions multiple times (Audio, then Phone).
        // However, standard Listener registry in Flutter might be one-shot for the "addRequestPermissionsResultListener"?
        // Actually, the binding keeps the listener.
        // But we re-use the same listener instance for the whole sequence.
        
        override fun onRequestPermissionsResult(
            id: Int,
            permissions: Array<String>,
            grantResults: IntArray
        ): Boolean {
            Log.d("HandlerPermissions", "onRequestPermissionsResult: id=$id, permissions=${permissions.contentToString()}, results=${grantResults.contentToString()}")
            
            if (id != AUDIO_REQUEST_ID) {
                return false
            }
            
            if (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                // Find which permission was denied for better error message
                val deniedPermissions = permissions.filterIndexed { index, _ -> grantResults[index] != PackageManager.PERMISSION_GRANTED }
                Log.w("HandlerPermissions", "Permissions denied: $deniedPermissions")
                
                // If denied, we fail the whole sequence
                val sb = StringBuilder("Permissions not granted: ")
                for (perm in deniedPermissions) {
                    sb.append(perm).append(" ")
                }
                callback.onResult("permissionError", sb.toString())
            } else {
                Log.i("HandlerPermissions", "Permission granted, moving to next step...")
                callback.onResult(null, null)
            }
            return true
        }

    }

    companion object {
        private const val AUDIO_REQUEST_ID = 9123
    }
}
