package com.wax.module.settings

import com.wax.module.platform.TargetApp

/**
 * Answers "what is the effective value for this target".
 *
 * The rule is one line and everything else here exists to make it hard to get wrong:
 *
 *     target override, if present, otherwise the global value, otherwise the
 *     documented feature default.
 *
 * An override is *absent*, not false. That distinction is the whole point: a target
 * with no override follows Global automatically, so a change to Global propagates
 * without touching the target, and a target that diverges is unaffected by later
 * Global changes.
 */
class EffectiveSettingsResolver(
    private val store: SettingsStore,
    /** Documented defaults, consulted only when neither scope has a value. */
    private val defaults: Map<String, Any?> = emptyMap(),
) {
    /** The default for [key], or null when the feature declares none. */
    fun defaultOf(key: String): Any? = defaults[key]

    /**
     * The effective boolean for [key] in [scope].
     *
     * A target scope inherits Global. Global itself falls back to the documented
     * default, which is `false` when the feature declares none, so an unreadable or
     * missing value can never accidentally enable a hook.
     */
    fun effectiveBoolean(
        key: String,
        scope: SettingsScope,
    ): Boolean {
        if (scope !is SettingsScope.Global) {
            store.readBoolean(scope, key)?.let { return it }
        }
        store.readBoolean(SettingsScope.Global, key)?.let { return it }
        return defaults[key] as? Boolean ?: false
    }

    /** The effective string, or [fallback] when unset. */
    fun effectiveString(
        key: String,
        scope: SettingsScope,
        fallback: String? = null,
    ): String? {
        if (scope !is SettingsScope.Global) {
            store.readString(scope, key)?.let { return it }
        }
        store.readString(SettingsScope.Global, key)?.let { return it }
        return defaults[key] as? String ?: fallback
    }

    /** The effective integer, or [fallback] when unset or unreadable. */
    fun effectiveInt(
        key: String,
        scope: SettingsScope,
        fallback: Int = 0,
    ): Int {
        if (scope !is SettingsScope.Global) {
            store.readInt(scope, key)?.let { return it }
        }
        store.readInt(SettingsScope.Global, key)?.let { return it }
        return defaults[key] as? Int ?: fallback
    }

    /** The effective float, or [fallback] when unset or unreadable. */
    fun effectiveFloat(
        key: String,
        scope: SettingsScope,
        fallback: Float = 0f,
    ): Float {
        if (scope !is SettingsScope.Global) {
            store.readFloat(scope, key)?.let { return it }
        }
        store.readFloat(SettingsScope.Global, key)?.let { return it }
        return defaults[key] as? Float ?: fallback
    }

    /** The effective string set, or an empty set when unset. */
    fun effectiveStringSet(
        key: String,
        scope: SettingsScope,
    ): Set<String> {
        if (scope !is SettingsScope.Global) {
            store.readStringSet(scope, key)?.let { return it }
        }
        store.readStringSet(SettingsScope.Global, key)?.let { return it }
        @Suppress("UNCHECKED_CAST")
        return defaults[key] as? Set<String> ?: emptySet()
    }

    /**
     * The tri-state of [key] for [scope].
     *
     * INHERIT means "no override is stored", never "the stored value happens to equal
     * the global one". Those are different, and collapsing them is a real bug: a user
     * who set WhatsApp to Enabled while Global was already Enabled would see "Use
     * Global" in the UI, and turning Global off would then silently turn their WhatsApp
     * setting off with it.
     *
     * Global has no third state, because it is always concrete.
     */
    fun triState(
        key: String,
        scope: SettingsScope,
    ): TriState {
        if (scope is SettingsScope.Global) {
            return if (effectiveBoolean(key, scope)) TriState.ENABLED else TriState.DISABLED
        }
        val override = store.readBoolean(scope, key) ?: return TriState.INHERIT
        return if (override) TriState.ENABLED else TriState.DISABLED
    }

    /** Shorthand for [TriState.resolve]. */
    fun resolveBoolean(
        state: TriState,
        global: Boolean,
    ): Boolean = state.resolve(global)

    /** Shorthand for [OverrideValue.resolve]. */
    fun <T> resolveValue(
        override: OverrideValue<T>,
        global: T,
    ): T = OverrideValue.resolve(override, global)

    /** Writes the tri-state of [key] for [scope]. */
    fun setTriState(
        key: String,
        scope: SettingsScope,
        state: TriState,
    ) {
        when (state) {
            TriState.INHERIT -> store.writeBoolean(scope, key, null)
            TriState.ENABLED -> store.writeBoolean(scope, key, true)
            TriState.DISABLED -> store.writeBoolean(scope, key, false)
        }
    }

    /**
     * Whether [key] in [scope] diverges from Global.
     *
     * Used by the UI to mark an override and to offer "reset to Global". A key that
     * only exists in Global is never an override.
     */
    fun isOverridden(
        key: String,
        scope: SettingsScope,
    ): Boolean {
        if (scope is SettingsScope.Global) return false
        return when {
            store.readBoolean(scope, key) != null -> true
            store.readString(scope, key) != null -> true
            store.readInt(scope, key) != null -> true
            store.readFloat(scope, key) != null -> true
            store.readStringSet(scope, key) != null -> true
            else -> false
        }
    }

    /** Every key overridden in [scope], sorted so the UI is stable. */
    fun overriddenKeys(scope: SettingsScope): List<String> = store.keysWithOverrides(scope).sorted()

    /** Removes [key] from [scope], leaving Global and the other target untouched. */
    fun resetKey(
        key: String,
        scope: SettingsScope,
    ) {
        store.writeString(scope, key, null)
        store.writeBoolean(scope, key, null)
        store.writeInt(scope, key, null)
        store.writeFloat(scope, key, null)
        store.writeStringSet(scope, key, null)
    }

    /** Removes every override in [scope], leaving Global untouched. */
    fun resetScope(scope: SettingsScope) {
        if (scope is SettingsScope.Global) {
            // Resetting Global is a different operation with a different confirmation,
            // handled by the caller, because it changes what every inheriting target
            // resolves to.
            store.clearScope(scope)
        } else {
            store.clearScope(scope)
        }
    }

    /**
     * Copies the concrete Global values into [scope] as explicit overrides.
     *
     * After this the target no longer follows Global, which is the point: it is how a
     * user pins a target to today's Global behaviour before changing Global.
     *
     * Every value type is copied, not just strings, because a user pinning
     * "send video in real resolution" is pinning a boolean while someone pinning the
     * status style is pinning a string.
     */
    fun freezeGlobalInto(scope: SettingsScope) {
        if (scope is SettingsScope.Global) return
        for (key in store.keysWithOverrides(SettingsScope.Global)) {
            store.readString(SettingsScope.Global, key)?.let { store.writeString(scope, key, it) }
            store.readBoolean(SettingsScope.Global, key)?.let { store.writeBoolean(scope, key, it) }
            store.readInt(SettingsScope.Global, key)?.let { store.writeInt(scope, key, it) }
            store.readFloat(SettingsScope.Global, key)?.let { store.writeFloat(scope, key, it) }
            store.readStringSet(SettingsScope.Global, key)?.let { store.writeStringSet(scope, key, it) }
        }
    }

    /** Copies [from]'s overrides onto [to], replacing what [to] had. */
    fun copyOverrides(
        from: SettingsScope,
        to: SettingsScope,
    ) {
        if (from == to) return
        store.copyScope(from, to)
    }

    /**
     * A snapshot bound to one hooked process.
     *
     * The hooks run inside WhatsApp, where every settings read is on a hot path. A
     * snapshot reads the store once per reload and answers from memory, so a hook
     * never performs disk I/O.
     *
     * Snapshots are per target and never shared, which is what keeps one target's
     * values from reaching the other process.
     */
    fun snapshotFor(app: TargetApp): SettingsSnapshot = SettingsSnapshot(this, SettingsScope.Target(app))
}
