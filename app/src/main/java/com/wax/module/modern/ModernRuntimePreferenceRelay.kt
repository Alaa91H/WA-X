package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.util.concurrent.Executors

/**
 * Non-destructive, opt-in bridge from WA X Manager settings into the API102
 * framework-owned RemotePreferences scoped to com.wax.module.
 *
 * Mirrors ONLY the migrated CustomTime pilot keys. Never deletes settings,
 * copies contacts/chats, or implies that any legacy feature is running.
 */
object ModernRuntimePreferenceRelay {
    const val ENABLE_KEY = "modern.feature.custom_time.enabled"
    private const val TAG = "WA-X ModernPrefs"
    private val observedKeys = setOf(ENABLE_KEY, "segundos", "ampm", "text_in_hour", "removeforwardlimit", "freezelastseen", "dndmode")
    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "wax-api102-settings-relay").apply { isDaemon = true }
        }

    @Volatile
    private var local: SharedPreferences? = null
    private val changes =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in observedKeys) requestSync()
        }

    @Synchronized
    fun start(context: Context) {
        if (local != null) return
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        local = preferences
        preferences.registerOnSharedPreferenceChangeListener(changes)
        ModernFrameworkServiceBridge.setOnConnectedListener { requestSync() }
        requestSync()
    }

    fun requestSync() {
        worker.execute {
            val source = local ?: return@execute
            val remote = ModernFrameworkServiceBridge.remotePreferences() ?: return@execute
            try {
                val values =
                    ModernCustomTimeSettingPolicy.from(
                        enabled = source.getBoolean(ENABLE_KEY, false),
                        seconds = source.getBoolean("segundos", false),
                        amPm = source.getBoolean("ampm", false),
                        template = source.getString("text_in_hour", "[TIME]"),
                    )
                // Never clear remote preferences: other target-specific/runtime keys live there.
                remote.edit {
                    putBoolean(ENABLE_KEY, values.enabled)
                    putBoolean("segundos", values.seconds)
                    putBoolean("ampm", values.amPm)
                    putString("text_in_hour", values.template)
                    // Preserve the user-selected Legacy switch for the migrated API102 feature.
                    putBoolean("removeforwardlimit", source.getBoolean("removeforwardlimit", false))
                    putBoolean("freezelastseen", source.getBoolean("freezelastseen", false))
                    putBoolean("dndmode", source.getBoolean("dndmode", false))
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not relay opted-in modern preference values", error)
            }
        }
    }
}

/** Pure policy so the migrated feature can be tested without Android or rooted devices. */
data class ModernCustomTimeSettingPolicy(
    val enabled: Boolean,
    val seconds: Boolean,
    val amPm: Boolean,
    val template: String,
) {
    companion object {
        fun from(
            enabled: Boolean,
            seconds: Boolean,
            amPm: Boolean,
            template: String?,
        ): ModernCustomTimeSettingPolicy =
            ModernCustomTimeSettingPolicy(
                enabled = enabled,
                seconds = seconds,
                amPm = amPm,
                template = template?.takeIf { it.isNotEmpty() } ?: "[TIME]",
            )
    }
}
