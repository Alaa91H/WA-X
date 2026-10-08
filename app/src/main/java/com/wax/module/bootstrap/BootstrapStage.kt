package com.wax.module.bootstrap

import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.RuntimeSubsystem

/**
 * How much of the module a stage takes with it when it does not work.
 *
 * The classification is the whole point of the stage runner. Before it, a failure anywhere in
 * startup meant the same thing: `start()` returned, nothing was installed, and the interface
 * learned about it from a different process. After it, a failure means a known amount, and
 * three different treatments follow from three different answers:
 *
 * | | Failure effect | Bootstrap result |
 * | --- | --- | --- |
 * | [CORE] | dependent stages are skipped | FAILED |
 * | [ESSENTIAL] | dependent stages are skipped | DEGRADED |
 * | [OPTIONAL] | nothing else is affected | DEGRADED |
 * | [EXPERIMENTAL] | nothing else is affected | DEGRADED |
 *
 * [OPTIONAL] and [EXPERIMENTAL] differ in whether the stage is attempted at all, which is a
 * decision made before the stage runs rather than a consequence of its outcome.
 *
 * **CORE is the classification that needs justifying**, because it is the only one that stops
 * the runtime. A stage may only be [CORE] if [BootstrapStage.justification] says why nothing
 * below it can work without it - and `StageRunnerTest.aCoreStageWithoutAJustificationIsRefused`
 * fails if one does. That test is the reason this enum exists as a table rather than as a
 * boolean somebody passes in: a classification that can be asserted without reading the stage's
 * reason will eventually be asserted without it.
 */
enum class StageCriticality {
    /** Without this stage nothing below it works, and the runtime is not usable. */
    CORE,

    /** Without this stage the module cannot do its job, but the parts that do not need it run. */
    ESSENTIAL,

    /** Its failure can never stop another stage and never makes the runtime unusable. */
    OPTIONAL,

    /** Not attempted unless something explicitly asks for it. */
    EXPERIMENTAL,
    ;

    /** Whether a failure in a stage with this criticality skips the stages that depend on it. */
    val skipsDependents: Boolean get() = this == CORE || this == ESSENTIAL

    /** Whether a failure in a stage with this criticality can make the bootstrap FAILED. */
    val canFailBootstrap: Boolean get() = this == CORE

    /**
     * Whether a stage with this criticality may be classified without saying why.
     *
     * [CORE] may not. The other three do not need a reason because they cannot stop anything,
     * so the justification would be a comment rather than an argument.
     */
    val requiresJustification: Boolean get() = this == CORE
}

/**
 * What happened when a stage ran.
 *
 * [AWAITING] is not a failure and not a success, and it is the outcome that makes an idempotent
 * two-pass bootstrap honest: a stage that needs the target's `Application` has nothing to run
 * against on the first pass, and recording that as a failure would report a broken module for a
 * process that has not finished starting.
 */
enum class StageOutcome {
    /** Completed as intended. */
    SUCCEEDED,

    /** Completed, with reduced capability - through a fallback or with a known loss. */
    DEGRADED,

    /** Attempted and did not work. */
    FAILED,

    /** Not attempted because a stage it depends on did not work. */
    SKIPPED,

    /** Not attempted yet because what it needs has not happened. */
    AWAITING,
    ;

    /** Whether this outcome is final, so a later pass must not run the stage again. */
    val isTerminal: Boolean get() = this != AWAITING

    /** Whether this outcome means something did not work. */
    val isImpaired: Boolean get() = this == DEGRADED || this == FAILED
}

/**
 * One step of startup, in the order the runtime performs it.
 *
 * The order is declaration order and it is the whole specification of the bootstrap: a reader
 * should be able to learn what happens when by reading this file top to bottom.
 *
 * A stage names the [subsystem] it reports on, or `null` when it has none. Two stages never
 * write the same subsystem, so "which stage produced this state" has one answer rather than
 * two, and the aggregate the interface reads is assembled from stages that each own exactly one
 * part of it.
 */
enum class BootstrapStage(
    /** The subsystem this stage reports on, or null for a stage that owns no subsystem. */
    val subsystem: RuntimeSubsystem?,
    /** How much of the module its failure takes with it. */
    val criticality: StageCriticality,
    /** The failure this stage reports when it does not work. */
    val failureCode: RuntimeFailureCode,
    /** The stages that must have worked before this one is worth attempting. */
    val dependsOn: Set<BootstrapStage>,
    /** Why this stage is [StageCriticality.CORE]; required for a CORE stage, absent otherwise. */
    val justification: String? = null,
    /** Whether this stage needs the target's `Application`, which does not exist on the first pass. */
    val requiresApplication: Boolean = false,
) {
    /** The framework invoked the module's entry point in this process. */
    FRAMEWORK(
        RuntimeSubsystem.FRAMEWORK,
        StageCriticality.CORE,
        RuntimeFailureCode.FRAMEWORK_UNAVAILABLE,
        emptySet(),
        "No framework called us, so no code below this point is running.",
    ),

    /** The module is loaded and executing, at the version the framework loaded. */
    MODULE(
        RuntimeSubsystem.MODULE,
        StageCriticality.CORE,
        RuntimeFailureCode.MODULE_DISABLED,
        setOf(FRAMEWORK),
        "Without a loaded module there is nothing for any other stage to do.",
    ),

    /** The framework decided to load the module into this package: the effective scope. */
    SCOPE(
        RuntimeSubsystem.SCOPE,
        StageCriticality.CORE,
        RuntimeFailureCode.SCOPE_MISSING,
        setOf(FRAMEWORK),
        "Reaching this stage at all means the framework placed us in this package. No stage below can be reached another way.",
    ),

    /** We are inside the target's own process. */
    TARGET(
        RuntimeSubsystem.TARGET_PROCESS,
        StageCriticality.CORE,
        RuntimeFailureCode.TARGET_NOT_RUNNING,
        setOf(FRAMEWORK),
        "Every feature below hooks classes in this process. There is no other process to work in.",
    ),

    /** The module's code is executing here, not merely loaded. */
    INJECTION(
        RuntimeSubsystem.INJECTION,
        StageCriticality.CORE,
        RuntimeFailureCode.INJECTION_FAILED,
        setOf(TARGET),
        "Injection is what the module is for; a stage below it that succeeds without it is describing something else.",
    ),

    /** The target's settings were read. */
    PREFERENCES(
        RuntimeSubsystem.PREFERENCES,
        StageCriticality.ESSENTIAL,
        RuntimeFailureCode.PREFERENCES_UNAVAILABLE,
        setOf(INJECTION),
        null,
        requiresApplication = true,
    ),

    /** Storage, the crash handler and the lifecycle callbacks are attached to the target. */
    APPLICATION_ATTACH(
        null,
        StageCriticality.CORE,
        RuntimeFailureCode.CORE_COMPONENT_FAILED,
        setOf(INJECTION),
        "Every stage below reads the target's context or writes through it. Nothing below works without one.",
    ),

    /** The obfuscation-resolving engine started. */
    DEX_ENGINE(
        RuntimeSubsystem.DEXKIT,
        StageCriticality.CORE,
        RuntimeFailureCode.DEXKIT_INIT_FAILED,
        setOf(APPLICATION_ATTACH),
        "Resolution is how every feature finds its dependencies, so no feature below it can install without the engine.",
    ),

    /** The resolver caches are warm and know which builds they were derived on. */
    RESOLVER_CACHE(
        RuntimeSubsystem.RESOLVER,
        StageCriticality.ESSENTIAL,
        RuntimeFailureCode.RESOLVER_FAILED,
        setOf(DEX_ENGINE),
        null,
        requiresApplication = true,
    ),

    /**
     * The shared infrastructure the features are built on.
     *
     * Depends on the engine as well as on the two stages before it, because the shared
     * components resolve members through it. That dependency is what makes an engine failure
     * skip this stage instead of letting it run and fail on its own - which would produce a
     * second, misleading failure for a consequence of the first.
     */
    CORE(
        RuntimeSubsystem.CORE_COMPONENTS,
        StageCriticality.CORE,
        RuntimeFailureCode.CORE_COMPONENT_FAILED,
        setOf(PREFERENCES, APPLICATION_ATTACH, DEX_ENGINE),
        "The features hold references to these components; without them they cannot even be constructed.",
    ),

    /** The hooks the module cannot work without. */
    ESSENTIAL(
        RuntimeSubsystem.ESSENTIAL_HOOKS,
        StageCriticality.ESSENTIAL,
        RuntimeFailureCode.ESSENTIAL_HOOK_FAILED,
        setOf(CORE, RESOLVER_CACHE),
        null,
        requiresApplication = true,
    ),

    /** The hooks that may fail without the module being called broken. */
    OPTIONAL(
        RuntimeSubsystem.OPTIONAL_HOOKS,
        StageCriticality.OPTIONAL,
        RuntimeFailureCode.OPTIONAL_FEATURE_FAILED,
        setOf(ESSENTIAL),
        null,
        requiresApplication = true,
    ),

    /** The finished runtime is read back and its own aggregate computed. */
    RUNTIME_VERIFICATION(
        null,
        StageCriticality.CORE,
        RuntimeFailureCode.RUNTIME_TIMEOUT,
        setOf(OPTIONAL),
        "A bootstrap nobody has read back has not been shown to work; this is where it is read back.",
        requiresApplication = true,
    ),

    /** The last stage. Records the end of the bootstrap and nothing else. */
    READY(
        null,
        StageCriticality.CORE,
        RuntimeFailureCode.UNKNOWN,
        setOf(RUNTIME_VERIFICATION),
        "This stage exists to end the sequence. It computes nothing and fails nothing.",
    ),
    ;

    /** Whether this stage needs the target's `Application`. */
    val needsApplication: Boolean get() = requiresApplication

    companion object {
        /** The order the runtime performs the stages in: declaration order. */
        val ORDER: List<BootstrapStage> = entries.toList()

        /** Every stage as a stage is defined, in order. */
        fun ordered(): List<BootstrapStage> = ORDER
    }
}
