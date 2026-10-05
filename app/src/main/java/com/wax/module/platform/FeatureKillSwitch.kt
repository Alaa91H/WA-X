package com.wax.module.platform

/**
 * The five states a feature can be in, as defined by T82.
 *
 * They are distinct on purpose: "the user turned this off" and "this crashed three times"
 * call for different UI and different recovery paths, and collapsing them would make the
 * module blame the user for a compatibility failure. The set deliberately matches the
 * roadmap's list one-to-one.
 */
enum class FeatureSwitchState {
    /** Loads normally. */
    ENABLED,

    /** The user turned it off. Only the user turns it back on. */
    MANUALLY_DISABLED,

    /** Disabled for a bounded time after a failure, then retried automatically. */
    TEMPORARILY_DISABLED,

    /** Disabled by the platform after repeated startup failures. Manual retry is allowed. */
    AUTOMATICALLY_DISABLED,

    /** Declared unusable on the current WhatsApp build until resolvers improve. */
    INCOMPATIBLE,
    ;

    /** Whether the feature may load in this state. */
    val isLoadable: Boolean get() = this == ENABLED

    /** Whether this state is the user's own doing. */
    val isUserChoice: Boolean get() = this == MANUALLY_DISABLED
}

/**
 * Decides whether each feature is allowed to load, and remembers why not.
 *
 * This is the enforcement half of the isolation rule: detection (T81) is useless unless
 * something persists the decision and refuses to load the feature next start. The state is
 * stored in a [KeyValueStore] rather than kept in memory so it survives the crash loop it
 * is meant to break — the whole point of "three repeated startup failures → disable" is
 * that the fourth start behaves differently.
 *
 * Key layout (one set per feature, under [KEY_PREFIX]):
 * ```
 * wae.kill.<id>.state     manual_disabled | temporary_disabled | auto_disabled | incompatible
 * wae.kill.<id>.reason    the user-readable explanation
 * wae.kill.<id>.until     epoch millis when a temporary disable expires
 * wae.kill.<id>.failures  consecutive startup failures since the last success
 * ```
 */
class FeatureKillSwitch(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    /** The current state of [featureId]. */
    fun stateOf(featureId: String): FeatureSwitchState =
        synchronized(lock) {
            when (store.getString(stateKey(featureId))) {
                null -> FeatureSwitchState.ENABLED
                STATE_MANUAL -> FeatureSwitchState.MANUALLY_DISABLED
                STATE_TEMPORARY -> {
                    val until = store.getLong(untilKey(featureId))
                    if (until > 0L && now() >= until) {
                        // The temporary disable has served its purpose: clear it so the next
                        // failure starts a fresh count rather than instantly re-disabling.
                        store.remove(stateKey(featureId))
                        store.remove(reasonKey(featureId))
                        store.remove(untilKey(featureId))
                        FeatureSwitchState.ENABLED
                    } else {
                        FeatureSwitchState.TEMPORARILY_DISABLED
                    }
                }

                STATE_AUTO -> FeatureSwitchState.AUTOMATICALLY_DISABLED
                STATE_INCOMPATIBLE -> FeatureSwitchState.INCOMPATIBLE
                // An unrecognised value can only come from a corrupted or future store. Failing
                // open keeps a corrupt key from bricking a working feature; the state is not
                // silently rewritten so diagnostics can still show the anomaly if it matters.
                else -> FeatureSwitchState.ENABLED
            }
        }

    /** Whether [featureId] may load right now. */
    fun isLoadable(featureId: String): Boolean = stateOf(featureId).isLoadable

    /**
     * Turns a feature on or off at the user's request.
     *
     * Enabling clears every *platform* decision (temporary and automatic) but never an
     * [FeatureSwitchState.INCOMPATIBLE] verdict: the canary owns that state, and a user
     * toggle must not be able to install a hook against a build whose resolvers failed.
     *
     * @return the resulting state, which callers should surface rather than assume
     */
    fun setEnabled(
        featureId: String,
        enabled: Boolean,
    ): FeatureSwitchState =
        synchronized(lock) {
            if (enabled) {
                if (stateOf(featureId) == FeatureSwitchState.INCOMPATIBLE) {
                    return@synchronized FeatureSwitchState.INCOMPATIBLE
                }
                store.remove(stateKey(featureId))
                store.remove(reasonKey(featureId))
                store.remove(untilKey(featureId))
                store.remove(failuresKey(featureId))
                FeatureSwitchState.ENABLED
            } else {
                store.putString(stateKey(featureId), STATE_MANUAL)
                store.putString(reasonKey(featureId), "turned off by the user")
                FeatureSwitchState.MANUALLY_DISABLED
            }
        }

    /** Disables a feature until [untilMillis], citing [reason]. */
    fun disableTemporarily(
        featureId: String,
        reason: String,
        untilMillis: Long,
    ): FeatureSwitchState =
        synchronized(lock) {
            store.putString(stateKey(featureId), STATE_TEMPORARY)
            store.putString(reasonKey(featureId), reason)
            store.putLong(untilKey(featureId), untilMillis)
            FeatureSwitchState.TEMPORARILY_DISABLED
        }

    /** Disables a feature for [durationMillis] starting now. */
    fun disableTemporarilyFor(
        featureId: String,
        reason: String,
        durationMillis: Long,
    ): FeatureSwitchState = disableTemporarily(featureId, reason, now() + durationMillis)

    /** Marks a feature incompatible with the current build until the canary clears it. */
    fun markIncompatible(
        featureId: String,
        reason: String,
    ): FeatureSwitchState =
        synchronized(lock) {
            store.putString(stateKey(featureId), STATE_INCOMPATIBLE)
            store.putString(reasonKey(featureId), reason)
            FeatureSwitchState.INCOMPATIBLE
        }

    /** Clears an incompatibility verdict, for example after the resolvers are fixed. */
    fun clearIncompatible(featureId: String): FeatureSwitchState =
        synchronized(lock) {
            if (stateOf(featureId) == FeatureSwitchState.INCOMPATIBLE) {
                store.remove(stateKey(featureId))
                store.remove(reasonKey(featureId))
            }
            stateOf(featureId)
        }

    /**
     * Records one startup failure for [featureId].
     *
     * After [MAX_STARTUP_FAILURES] consecutive failures the feature is automatically
     * disabled. The count is only kept while the feature is loadable; a feature the user
     * disabled must not accumulate strikes in the background.
     *
     * @return the resulting state, so the caller can notify the user on the transition to
     *   [FeatureSwitchState.AUTOMATICALLY_DISABLED] rather than after every failure
     */
    fun recordStartupFailure(featureId: String): FeatureSwitchState =
        synchronized(lock) {
            when (stateOf(featureId)) {
                FeatureSwitchState.MANUALLY_DISABLED,
                FeatureSwitchState.INCOMPATIBLE,
                -> return@synchronized stateOf(featureId)

                else -> Unit
            }
            val failures = store.getInt(failuresKey(featureId)) + 1
            store.putInt(failuresKey(featureId), failures)
            if (failures >= MAX_STARTUP_FAILURES) {
                store.putString(stateKey(featureId), STATE_AUTO)
                store.putString(
                    reasonKey(featureId),
                    "disabled automatically after $failures repeated startup failures",
                )
                FeatureSwitchState.AUTOMATICALLY_DISABLED
            } else {
                stateOf(featureId)
            }
        }

    /**
     * Records a successful start of [featureId], resetting its failure count.
     *
     * A feature that worked must not carry strikes into the next problematic build, which
     * is what makes the three-failure rule mean "three *consecutive* failures".
     */
    fun recordStartupSuccess(featureId: String) =
        synchronized(lock) {
            store.remove(failuresKey(featureId))
        }

    /**
     * Re-enables a feature the platform disabled, leaving the user's own choice alone.
     *
     * This is the "allow manual retry" side of T81: after an automatic disable, the user
     * gets one clean attempt with the failure counter reset.
     */
    fun retry(featureId: String): FeatureSwitchState =
        synchronized(lock) {
            when (stateOf(featureId)) {
                FeatureSwitchState.AUTOMATICALLY_DISABLED, FeatureSwitchState.TEMPORARILY_DISABLED -> {
                    store.remove(stateKey(featureId))
                    store.remove(reasonKey(featureId))
                    store.remove(untilKey(featureId))
                    store.remove(failuresKey(featureId))
                    FeatureSwitchState.ENABLED
                }

                else -> stateOf(featureId)
            }
        }

    /** The explanation stored alongside the state, if any. */
    fun reasonFor(featureId: String): String? = synchronized(lock) { store.getString(reasonKey(featureId)) }

    /** The consecutive startup failure count for [featureId]. */
    fun failuresFor(featureId: String): Int = synchronized(lock) { store.getInt(failuresKey(featureId)) }

    /** Every feature that is not currently loadable, with its state and reason. */
    fun blocked(): List<BlockedFeature> =
        synchronized(lock) {
            store
                .keys(KEY_PREFIX)
                .filter { it.endsWith(STATE_SUFFIX) }
                .mapNotNull { key -> featureIdOf(key) }
                .distinct()
                .mapNotNull { featureId ->
                    val state = stateOf(featureId)
                    if (state.isLoadable) null else BlockedFeature(featureId, state, reasonFor(featureId))
                }
        }

    /** Renders the blocked set for diagnostics. */
    fun renderBlocked(): String {
        val blocked = blocked()
        if (blocked.isEmpty()) return "No features are disabled."
        return buildString {
            appendLine("Disabled features: ${blocked.size}")
            blocked.forEach { appendLine("* ${it.toDisplayLine()}") }
        }
    }

    /** Drops every stored decision. Used by tests and by a factory reset. */
    fun clear() =
        synchronized(lock) {
            store.keys(KEY_PREFIX).forEach { store.remove(it) }
        }

    private fun stateKey(featureId: String) = "$KEY_PREFIX$featureId$STATE_SUFFIX"

    private fun reasonKey(featureId: String) = "$KEY_PREFIX$featureId$REASON_SUFFIX"

    private fun untilKey(featureId: String) = "$KEY_PREFIX$featureId$UNTIL_SUFFIX"

    private fun failuresKey(featureId: String) = "$KEY_PREFIX$featureId$FAILURES_SUFFIX"

    /** Reverses [stateKey]; returns null when the key only shares the prefix. */
    private fun featureIdOf(stateKey: String): String? {
        if (!stateKey.startsWith(KEY_PREFIX) || !stateKey.endsWith(STATE_SUFFIX)) return null
        val id = stateKey.removePrefix(KEY_PREFIX).removeSuffix(STATE_SUFFIX)
        return id.ifBlank { null }
    }

    companion object {
        /** The number of consecutive startup failures that triggers automatic isolation. */
        const val MAX_STARTUP_FAILURES: Int = 3

        const val KEY_PREFIX: String = "wae.kill."
        const val STATE_SUFFIX: String = ".state"
        const val REASON_SUFFIX: String = ".reason"
        const val UNTIL_SUFFIX: String = ".until"
        const val FAILURES_SUFFIX: String = ".failures"

        private const val STATE_MANUAL = "manual_disabled"
        private const val STATE_TEMPORARY = "temporary_disabled"
        private const val STATE_AUTO = "auto_disabled"
        private const val STATE_INCOMPATIBLE = "incompatible"
    }
}

/** One feature the kill switch is holding back. */
data class BlockedFeature(
    val featureId: String,
    val state: FeatureSwitchState,
    val reason: String?,
) {
    /** One line for diagnostics. */
    fun toDisplayLine(): String = "$featureId [${state.name}]${if (reason.isNullOrBlank()) "" else ": $reason"}"
}
