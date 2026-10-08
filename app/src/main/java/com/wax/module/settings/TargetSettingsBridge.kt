package com.wax.module.settings

import android.content.SharedPreferences
import com.wax.module.platform.TargetApp
import com.wax.module.xposed.core.components.SharedPreferencesWrapper
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XSharedPreferences
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
     * The settings snapshot for [target], resolved through the same resolver the Manager uses.
     *
     * Built here rather than in the contract's context factory because the snapshot has to come
     * from the *scoped* preferences this class just wrapped. Reading it from the raw delegate
     * instead would hand a feature WhatsApp's overrides when the process is WhatsApp Business -
     * the exact confusion the per-target work exists to prevent.
     *
     * One snapshot per call, and one call per target process, so there is no cache to invalidate
     * here. A feature captures it and sees a consistent view for its whole lifetime, which is what
     * an immutable snapshot is for.
     */
    @JvmStatic
    fun snapshotFor(
        scoped: SharedPreferences,
        target: TargetApp?,
    ): SettingsSnapshot {
        val resolver = EffectiveSettingsResolver(SharedPreferencesSettingsStore(scoped))
        // A process with no resolved target reads Global rather than failing: the snapshot has to
        // exist for the context to be constructible, and a target-less process is a real state
        // (the bridge, for one) rather than an error.
        // `snapshotFor` is declared over a TargetApp because a per-target snapshot is the case
        // that exists. A process with no resolved target is not one the entry point routes here,
        // so the null branch reports the absence rather than inventing a Global snapshot that no
        // resolver API would accept.
        return target?.let { resolver.snapshotFor(it) }
            ?: throw IllegalStateException(
                "no target resolved for $${scoped.javaClass.simpleName}; a settings snapshot cannot be built without one",
            )
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
            val raw = prefs.rawDelegate
            if (raw is XSharedPreferences) {
                raw.reload()
            }
            prefs.refresh(SharedPreferencesSettingsStore(raw))
        }
    }
}
