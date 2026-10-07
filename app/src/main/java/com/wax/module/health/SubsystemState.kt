package com.wax.module.health

/**
 * The state of one subsystem.
 *
 * These are deliberately not ordered from good to bad. [DEGRADED] and [FAILED] are
 * different claims, [UNAVAILABLE] is a statement about the environment rather than about
 * a failure, and [SKIPPED] means the stage was never attempted — reporting "failed" for
 * something that never ran is the same class of error as reporting "disabled" for
 * LSPosed because DexKit failed.
 */
enum class SubsystemState {
    /** Nothing has reported on this subsystem yet. The only honest initial value. */
    UNKNOWN,

    /** The stage that decides this subsystem's state is currently running. */
    STARTING,

    /** The subsystem is present and working. */
    READY,

    /** Working with reduced capability, or through a fallback path. */
    DEGRADED,

    /** Attempted and failed. */
    FAILED,

    /** Not present in this environment, and not expected to be. */
    UNAVAILABLE,

    /** Deliberately not attempted in this session. */
    SKIPPED,
    ;

    /** Whether the subsystem is working in any form. */
    val isWorking: Boolean get() = this == READY || this == DEGRADED

    /** Whether the subsystem is in a state a user would want to be told about. */
    val isNotable: Boolean get() = this == FAILED || this == DEGRADED || this == UNAVAILABLE

    /**
     * Whether a subsystem in this state may move to [next].
     *
     * Three rules, each of which exists because breaking it would hide a real failure:
     *
     * 1. **A subsystem never unlearns its state.** Only [UNKNOWN] may be reported as
     *    [UNKNOWN], so a late-arriving stage cannot overwrite a failure with the initial
     *    value and turn a broken runtime into a fresh-looking one.
     * 2. **A stage must be attempted before its outcome is reported.** A subsystem that is
     *    [UNAVAILABLE] or [SKIPPED] may only become [STARTING] or [READY]; it cannot jump
     *    to [FAILED] or [DEGRADED], because neither is an outcome of not trying.
     * 3. **Everything else is allowed.** Failures are retried, degraded subsystems
     *    recover, and a new session restarts a subsystem that was [READY]; forbidding any
     *    of those would make the model reject the truth.
     *
     * Re-reporting the current state is always allowed, because idempotent reporting is
     * how a stage that runs twice says the same thing twice.
     */
    fun canTransitionTo(next: SubsystemState): Boolean {
        if (next == this) return true
        if (next == UNKNOWN) return false
        if (this == UNKNOWN) return true
        if (this == UNAVAILABLE || this == SKIPPED) {
            return next == STARTING || next == READY
        }
        return true
    }
}
