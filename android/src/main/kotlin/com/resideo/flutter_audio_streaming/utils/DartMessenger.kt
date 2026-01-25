package com.resideo.flutter_audio_streaming.utils

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import java.util.*

class DartMessenger(messenger: BinaryMessenger, id: String) {
    private var eventSink: EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class EventType {
        ERROR, CAMERA_CLOSING, RTMP_STOPPED, RTMP_STARTED, ROTATION_UPDATE,
        AUDIO_INTERRUPTED, AUDIO_RESUMED,
        NETWORK_INTERRUPTED, NETWORK_RESUMED
    }

    fun send(eventType: EventType, description: String?) {
        val event: MutableMap<String, String?> = HashMap()
        event["eventType"] = eventType.toString().lowercase(Locale.ROOT)
        if (!TextUtils.isEmpty(description)) {
            event["errorDescription"] = description
        }
        val sink = eventSink ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sink.success(event)
            return
        }
        mainHandler.post {
            eventSink?.success(event)
        }
    }
    
    fun send(eventType: EventType, description: String?, extras: Map<String, Any?>?) {
        val event: MutableMap<String, Any?> = HashMap()
        event["eventType"] = eventType.toString().lowercase(Locale.ROOT)
        if (!TextUtils.isEmpty(description)) {
            event["errorDescription"] = description
        }
        if (extras != null) {
            for ((k, v) in extras) {
                event[k] = v
            }
        }
        val sink = eventSink ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sink.success(event)
            return
        }
        mainHandler.post {
            eventSink?.success(event)
        }
    }

    init {
        EventChannel(messenger, "plugins.flutter.io/flutter_audio_streaming/$id")
            .setStreamHandler(
                object : EventChannel.StreamHandler {
                    override fun onListen(arguments: Any?, sink: EventSink) {
                        eventSink = sink
                    }

                    override fun onCancel(arguments: Any?) {
                        eventSink = null
                    }
                }
            )
    }
}
