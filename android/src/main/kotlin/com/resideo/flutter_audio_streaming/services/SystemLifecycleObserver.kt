package com.resideo.flutter_audio_streaming.services

import android.app.Activity
import android.app.Application
import android.os.Bundle

interface LifecycleEventListener {
    fun onActivityResumed(activity: Activity)
    fun onActivityPaused(activity: Activity)
    fun onActivityStarted(activity: Activity)
    fun onActivityStopped(activity: Activity)
    fun onActivityDestroyed(activity: Activity)
}

class SystemLifecycleObserver : Application.ActivityLifecycleCallbacks {

    lateinit var listener: LifecycleEventListener


    override fun onActivityResumed(activity: Activity) = listener.onActivityResumed(activity)
    override fun onActivityPaused(activity: Activity) = listener.onActivityPaused(activity)
    override fun onActivityStarted(activity: Activity) = listener.onActivityStarted(activity)
    override fun onActivityStopped(activity: Activity) = listener.onActivityStopped(activity)
    override fun onActivityDestroyed(activity: Activity) = listener.onActivityDestroyed(activity)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
