package com.wax.module.modern

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast

/** Shared, single-flight fallback used by the menu hook and the embedded shell. */
object ModernManagerFallback {
    private const val TAG = "WA-X MenuHome102"
    private const val MANAGER_PACKAGE = "com.wax.module"
    private const val MANAGER_ACTIVITY = "com.wax.module.activities.MainActivity"
    private val gate = ActivityFallbackGate()

    @JvmStatic
    fun open(activity: Activity): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "M06_MANAGER_FALLBACK_SKIPPED reason=ACTIVITY_UNAVAILABLE")
            return false
        }
        if (!gate.tryBegin(activity)) {
            Log.i(TAG, "M06_MANAGER_FALLBACK_SKIPPED reason=ALREADY_REQUESTED")
            return false
        }

        val application = activity.application
        var callbackRegistered = false
        var hostPaused = false
        lateinit var callback: Application.ActivityLifecycleCallbacks
        fun unregisterCallback() {
            if (!callbackRegistered) return
            callbackRegistered = false
            try {
                application.unregisterActivityLifecycleCallbacks(callback)
            } catch (_: RuntimeException) {
                Log.w(TAG, "M06_MANAGER_FALLBACK_CALLBACK_REMOVE_FAILED")
            }
        }
        fun hostReturnedOrEnded(candidate: Activity) {
            if (candidate !== activity) return
            gate.release(activity)
            unregisterCallback()
        }
        callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(candidate: Activity, state: Bundle?) {}
            override fun onActivityStarted(candidate: Activity) {}
            override fun onActivityResumed(candidate: Activity) {
                if (candidate === activity && hostPaused) hostReturnedOrEnded(candidate)
            }
            override fun onActivityPaused(candidate: Activity) {
                if (candidate === activity) hostPaused = true
            }
            override fun onActivityStopped(candidate: Activity) = hostReturnedOrEnded(candidate)
            override fun onActivitySaveInstanceState(candidate: Activity, state: Bundle) {}
            override fun onActivityDestroyed(candidate: Activity) = hostReturnedOrEnded(candidate)
        }

        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(MANAGER_PACKAGE, MANAGER_ACTIVITY)
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return try {
            application.registerActivityLifecycleCallbacks(callback)
            callbackRegistered = true
            activity.startActivity(intent)
            gate.markOpened(activity)
            Log.i(TAG, "M06_MENU_HOME_MANAGER_OPENED")
            true
        } catch (failure: RuntimeException) {
            unregisterCallback()
            gate.allowRetry(activity)
            val reason = ControlCenterFailureReason.fromClassName(failure.javaClass.name)
            Log.w(TAG,
                "M06_MANAGER_FALLBACK_FAILED reason=$reason exception=${failure.javaClass.simpleName}")
            try {
                Toast.makeText(activity, "WA X Manager could not be opened", Toast.LENGTH_SHORT).show()
            } catch (_: RuntimeException) {
                // A failed fallback must not affect the host WhatsApp process.
            }
            false
        }
    }
}
