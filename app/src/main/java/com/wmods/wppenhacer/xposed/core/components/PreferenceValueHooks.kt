package com.wmods.wppenhacer.xposed.core.components

/**
 * The value-transformation chain applied to intercepted preference reads.
 *
 * Kept separate from [SharedPreferencesWrapper] so the ordering rule is verifiable
 * without installing a hook. The chain is an ordered fold: every hook sees the previous
 * hook's output, and the last hook's return value is what the caller receives.
 */
object PreferenceValueHooks {
    /** One link in the chain. Mirrors [SharedPreferencesWrapper.SPrefHook]. */
    fun interface Transform {
        fun apply(
            key: String?,
            value: Any?,
        ): Any?
    }

    /**
     * Folds [hooks] over [value].
     *
     * A hook returning null removes the value from the chain rather than ending it: the
     * next hook still runs, and the chain's final result is whatever the last hook
     * produced. With no hooks registered the value is returned untouched.
     */
    fun applyAll(
        hooks: Iterable<Transform>,
        key: String?,
        value: Any?,
    ): Any? {
        var current = value
        for (hook in hooks) {
            current = hook.apply(key, current)
        }
        return current
    }
}
