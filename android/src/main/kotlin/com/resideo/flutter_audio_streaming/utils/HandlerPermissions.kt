package com.resideo.flutter_audio_streaming.utils

import android.Manifest.permission
import android.app.Activity
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
            callback.onResult("audioPermission", "Audio permission request ongoing")
        }
        val needsAudio = !hasAudioPermission(activity)
        val needsWrite = !hasWriteExternalStoragePermission(activity)
        if (needsAudio || needsWrite) {
            permissionsRegistry.adddListener(
                RequestPermissionsListener(
                    object : ResultCallback {
                        override fun onResult(errorCode: String?, errorDescription: String?) {
                            ongoing = false
                            callback.onResult(errorCode, errorDescription)
                        }
                    })
            )
            ongoing = true
            val toRequest = mutableListOf<String>()
            if (needsAudio) toRequest.add(permission.RECORD_AUDIO)
            if (needsWrite) toRequest.add(permission.WRITE_EXTERNAL_STORAGE)
            ActivityCompat.requestPermissions(activity, toRequest.toTypedArray(), AUDIO_REQUEST_ID)
        } else {
            // Permissions already exist. Call the callback with success.
            callback.onResult(null, null)
        }
    }

    private fun hasAudioPermission(activity: Activity): Boolean {
        return (ContextCompat.checkSelfPermission(activity, permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED)
    }

    private fun hasWriteExternalStoragePermission(activity: Activity): Boolean {
        return (ContextCompat.checkSelfPermission(activity, permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED)
    }

    private fun hasWakeLockPermission(activity: Activity): Boolean = true


    @VisibleForTesting
    internal class RequestPermissionsListener @VisibleForTesting constructor(val callback: ResultCallback) :
        RequestPermissionsResultListener {
        // There's no way to unregister permission listeners in the v1 embedding, so we'll be called
        // duplicate times in cases where the user denies and then grants a permission. Keep track of if
        // we've responded before and bail out of handling the callback manually if this is a repeat
        // call.
        var alreadyCalled = false
        override fun onRequestPermissionsResult(
            id: Int,
            permissions: Array<String>,
            grantResults: IntArray
        ): Boolean {
            if (alreadyCalled || id != AUDIO_REQUEST_ID) {
                return false
            }
            alreadyCalled = true
            if (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                callback.onResult("audioPermission", "MediaRecorderAudio permission not granted")
            } else {
                callback.onResult(null, null)
            }
            return true
        }

    }

    companion object {
        private const val AUDIO_REQUEST_ID = 9123
    }
}
