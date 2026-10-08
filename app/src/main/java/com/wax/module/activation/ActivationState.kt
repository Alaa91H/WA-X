package com.wax.module.activation

import com.wax.module.health.HealthFreshness
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.SubsystemState

/**
 * One target's activation, in the words that can each lead to a different action.
 *
 * The states are not ordered, and there is deliberately no `isHealthy`. Each of these is a
 * different claim about the world and each of them asks a different thing of the user:
 *
 * | State | What is known | What the user can do |
 * | --- | --- | --- |
 * | [NOT_INSTALLED] | the app is not on the device | install it |
 * | [NOT_RUNNING] | installed, no process, nothing has ever reported | open it |
 * | [RUNNING_NOT_INJECTED] | a process exists and WA X never reported from inside it | enable the module, set the scope |
 * | [BOOTSTRAPPING] | WA X is executing and has not finished starting | wait |
 * | [DEGRADED] | WA X is executing with reduced capability | read the failure code |
 * | [FAILED] | WA X is executing and a required part failed | read the failure code |
 * | [READY] | WA X is executing and everything it needs is working | nothing |
 * | [UNKNOWN] | something was observed but it does not identify a failure | look again |
 *
 * [UNKNOWN] exists because "the process was there and then was not" and "the process was never
 * there" are different, and neither of them is proof of a misconfiguration. Collapsing both
 * into `NOT_RUNNING` is how a user is told to check the scope when the scope was never the
 * problem, and how a user is told nothing is wrong when the module never loaded at all.
 */
enum class ActivationState {
    /** The target application is not installed on this device. */
    NOT_INSTALLED,

    /** Installed, and nothing has ever reported from inside it. */
    NOT_RUNNING,

    /** A process for this target exists and WA X has never been observed inside it. */
    RUNNING_NOT_INJECTED,

    /** WA X is executing in the target and has not finished starting. */
    BOOTSTRAPPING,

    /** WA X is executing in the target with reduced capability. */
    DEGRADED,

    /** WA X is executing in the target and a part it requires failed. */
    FAILED,

    /** WA X is executing in the target and everything it requires is working. */
    READY,

    /**
     * A report exists but is too old to describe the target now, or it describes a process
     * that has gone.
     */
    UNKNOWN,
    ;

    /** Whether this state means WA X's code is executing inside the target. */
    val isInjected: Boolean
        get() = this == BOOTSTRAPPING || this == DEGRADED || this == FAILED || this == READY

    /** Whether this state is a claim about the target rather than about the module. */
    val isProvenFailure: Boolean
        get() = this == DEGRADED || this == FAILED
}

/**
 * What the manager knows about one target, before any interpretation.
 *
 * This is the honest input: each field is a fact with a named source, and nothing here is
 * already a conclusion. Interpretation lives in [ActivationStatusResolver] so it can be tested
 * without Android, and so there is exactly one place where a fact becomes a claim.
 */
data class ActivationObservation(
    /** The target package, e.g. `com.whatsapp`. */
    val packageName: String,
    /** Whether the package manager reports the app as installed. */
    val installed: Boolean,
    /** What could be learned about the target's process. See [TargetProcessObservation]. */
    val process: TargetProcessObservation = TargetProcessObservation.UNOBSERVABLE,
    /** The most recent heartbeat received from inside the target, if any. */
    val heartbeat: TargetHeartbeat? = null,
    /** Whether the module's own process was hooked. Legacy source; see [ActivationSignal]. */
    val legacySelfHookSignal: Boolean = false,
) {
    /** Which sources this observation draws on. */
    val signals: List<ActivationSignal>
        get() = signalsFromThisBoot(null)

    /**
     * The strongest source this observation draws on in boot [currentBootId].
     *
     * A heartbeat filed before the last reboot is not a source for anything about the machine
     * now, so it is excluded here rather than being counted and then discounted downstream. That
     * matters because [ActivationSignal.FRAMEWORK_EVIDENCE] outranks everything: a stale record
     * that was allowed to contribute would make the interface claim framework evidence it does
     * not have, which is the same class of error as the boolean it replaced.
     */
    fun strongestSignalFromThisBoot(currentBootId: String?): ActivationSignal =
        signalsFromThisBoot(currentBootId)
            .maxByOrNull { it.authority }
            ?: ActivationSignal.NONE

    private fun signalsFromThisBoot(currentBootId: String?): List<ActivationSignal> =
        buildList {
            if (heartbeat != null && (currentBootId == null || heartbeat.isFromBoot(currentBootId))) {
                add(ActivationSignal.FRAMEWORK_EVIDENCE)
            }
            if (process.isRunning) add(ActivationSignal.TARGET_PROCESS_OBSERVATION)
            if (installed) add(ActivationSignal.PACKAGE_METADATA)
            if (legacySelfHookSignal) add(ActivationSignal.LEGACY_SELF_HOOK_SIGNAL)
            if (isEmpty()) add(ActivationSignal.NONE)
        }
}

/**
 * The interpretation of an [ActivationObservation]: a state, how old its evidence is, the one
 * failure that explains it, and the action it implies.
 *
 * [failureCode] is the point of the whole type. The generic message this replaced existed
 * because nothing carried the reason, so every reason produced the same sentence. A card that
 * has a [RuntimeFailureCode] can name what failed; a card that has [ActivationState.UNKNOWN]
 * must not pretend to know.
 */
data class ActivationStatus(
    /** The package this status is about. */
    val packageName: String,
    /** What is known. */
    val state: ActivationState,
    /** How old the evidence behind [state] is. */
    val freshness: HealthFreshness,
    /** The one failure that explains [state], or null when nothing failed. */
    val failureCode: RuntimeFailureCode? = null,
    /** The action this state implies. */
    val action: ActivationAction,
    /** The source the state was derived from. */
    val signal: ActivationSignal,
) {
    /**
     * Whether a generic "not enabled in LSPosed" message is allowed.
     *
     * Gate A of M02: that message is forbidden unless the failure was actually proven. Exactly
     * one failure in this model meets that bar - [RuntimeFailureCode.INJECTION_NOT_OBSERVED],
     * which requires both halves to have been observed: a framework demonstrably loaded the
     * module somewhere ([ActivationSignal.LEGACY_SELF_HOOK_SIGNAL]), and a process
     * demonstrably exists for the target with no report from inside it. Those two together
     * prove the module is enabled somewhere and not here, which is the only thing a user can
     * act on.
     *
     * [RuntimeFailureCode.SCOPE_MISSING] and [RuntimeFailureCode.MODULE_DISABLED] deliberately
     * do not qualify, although they sound like they should. Neither can be proven from any
     * process: the framework does not report a module's scope to a third process, and "the
     * module is not enabled" is indistinguishable from "no framework exists" from here. Naming
     * either would be the guess this package exists to remove - so they are produced only by a
     * runtime that can see the framework's own decision, and never by a Manager-side absence.
     */
    val mayReportFrameworkActivationFailure: Boolean
        get() = failureCode == RuntimeFailureCode.INJECTION_NOT_OBSERVED

    /** Whether the subsystem state [state] implies, for rendering a detail row. */
    val subsystemState: SubsystemState?
        get() =
            when (state) {
                ActivationState.BOOTSTRAPPING -> SubsystemState.STARTING
                ActivationState.READY -> SubsystemState.READY
                ActivationState.DEGRADED -> SubsystemState.DEGRADED
                ActivationState.FAILED -> SubsystemState.FAILED
                else -> null
            }
}
