package com.wax.module.health

/**
 * The parts of the runtime whose state is tracked independently.
 *
 * The whole point of this enum is that these are *separate*. The module has one question
 * the user asks ("is it working?") and many answers, and collapsing them into a single
 * boolean is what produces a screen that says LSPosed is disabled while LSPosed is
 * running, the module is in scope, and DexKit is what actually failed.
 *
 * So there is no `isHealthy` property here, and [OVERALL_RUNTIME] is not a verdict about
 * the others: it is the state of the runtime *as a whole*, computed by [HealthReporter]
 * from these states and never assigned by a component about itself.
 */
enum class RuntimeSubsystem {
    /** The Xposed/LSPosed framework: is it present in this process at all. */
    FRAMEWORK,

    /** The module: loaded, enabled, and the version the framework loaded. */
    MODULE,

    /** Scope: whether this package is in the module's active scope. */
    SCOPE,

    /** The target process: installed, running, and whether it said anything recently. */
    TARGET_PROCESS,

    /** Injection: whether the module's code is actually executing in the target. */
    INJECTION,

    /** Preferences: whether the module's settings could be read in this process. */
    PREFERENCES,

    /** DexKit: the obfuscation-resolving engine's own initialisation. */
    DEXKIT,

    /** Resolvers: whether the features' dependencies could be located on this build. */
    RESOLVER,

    /** The core components the module's shared infrastructure is built on. */
    CORE_COMPONENTS,

    /** Hooks whose failure means the module cannot do its job. */
    ESSENTIAL_HOOKS,

    /** Hooks that may fail without the module being called broken. */
    OPTIONAL_HOOKS,

    /** The runtime as a whole. Computed, never self-reported. */
    OVERALL_RUNTIME,
    ;

    /** Whether this entry is an aggregate rather than a subsystem of its own. */
    val isAggregate: Boolean get() = this == OVERALL_RUNTIME

    companion object {
        /** Every subsystem that reports for itself, in a stable order. */
        val independent: List<RuntimeSubsystem> = entries.filterNot { it.isAggregate }

        /** The subsystems whose states are combined into [OVERALL_RUNTIME]. */
        val aggregateInputs: List<RuntimeSubsystem> = independent
    }
}
