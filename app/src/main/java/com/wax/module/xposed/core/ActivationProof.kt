package com.wax.module.xposed.core

import com.wax.module.activation.ActivationHeartbeatFactory
import com.wax.module.activation.TargetHeartbeat
import com.wax.module.diagnostics.FailureCode
import com.wax.module.diagnostics.FeatureFailureReport
import com.wax.module.health.HealthEventStatus
import com.wax.module.health.HealthReporter
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.RuntimeSubsystem
import com.wax.module.health.SubsystemState

/**
 * Reports the facts that prove activation, in the order the runtime establishes them.
 *
 * M01 built a twelve-subsystem health model and wired one of them. That was deliberate - a
 * model nothing writes to cannot be trusted - but it left the model unable to say anything
 * useful: with eleven subsystems still reporting `UNKNOWN`, the aggregate is `DEGRADED` by its
 * own rule 5 ("some subsystems have reported and others have not"), so a perfectly healthy
 * bootstrap could only ever publish "degraded". A status card that always says degraded is
 * the same defect as the boolean it replaced, with more words.
 *
 * So this class is where the remaining subsystems get their evidence, and every method here
 * corresponds to something the framework already told us rather than to something we decided:
 *
 * | Call | Evidence it records |
 * | --- | --- |
 * | [frameworkAnswered] | the framework invoked our entry point in this process |
 * | [moduleLoaded] | the module is executing, at this version |
 * | [scopeIsEffective] | the framework decided to load us *into this package* |
 * | [targetProcessIsRunning] | we are inside the target's process |
 * | [injected] | our code is executing here |
 * | [preferencesResolved] | settings were read, and by which route |
 * | [coreComponentsReady] | shared infrastructure initialised |
 * | [hooksInstalled] | the hook set was installed |
 * | [optionalFeaturesInstalled] | which optional features failed |
 * | [resolversFinished] | what resolution produced across the session |
 *
 * `scopeIsEffective` is the answer the scope array cannot give. The `xposedscope` metadata is
 * a *recommendation* the user is free to edit; the framework reaching this package is proof of
 * the *effective* scope. Recording the difference is what stops "WhatsApp is not in the scope"
 * from being asserted on the basis of a file the user may have changed.
 *
 * Nothing here decides what the user is told. It records what happened; the interpretation is
 * [com.wax.module.activation.ActivationStatusResolver]'s, in code that can be tested without a
 * device.
 */
class ActivationProof(
    private val health: HealthReporter,
) {
    /**
     * The record this proof writes into.
     *
     * Exposed rather than hidden so a reader - the Manager's own diagnostics, or a test - can
     * inspect exactly what the runtime claimed, instead of inferring it from what the interface
     * shows. A fact that can only be read through a formatted sentence cannot be checked.
     */
    val reporter: HealthReporter get() = health

    /**
     * The framework called us.
     *
     * There is no code path that reaches this class without the Xposed framework having loaded
     * the module and invoked the entry point, which is why it is recorded as [SubsystemState.READY]
     * and never as a condition: the condition would be the framework's absence, and its absence
     * means nothing here is called at all. Absence is observed from the other side, by the
     * Manager, and it is reported as absence rather than as a subsystem that failed.
     */
    fun frameworkAnswered(): ReportOutcome = report(RuntimeSubsystem.FRAMEWORK, SubsystemState.READY, null, COMPONENT_FRAMEWORK)

    /** The module is loaded and executing, at the version the framework loaded. */
    fun moduleLoaded(moduleVersion: String): ReportOutcome =
        report(RuntimeSubsystem.MODULE, SubsystemState.READY, null, COMPONENT_MODULE, moduleVersion)

    /**
     * The framework reached this package, so this package is in the module's effective scope.
     *
     * A recommendation and an effective scope are different things and only one of them is
     * observable from inside the process, so only this one is ever reported.
     */
    fun scopeIsEffective(): ReportOutcome = report(RuntimeSubsystem.SCOPE, SubsystemState.READY, null, COMPONENT_SCOPE)

    /** We are running inside the target's process. */
    fun targetProcessIsRunning(): ReportOutcome =
        report(RuntimeSubsystem.TARGET_PROCESS, SubsystemState.READY, null, COMPONENT_TARGET_PROCESS)

    /** Our code is executing in the target. */
    fun injected(): ReportOutcome = report(RuntimeSubsystem.INJECTION, SubsystemState.READY, null, COMPONENT_INJECTION)

    /**
     * Settings were read.
     *
     * [direct] says whether the shared preference file was read directly or through the
     * fallback provider. The fallback works, so it is a degradation and not a failure - and it
     * is reported as one, because "your settings are being read through a fallback" is a real
     * thing a user can act on and the previous code never said it.
     */
    fun preferencesResolved(direct: Boolean): ReportOutcome =
        if (direct) {
            report(RuntimeSubsystem.PREFERENCES, SubsystemState.READY, null, COMPONENT_PREFERENCES)
        } else {
            report(
                RuntimeSubsystem.PREFERENCES,
                SubsystemState.DEGRADED,
                RuntimeFailureCode.PREFERENCES_UNAVAILABLE,
                COMPONENT_PREFERENCES,
            )
        }

    /** The shared infrastructure every feature is built on initialised. */
    fun coreComponentsReady(): ReportOutcome =
        report(RuntimeSubsystem.CORE_COMPONENTS, SubsystemState.READY, null, COMPONENT_CORE_COMPONENTS)

    /** The hook set was installed. */
    fun hooksInstalled(): ReportOutcome = report(RuntimeSubsystem.ESSENTIAL_HOOKS, SubsystemState.READY, null, COMPONENT_ESSENTIAL_HOOKS)

    /** A hook the module cannot work without failed to install. */
    fun essentialHooksFailed(message: String?): ReportOutcome =
        report(
            RuntimeSubsystem.ESSENTIAL_HOOKS,
            SubsystemState.FAILED,
            RuntimeFailureCode.ESSENTIAL_HOOK_FAILED,
            COMPONENT_ESSENTIAL_HOOKS,
            message,
        )

    /**
     * The optional feature set, from the failure reports collected for this session.
     *
     * A count of failures is used rather than a verdict about features, because "six of nine
     * optional features installed" is actionable in a way that "degraded" is not - and the
     * count is already recorded elsewhere, so reading it here adds no new claim.
     */
    fun optionalFeaturesInstalled(reports: List<FeatureFailureReport>): ReportOutcome =
        if (reports.isEmpty()) {
            report(RuntimeSubsystem.OPTIONAL_HOOKS, SubsystemState.READY, null, COMPONENT_OPTIONAL_HOOKS)
        } else {
            report(
                RuntimeSubsystem.OPTIONAL_HOOKS,
                SubsystemState.DEGRADED,
                RuntimeFailureCode.OPTIONAL_FEATURE_FAILED,
                COMPONENT_OPTIONAL_HOOKS,
                "${reports.size} optional feature(s) failed",
            )
        }

    /**
     * Resolution finished for this session.
     *
     * Only resolution-class failures move the resolver subsystem. A feature that failed to
     * construct is not a resolver failure, and reporting it as one is the attribution bug this
     * whole model exists to prevent.
     */
    fun resolversFinished(reports: List<FeatureFailureReport>): ReportOutcome =
        if (reports.none { it.isResolverFailure() }) {
            report(RuntimeSubsystem.RESOLVER, SubsystemState.READY, null, COMPONENT_RESOLVER)
        } else {
            report(RuntimeSubsystem.RESOLVER, SubsystemState.DEGRADED, RuntimeFailureCode.RESOLVER_FAILED, COMPONENT_RESOLVER)
        }

    /**
     * The heartbeat for this process right now.
     *
     * Null when the runtime has nothing to say yet - which, after entry, it always has, because
     * [frameworkAnswered] alone is already evidence. The null case is a runtime with no
     * reporter at all, and publishing nothing is better than publishing a placeholder.
     */
    fun heartbeat(stage: String = ActivationHeartbeatFactory.STAGE_ENTRY): TargetHeartbeat? =
        ActivationHeartbeatFactory.from(health.snapshot(), stage)

    /**
     * The stage this runtime is in.
     *
     * Read out of the events the reporter already keeps rather than tracked in a new field: the
     * most recent stage that started and has not finished is what the runtime is doing, and a
     * separate variable would be a second place for that answer to live and disagree.
     */
    fun currentStage(): String =
        health
            .events()
            .lastOrNull { it.status == HealthEventStatus.START }
            ?.componentId
            ?: health.events().lastOrNull()?.componentId
            ?: ActivationHeartbeatFactory.STAGE_ENTRY

    private fun report(
        subsystem: RuntimeSubsystem,
        state: SubsystemState,
        code: RuntimeFailureCode?,
        componentId: String,
        message: String? = null,
    ): ReportOutcome {
        val stage = health.begin(subsystem, componentId)
        return when (state) {
            SubsystemState.READY -> {
                health.succeed(stage, message)
                ReportOutcome.RECORDED
            }

            SubsystemState.DEGRADED -> {
                health.degrade(stage, code ?: RuntimeFailureCode.UNKNOWN, message)
                ReportOutcome.RECORDED
            }

            SubsystemState.FAILED -> {
                health.fail(stage, code ?: RuntimeFailureCode.UNKNOWN, message)
                ReportOutcome.RECORDED
            }

            else -> {
                ReportOutcome.REFUSED
            }
        }
    }

    /** What a call to this class achieved. */
    enum class ReportOutcome {
        /** The subsystem's state was recorded. */
        RECORDED,

        /** The requested state was not something a stage completion can express. */
        REFUSED,
    }

    private companion object {
        const val COMPONENT_FRAMEWORK = "framework.entry"
        const val COMPONENT_MODULE = "module.load"
        const val COMPONENT_SCOPE = "scope.effective"
        const val COMPONENT_TARGET_PROCESS = "target.process"
        const val COMPONENT_INJECTION = "injection.entry"
        const val COMPONENT_PREFERENCES = "preferences.read"
        const val COMPONENT_CORE_COMPONENTS = "core.components"
        const val COMPONENT_ESSENTIAL_HOOKS = "hooks.essential"
        const val COMPONENT_OPTIONAL_HOOKS = "hooks.optional"
        const val COMPONENT_RESOLVER = "resolver.session"
    }
}

/**
 * Whether this report describes resolution rather than installation.
 *
 * A feature that failed to build is not a resolver failure, and the two are separated here so
 * the resolver subsystem cannot absorb failures that belong to the features.
 */
internal fun FeatureFailureReport.isResolverFailure(): Boolean =
    code == FailureCode.RESOLVER_INIT_FAILED ||
        code == FailureCode.RESOLVER_AMBIGUOUS ||
        code == FailureCode.RESOLVER_NOT_FOUND
