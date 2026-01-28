package com.resideo.flutter_audio_streaming

import android.app.Activity
import android.util.Log
import androidx.annotation.NonNull

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel
import com.resideo.flutter_audio_streaming.utils.HandlerPermissions
import com.resideo.flutter_audio_streaming.utils.DartMessenger
import com.resideo.flutter_audio_streaming.core.MethodCallHandlerImpl
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.PluginRegistry

/** FlutterAudioStreamingPlugin */
public class FlutterAudioStreamingPlugin : FlutterPlugin, ActivityAware {

    /// The MethodChannel that will the˙ communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private var methodCallHandler: MethodCallHandlerImpl? = null
    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.v(TAG, "onAttachedToEngine $flutterPluginBinding")
        this.flutterPluginBinding = flutterPluginBinding
        methodCallHandler = MethodCallHandlerImpl(
            flutterPluginBinding.binaryMessenger,
            HandlerPermissions()
        )
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        Log.v(TAG, "onDetachedFromEngine $binding")
        methodCallHandler?.stopListening()
        methodCallHandler = null
        flutterPluginBinding = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Log.v(TAG, "onAttachedToActivity $binding")
        methodCallHandler?.setActivity(binding.activity)
        methodCallHandler?.setPermissionsRegistry(object : HandlerPermissions.PermissionStuff {
             override fun adddListener(listener: PluginRegistry.RequestPermissionsResultListener) {
                 binding.addRequestPermissionsResultListener(listener);
             }
        })
    }

    override fun onDetachedFromActivity() {
        Log.v(TAG, "onDetachedFromActivity")
        methodCallHandler?.setActivity(null)
        methodCallHandler?.setPermissionsRegistry(null)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    companion object {
        const val TAG = "AudioStreamingPlugin"
    }
}
