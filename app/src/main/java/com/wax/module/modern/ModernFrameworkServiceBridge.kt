package com.wax.module.modern

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Manager-side counterpart of ModernXposedEntry.
 *
 * This connection does NOT claim WhatsApp injection or feature readiness. Those require a
 * target-authenticated heartbeat; an Xposed framework connection alone is insufficient.
 * No XSharedPreferences or legacy self-hook signal is used for modern preferences.
 */
object ModernFrameworkServiceBridge {
    const val PREFERENCES_GROUP = "wax.runtime.v1"
    private const val TAG = "WA-X ModernService"
    private const val MIN_API_VERSION = 102

    data class Snapshot(
        val connected: Boolean,
        val apiVersion: Int?,
        val frameworkName: String?,
        val frameworkVersion: String?,
        val error: String?,
    )

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var snapshot = Snapshot(false, null, null, null, "NOT_CONNECTED")

    @Volatile
    private var registered = false

    private val listener =
        object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(value: XposedService) {
                try {
                    if (value.apiVersion < MIN_API_VERSION) {
                        service = null
                        snapshot = Snapshot(false, value.apiVersion, value.frameworkName, value.frameworkVersion, "API_UNSUPPORTED")
                        Log.w(TAG, "Framework API is lower than required API 102")
                        return
                    }
                    // A working remote preference group is part of the framework handshake.
                    value.getRemotePreferences(PREFERENCES_GROUP)
                    service = value
                    snapshot = Snapshot(true, value.apiVersion, value.frameworkName, value.frameworkVersion, null)
                    Log.i(TAG, "Modern framework connected: API ${value.apiVersion}")
                } catch (e: RuntimeException) {
                    service = null
                    snapshot = Snapshot(false, null, null, null, "SERVICE_HANDSHAKE_FAILED")
                    Log.w(TAG, "Modern framework handshake failed", e)
                }
            }

            override fun onServiceDied(value: XposedService) {
                if (service === value) {
                    service = null
                    snapshot = Snapshot(false, null, null, null, "SERVICE_DISCONNECTED")
                    Log.w(TAG, "Modern framework service disconnected")
                }
            }
        }

    @Synchronized
    fun register() {
        if (registered) return
        try {
            XposedServiceHelper.registerListener(listener)
            registered = true
        } catch (e: RuntimeException) {
            service = null
            snapshot = Snapshot(false, null, null, null, "LISTENER_REGISTRATION_FAILED")
            Log.w(TAG, "Framework service listener registration failed", e)
        } catch (e: LinkageError) {
            service = null
            snapshot = Snapshot(false, null, null, null, "SERVICE_API_UNAVAILABLE")
            Log.w(TAG, "Modern framework service API is not available", e)
        }
    }

    fun currentState(): Snapshot = snapshot

    /** Null if modern service is unavailable. Never return local/legacy preferences as a substitute. */
    fun remotePreferences(): SharedPreferences? =
        try {
            service?.getRemotePreferences(PREFERENCES_GROUP)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to read remote framework preferences", e)
            null
        }
}
