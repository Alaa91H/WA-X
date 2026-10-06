package com.wax.module.settings

import com.wax.module.platform.TargetApp

/**
 * How a value is stored, and how it is found again.
 *
 * Global values keep their original key, byte for byte. That is deliberate and it is the
 * reason this class exists in this shape: several hundred feature call sites read
 * `pref.getBoolean("somekey", false)` against a preference file written by an older
 * version of the module. Moving Global under a prefix would require rewriting all of
 * them at once and would break every existing user's configuration. Namespacing only the
 * overrides means a value written by 1.0.0 is still the value read by 1.1.0.
 *
 * Target overrides therefore live under a prefix, because they are new data and their
 * names must not collide with a Global key.
 */
object SettingsKeys {
    /** Prefix for a per-target override. */
    const val TARGET_PREFIX: String = "waxtarget."

    /** The physical key an override for [key] in [scope] occupies. */
    fun physicalKey(
        scope: SettingsScope,
        key: String,
    ): String =
        when (scope) {
            is SettingsScope.Global -> key
            is SettingsScope.Target -> TARGET_PREFIX + scope.app.code + "." + key
        }

    /** The key an override resolves to, or null when [physicalKey] is a Global key. */
    fun overrideKey(physicalKey: String): String? =
        if (physicalKey.startsWith(TARGET_PREFIX)) {
            physicalKey
                .removePrefix(TARGET_PREFIX)
                .substringAfter('.', "")
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /** The target an override physical key belongs to, or null. */
    fun overrideTarget(physicalKey: String): TargetApp? {
        if (!physicalKey.startsWith(TARGET_PREFIX)) return null
        val code = physicalKey.removePrefix(TARGET_PREFIX).substringBefore('.', "")
        return TargetApp.fromCode(code)
    }

    /** Whether [physicalKey] is one of ours rather than a Global value. */
    fun isOverrideKey(physicalKey: String): Boolean = physicalKey.startsWith(TARGET_PREFIX)
}
