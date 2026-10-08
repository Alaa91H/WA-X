package com.wax.module.activation

import com.wax.module.health.FailureSeverity
import com.wax.module.health.HealthFreshness
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.SubsystemState

/**
 * Turns what is known about a target into what may be said about it.
 *
 * This is the whole point of M02, and it is deliberately the only place a fact becomes a
 * claim. Everything here is a pure function of its arguments, so every failure mode the issue
 * names - no framework, module disabled, missing scope, stopped target, no injection, a DexKit
 * failure, a stale heartbeat, a restart - is a unit test rather than a screenshot taken on a
 * device that does not exist in CI.
 *
 * The order of the questions is the order of authority:
 *
 * 1. Not installed beats everything: there is nothing to be injected into.
 * 2. A heartbeat from inside the target, in this boot, beats every external observation,
 *    because it is the only source produced by the code the question is about.
 * 3. A heartbeat from a *previous* boot is not evidence about this one, whatever its age.
 * 4. Only then may the manager's own observations be used, and only to say what they can
 *    actually say: a process exists (so it is running) or it does not (so nothing is).
 *
 * What the resolver will not do is produce [RuntimeFailureCode.SCOPE_MISSING] from "no
 * heartbeat". Absence of evidence is not evidence of a missing scope, and the framework does
 * not tell a third process what its scope contains. Where the honest answer is "a framework
 * loaded WA X somewhere but not here, and nothing here can say which part of that is wrong",
 * the resolver returns [ActivationAction.ENABLE_IN_FRAMEWORK] and - only when the framework
 * itself is proven to exist - [RuntimeFailureCode.INJECTION_NOT_OBSERVED]. That code is the
 * one generic message this design still permits, because it is the one case where a framework
 * is demonstrably present and injection into a demonstrably running target is demonstrably
 * absent. Everything else - a resolver failure, an engine failure, a feature failure - reaches
 * the card as its own code, which is the mechanism that stops a DexKit failure being shown as
 * "LSPosed is disabled".
 */
object ActivationStatusResolver {
    /** The package the module-level status describes. */
    const val MODULE_PACKAGE: String = "com.wax.module"

    /** Interprets [observation] as of [nowMillis] in boot [currentBootId]. */
    fun resolve(
        observation: ActivationObservation,
        nowMillis: Long,
        currentBootId: String,
    ): ActivationStatus {
        val signal = observation.strongestSignalFromThisBoot(currentBootId)
        if (!observation.installed) {
            return status(observation, ActivationState.NOT_INSTALLED, HealthFreshness.ABSENT, ActivationAction.INSTALL_TARGET, signal)
        }

        val fromThisBoot = observation.heartbeat?.takeIf { it.isFromBoot(currentBootId) }
        if (fromThisBoot != null) {
            return fromHeartbeat(observation, fromThisBoot, nowMillis)
        }

        if (observation.process.isRunning) {
            return status(
                observation,
                ActivationState.RUNNING_NOT_INJECTED,
                HealthFreshness.ABSENT,
                ActivationAction.ENABLE_IN_FRAMEWORK,
                signal,
                // Proven only when the framework is known to exist. Without the legacy signal
                // this is "something is running and nothing reported", which is not yet a
                // finding - the target may have been observed during its first seconds, before
                // the module finished loading.
                failureCode = if (observation.legacySelfHookSignal) RuntimeFailureCode.INJECTION_NOT_OBSERVED else null,
            )
        }

        // The process list was authoritative and did not contain the target: it is not running,
        // and nothing it could have reported.
        if (observation.process == TargetProcessObservation.NOT_RUNNING) {
            return status(
                observation,
                ActivationState.NOT_RUNNING,
                observation.heartbeat?.freshnessAt(nowMillis) ?: HealthFreshness.ABSENT,
                ActivationAction.OPEN_TARGET,
                signal,
            )
        }

        // Installed, no heartbeat, and the platform would not say whether the process exists.
        // This is the case the previous interface turned into "WhatsApp is not running or has
        // not been activated in Lsposed", which asserted two things it had evidence for neither
        // of. The honest state is that nothing has been observed, and the action is the one
        // that produces evidence.
        return status(
            observation,
            ActivationState.UNKNOWN,
            observation.heartbeat?.freshnessAt(nowMillis) ?: HealthFreshness.ABSENT,
            ActivationAction.OPEN_TARGET,
            signal,
        )
    }

    private fun fromHeartbeat(
        observation: ActivationObservation,
        heartbeat: TargetHeartbeat,
        nowMillis: Long,
    ): ActivationStatus {
        val freshness = heartbeat.freshnessAt(nowMillis)
        // A stale or expired report still proves injection *happened* in this boot, so the
        // state stays an injected one and the age travels beside it. Collapsing the two would
        // either paint a green banner over a process that is gone, or claim the module was
        // never loaded after it demonstrably was.
        val state =
            when {
                freshness != HealthFreshness.FRESH -> ActivationState.UNKNOWN
                heartbeat.state == SubsystemState.READY -> ActivationState.READY
                heartbeat.state == SubsystemState.STARTING -> ActivationState.BOOTSTRAPPING
                heartbeat.state == SubsystemState.DEGRADED || heartbeat.state == SubsystemState.FAILED -> activatedButImpaired(heartbeat)
                else -> ActivationState.UNKNOWN
            }
        val action =
            when (state) {
                ActivationState.READY -> ActivationAction.NONE

                ActivationState.BOOTSTRAPPING -> ActivationAction.WAIT

                ActivationState.DEGRADED, ActivationState.FAILED -> ActivationAction.OPEN_DIAGNOSTICS

                // Stale or expired: the module was loaded and the target is not reporting any
                // more. Starting the app again is the action that produces new evidence.
                else -> ActivationAction.OPEN_TARGET
            }
        return status(observation, state, freshness, action, ActivationSignal.FRAMEWORK_EVIDENCE, heartbeat.failureCode)
    }

    /**
     * Whether an impaired runtime is degraded or failed, in the runtime's own words.
     *
     * The aggregate deliberately ranks "a subsystem that is not part of the core failed" as
     * [SubsystemState.DEGRADED], because some subsystems - the optional ones - may fail without
     * * the module being called broken. That is the right rule and it is the runtime's to apply;
     * what it cannot know is whether *this particular* failure was one of the optional ones.
     *
     * The code can, because [RuntimeFailureCode.severity] is a property of the failure rather
     * than of the ordering somebody typed into a `when`. So the aggregate stays the aggregate -
     * the Manager does not recompute it - and the one word the interface uses is chosen from
     * the severity the runtime already assigned.
     *
     * This is not cosmetic. A runtime whose resolution engine failed to start installed zero
     * hooks; describing that as "reduced capability" is the soft-pedal this package exists to
     * remove, and it is the same class of error as the banner that called it enabled.
     */
    private fun activatedButImpaired(heartbeat: TargetHeartbeat): ActivationState =
        when (heartbeat.failureCode?.severity) {
            null, FailureSeverity.MINOR -> ActivationState.DEGRADED
            FailureSeverity.MAJOR, FailureSeverity.CRITICAL -> ActivationState.FAILED
        }

    /**
     * The status of the module itself.
     *
     * Derived from the targets, not from the module's opinion of itself. The self-hook is
     * still read, and still reported, but as [ActivationSignal.LEGACY_SELF_HOOK_SIGNAL] with no
     * claim about any target: a target that is ready while the legacy signal is false is not a
     * contradiction, it is a target that reported evidence of its own.
     */
    fun resolveModuleStatus(
        legacySelfHookSignal: Boolean,
        targetStatuses: List<ActivationStatus>,
    ): ActivationStatus {
        val injected = targetStatuses.filter { it.state.isInjected }
        val failed = injected.filter { it.state == ActivationState.FAILED }
        val degraded = injected.filter { it.state == ActivationState.DEGRADED }
        val signal = if (legacySelfHookSignal) ActivationSignal.LEGACY_SELF_HOOK_SIGNAL else ActivationSignal.NONE

        val state =
            when {
                failed.isNotEmpty() -> ActivationState.FAILED
                degraded.isNotEmpty() -> ActivationState.DEGRADED
                injected.isNotEmpty() -> ActivationState.READY
                legacySelfHookSignal -> ActivationState.RUNNING_NOT_INJECTED
                else -> ActivationState.UNKNOWN
            }
        val action =
            when (state) {
                ActivationState.READY -> ActivationAction.NONE
                ActivationState.RUNNING_NOT_INJECTED -> ActivationAction.OPEN_TARGET
                ActivationState.DEGRADED, ActivationState.FAILED -> ActivationAction.OPEN_DIAGNOSTICS
                else -> ActivationAction.ENABLE_IN_FRAMEWORK
            }
        return ActivationStatus(
            packageName = MODULE_PACKAGE,
            state = state,
            freshness = injected.bestFreshness(),
            failureCode =
                when {
                    failed.isNotEmpty() -> RuntimeFailureCode.mostSevere(failed.mapNotNull { it.failureCode })
                    degraded.isNotEmpty() -> RuntimeFailureCode.OPTIONAL_FEATURE_FAILED
                    else -> null
                },
            action = action,
            signal = signal,
        )
    }

    /** The best (youngest) freshness among injected targets; ABSENT when there are none. */
    private fun List<ActivationStatus>.bestFreshness(): HealthFreshness =
        filter { it.state.isInjected }
            .minByOrNull { it.freshness.ordinal }
            ?.freshness
            ?: HealthFreshness.ABSENT

    private fun status(
        observation: ActivationObservation,
        state: ActivationState,
        freshness: HealthFreshness,
        action: ActivationAction,
        signal: ActivationSignal,
        failureCode: RuntimeFailureCode? = null,
    ): ActivationStatus =
        ActivationStatus(
            packageName = observation.packageName,
            state = state,
            freshness = freshness,
            failureCode = failureCode,
            action = action,
            signal = signal,
        )
}
