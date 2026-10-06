package com.wax.module.settings

import android.content.SharedPreferences
import com.wax.module.platform.TargetApp
import com.wax.module.xposed.core.components.SharedPreferencesWrapper
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge

/**
 * Connects the settings store to the preference object features read.
 *
 * One call site, [install], runs once per hooked process. Everything else about
 * per-target configuration is decided in [TargetScopedPreferences].
 */
object TargetSettingsBridge {
    /**
     * Wraps the hooked process's preferences for [target].
     *
     * Idempotent on purpose: the entry point attaches once per process, but a WhatsApp
     * process can be entered more than once and a second wrapper around a wrapper would
     * resolve the same key twice.
     */
    @JvmStatic
    fun install(
        delegate: SharedPreferences,
        target: TargetApp?,
    ): SharedPreferences {
        if (target == null) return delegate
        if (delegate is TargetScopedPreferences) return delegate

        val store = SharedPreferencesSettingsStore(delegate)
        val scoped = TargetScopedPreferences.wrap(delegate, target)
        if (scoped is TargetScopedPreferences) {
            scoped.refresh(store)
            Utils.xprefs = scoped
            // The same values are also pushed into WhatsApp's own preference reads, so a
            // feature that reads through the hooked SharedPreferences rather than through
            // Utils.xprefs gets the same answer. Without this, two features reading the
            // same setting could disagree.
            SharedPreferencesWrapper.addHook { key, value -> scoped.overrideForHook(key, value) }
        }
        XposedBridge.log("WA X: target settings active for ${target.displayName}")
        return scoped
    }

    /**
     * Re-reads the overrides after the manager writes.
     *
     * Called from the module process on a preference change, so a change in the interface
     * reaches a running WhatsApp without a restart.
     */
    @JvmStatic
    fun reload() {
        val prefs = Utils.xprefs
        if (prefs is TargetScopedPreferences) {
            prefs.refresh(SharedPreferencesSettingsStore(prefs.rawDelegate))
        }
    }
}
