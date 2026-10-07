package com.wax.module.health

/**
 * The stable, machine-readable reason a runtime subsystem is not working.
 *
 * This is the runtime-level counterpart of [com.wax.module.diagnostics.FailureCode],
 * which classifies a single feature's failure. The two must not be merged: a feature
 * code answers "which feature broke and how", and this one answers "which part of the
 * runtime is not there". Reporting a [DEXKIT_INIT_FAILED] as a feature failure is exactly
 * how a resolver problem ends up on screen as "LSPosed is disabled".
 *
 * Names are part of the contract: codes may be added, and existing ones must never be
 * repurposed. Every code names the one subsystem it belongs to, so a card can never
 * infer another subsystem's failure from it.
 *
 * [severity] exists so the aggregate state of the runtime does not have to be decided by
 * an ordering somebody typed into a `when` block: the worst failure present is the one
 * reported, and "worst" is a property of the code.
 */
enum class RuntimeFailureCode(
    /** The subsystem this code is allowed to describe. */
    val subsystem: RuntimeSubsystem,
    /** How much of the module this failure takes with it. */
    val severity: FailureSeverity,
) {
    /** No framework answered in this process. */
    FRAMEWORK_UNAVAILABLE(RuntimeSubsystem.FRAMEWORK, FailureSeverity.CRITICAL),

    /** The framework is present but the module is not enabled for this package. */
    MODULE_DISABLED(RuntimeSubsystem.MODULE, FailureSeverity.CRITICAL),

    /** This package is not in the module's scope, so the module never ran here. */
    SCOPE_MISSING(RuntimeSubsystem.SCOPE, FailureSeverity.CRITICAL),

    /** The target application is not running, so this process does not exist. */
    TARGET_NOT_RUNNING(RuntimeSubsystem.TARGET_PROCESS, FailureSeverity.MAJOR),

    /** The target is running and the module has not been observed inside it. */
    INJECTION_NOT_OBSERVED(RuntimeSubsystem.INJECTION, FailureSeverity.MAJOR),

    /** Injection was attempted and threw. */
    INJECTION_FAILED(RuntimeSubsystem.INJECTION, FailureSeverity.CRITICAL),

    /** Settings could not be read in this process. */
    PREFERENCES_UNAVAILABLE(RuntimeSubsystem.PREFERENCES, FailureSeverity.MAJOR),

    /** DexKit could not be initialised, so resolution cannot start. */
    DEXKIT_INIT_FAILED(RuntimeSubsystem.DEXKIT, FailureSeverity.MAJOR),

    /** Resolution ran and failed, or matched ambiguously. */
    RESOLVER_FAILED(RuntimeSubsystem.RESOLVER, FailureSeverity.MAJOR),

    /** A core component the shared infrastructure depends on failed. */
    CORE_COMPONENT_FAILED(RuntimeSubsystem.CORE_COMPONENTS, FailureSeverity.CRITICAL),

    /** A hook the module cannot work without failed to install. */
    ESSENTIAL_HOOK_FAILED(RuntimeSubsystem.ESSENTIAL_HOOKS, FailureSeverity.CRITICAL),

    /** An optional feature failed. The module is degraded, not broken. */
    OPTIONAL_FEATURE_FAILED(RuntimeSubsystem.OPTIONAL_HOOKS, FailureSeverity.MINOR),

    /** The target's last heartbeat is between the fresh and expired thresholds. */
    HEARTBEAT_STALE(RuntimeSubsystem.TARGET_PROCESS, FailureSeverity.MINOR),

    /** The target's last heartbeat is past the expiry threshold. */
    HEARTBEAT_EXPIRED(RuntimeSubsystem.TARGET_PROCESS, FailureSeverity.MAJOR),

    /** The target's version is outside the module's supported set. */
    VERSION_UNSUPPORTED(RuntimeSubsystem.TARGET_PROCESS, FailureSeverity.CRITICAL),

    /** A stage exceeded its time budget. */
    RUNTIME_TIMEOUT(RuntimeSubsystem.OVERALL_RUNTIME, FailureSeverity.MAJOR),

    /** The watchdog tripped. */
    WATCHDOG_TRIPPED(RuntimeSubsystem.OVERALL_RUNTIME, FailureSeverity.MAJOR),

    /** Nothing more specific could be determined. Saying so is better than guessing. */
    UNKNOWN(RuntimeSubsystem.OVERALL_RUNTIME, FailureSeverity.MINOR),
    ;

    companion object {
        /** The codes belonging to [subsystem]. */
        fun forSubsystem(subsystem: RuntimeSubsystem): List<RuntimeFailureCode> = entries.filter { it.subsystem == subsystem }

        /**
         * The most severe code in [codes], or null when there are none.
         *
         * Ties are broken by declaration order, so the reported code for a given set of
         * failures is stable rather than dependent on iteration order.
         */
        fun mostSevere(codes: Collection<RuntimeFailureCode>): RuntimeFailureCode? =
            codes.minWithOrNull(compareBy({ it.severity.rank }, { it.ordinal }))
    }
}

/**
 * How much of the module a failure takes with it.
 *
 * The ordering is used to pick the one code reported for the runtime as a whole, which is
 * why it is a property of the failure rather than a decision made at the reporting site.
 */
enum class FailureSeverity(
    /** Lower is worse; the minimum is the reported severity. */
    val rank: Int,
) {
    /** The module cannot work at all in this state. */
    CRITICAL(0),

    /** The runtime works, but a required capability is missing or broken. */
    MAJOR(1),

    /** Something optional is broken or uncertain. */
    MINOR(2),
}
