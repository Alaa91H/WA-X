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
 * Mirrors explicitly supported API102 feature keys. Never deletes settings,
 * copies contacts/chats, or implies that any legacy feature is running.
 */
object ModernRuntimePreferenceRelay {
    const val ENABLE_KEY = "modern.feature.custom_time.enabled"
    private const val TAG = "WA-X ModernPrefs"
    private val observedKeys =
        setOf(
            ENABLE_KEY,
            "segundos",
            "ampm",
            "text_in_hour",
            "removeforwardlimit",
            "freezelastseen",
            "dndmode",
            "tasker",
            "tasker_auth_token",
            "ghostmode",
            "ghostmode_t",
            "ghostmode_r",
            "typearchive",
            "viewonce",
            "hideread",
            "hideread_group",
            "hidereceipt",
            "hidereadafterreply",
            "antirevoke",
            "hidestatusview",
            "sendstatusseenonreply",
        )

    /** Checked by contract tests so new Control Center keys cannot be omitted from the relay. */
    internal fun observes(key: String): Boolean = key in observedKeys

    internal fun affectsControlCenter(key: String?): Boolean =
        key == null || key in observedKeys || key == ModernControlCenterCatalog.FAVORITES_KEY

    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "wax-api102-settings-relay").apply { isDaemon = true }
        }

    @Volatile
    private var local: SharedPreferences? = null

    @Volatile
    private var applicationContext: Context? = null

    private val changes =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in observedKeys) requestSync()
            if (affectsControlCenter(key)) notifyControlCenter()
        }

    private fun notifyControlCenter() {
        try {
            applicationContext?.contentResolver?.notifyChange(ModernTargetStateClient.STATES_URI, null)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not signal Control Center state change", error)
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (local != null) return
        applicationContext = context.applicationContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        // Old embedded switches accidentally wrote Booleans into ListPreference slots.
        // Repair the stored type before the Manager UI or the runtime can read those slots.
        repairLegacyModes(preferences)
        local = preferences
        preferences.registerOnSharedPreferenceChangeListener(changes)
        ModernFrameworkServiceBridge.setOnConnectedListener { requestSync() }
        requestSync()
    }

    /** Preserve real list selections while normalizing historical malformed booleans. */
    internal fun legacyMode(value: Any?): String =
        when (value) {
            "1", "2" -> value as String
            true -> "1"
            else -> "0"
        }

    private fun repairLegacyModes(preferences: SharedPreferences) {
        val original = preferences.all
        val editor = preferences.edit()
        var changed = false
        for (key in listOf("typearchive", "antirevoke")) {
            if (original[key] is Boolean) {
                editor.putString(key, legacyMode(original[key]))
                changed = true
            }
        }
        if (changed && !editor.commit()) {
            Log.w(TAG, "Could not repair malformed legacy list preference values")
        }
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
                    // Tasker automation: opt-in flag plus its auth token.
                    putBoolean("tasker", source.getBoolean("tasker", false))
                    putString("tasker_auth_token", source.getString("tasker_auth_token", "").orEmpty())
                    // Typing/recording privacy: the three global switches the
                    // migrated adapter reads. Per-contact rules stay in the
                    // Manager and are fetched per contact on demand.
                    putBoolean("ghostmode", source.getBoolean("ghostmode", false))
                    putBoolean("ghostmode_t", source.getBoolean("ghostmode_t", false))
                    putBoolean("ghostmode_r", source.getBoolean("ghostmode_r", false))
                    // Archived-chat hiding: the user's mode, not a boolean.
                    putString("typearchive", source.getString("typearchive", "0") ?: "0")
                    putBoolean("viewonce", source.getBoolean("viewonce", false))
                    // Single source of truth: Manager preferences also feed the in-WhatsApp
                    // privacy toggles. Do not drop a persisted setting at the API102 bridge.
                    putBoolean("hideread", source.getBoolean("hideread", false))
                    putBoolean("hideread_group", source.getBoolean("hideread_group", false))
                    putBoolean("hidereceipt", source.getBoolean("hidereceipt", false))
                    putBoolean("hidereadafterreply", source.getBoolean("hidereadafterreply", false))
                    putString("antirevoke", legacyMode(source.all["antirevoke"]))
                    putBoolean("hidestatusview", source.getBoolean("hidestatusview", false))
                    putBoolean("sendstatusseenonreply", source.getBoolean("sendstatusseenonreply", false))
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
