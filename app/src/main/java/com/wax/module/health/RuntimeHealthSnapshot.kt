package com.wax.module.health

/**
 * Everything the runtime knows about itself in one process at one moment.
 *
 * The field set is the modernization program's [M01.02] list, and its shape is the point:
 * every subsystem is present as its own state, alongside the identity of the session that
 * produced them. There is deliberately no `isHealthy` boolean, because the question the
 * banner asks ("is this working?") and the question the diagnostics need ("which part is
 * not?") cannot be answered by the same value.
 *
 * The named per-subsystem accessors exist because those are the states the program names
 * explicitly; the map behind them covers every subsystem, so a state added later cannot be
 * silently absent.
 */
data class RuntimeHealthSnapshot(
    val bootId: String,
    val moduleSessionId: String,
    val targetSessionId: String,
    val timestampMillis: Long,
    val packageName: String,
    val processName: String,
    val pid: Int,
    val moduleVersion: String,
    val targetVersionName: String? = null,
    val targetVersionCode: Long? = null,
    val androidSdk: Int = 0,
    private val subsystems: Map<RuntimeSubsystem, SubsystemState> = emptyMap(),
    val featureSummary: FeatureSummary = FeatureSummary(),
    val overallState: SubsystemState = SubsystemState.UNKNOWN,
    val failureCode: RuntimeFailureCode? = null,
    val failureMessage: String? = null,
) {
    /** The session identities this snapshot was produced in. */
    val sessions: RuntimeSessions
        get() = RuntimeSessions(bootId, moduleSessionId, targetSessionId)

    /**
     * The key that separates one target's health from another's.
     *
     * WhatsApp and WhatsApp Business are different applications, and a snapshot that
     * cannot say which one it describes is worse than no snapshot: it invites the state of
     * one to be shown as the state of the other.
     */
    val targetKey: String get() = "$packageName|$processName"

    /** The state of [subsystem], or [SubsystemState.UNKNOWN] when nothing reported. */
    fun stateOf(subsystem: RuntimeSubsystem): SubsystemState = subsystems[subsystem] ?: SubsystemState.UNKNOWN

    /** Every subsystem state, always including an entry for each independent subsystem. */
    val subsystemStates: Map<RuntimeSubsystem, SubsystemState>
        get() = RuntimeSubsystem.independent.associateWith { stateOf(it) }

    /** How old this snapshot is when read at [nowMillis]. */
    fun ageMillis(nowMillis: Long): Long = nowMillis - timestampMillis

    /** How trustworthy this snapshot still is when read at [nowMillis]. */
    fun freshness(nowMillis: Long): HealthFreshness = HealthFreshness.at(timestampMillis, nowMillis)

    /** Whether a report this old should be treated as describing a runtime that is gone. */
    fun isExpired(nowMillis: Long): Boolean = freshness(nowMillis) == HealthFreshness.EXPIRED

    val frameworkState: SubsystemState get() = stateOf(RuntimeSubsystem.FRAMEWORK)

    val moduleState: SubsystemState get() = stateOf(RuntimeSubsystem.MODULE)

    val scopeState: SubsystemState get() = stateOf(RuntimeSubsystem.SCOPE)

    val targetProcessState: SubsystemState get() = stateOf(RuntimeSubsystem.TARGET_PROCESS)

    val injectionState: SubsystemState get() = stateOf(RuntimeSubsystem.INJECTION)

    val preferencesState: SubsystemState get() = stateOf(RuntimeSubsystem.PREFERENCES)

    val dexKitState: SubsystemState get() = stateOf(RuntimeSubsystem.DEXKIT)

    val resolverState: SubsystemState get() = stateOf(RuntimeSubsystem.RESOLVER)

    val coreComponentState: SubsystemState get() = stateOf(RuntimeSubsystem.CORE_COMPONENTS)

    val essentialHookState: SubsystemState get() = stateOf(RuntimeSubsystem.ESSENTIAL_HOOKS)

    val optionalHookState: SubsystemState get() = stateOf(RuntimeSubsystem.OPTIONAL_HOOKS)

    companion object {
        /** The subsystems in the order a snapshot records them. */
        val RECORDED_SUBSYSTEMS: List<RuntimeSubsystem> = RuntimeSubsystem.independent

        /**
         * Builds a snapshot, filling in every subsystem that did not report.
         *
         * A missing entry becomes [SubsystemState.UNKNOWN] rather than being absent, so a
         * reader never has to decide what "no row" means.
         */
        fun of(
            sessions: RuntimeSessions,
            timestampMillis: Long,
            packageName: String,
            processName: String,
            pid: Int,
            moduleVersion: String,
            states: Map<RuntimeSubsystem, SubsystemState>,
            targetVersionName: String? = null,
            targetVersionCode: Long? = null,
            androidSdk: Int = 0,
            featureSummary: FeatureSummary = FeatureSummary(),
            overallState: SubsystemState = SubsystemState.UNKNOWN,
            failureCode: RuntimeFailureCode? = null,
            failureMessage: String? = null,
        ): RuntimeHealthSnapshot =
            RuntimeHealthSnapshot(
                bootId = sessions.bootId,
                moduleSessionId = sessions.moduleSessionId,
                targetSessionId = sessions.targetSessionId,
                timestampMillis = timestampMillis,
                packageName = packageName,
                processName = processName,
                pid = pid,
                moduleVersion = moduleVersion,
                targetVersionName = targetVersionName,
                targetVersionCode = targetVersionCode,
                androidSdk = androidSdk,
                subsystems = RECORDED_SUBSYSTEMS.associateWith { states[it] ?: SubsystemState.UNKNOWN },
                featureSummary = featureSummary,
                overallState = overallState,
                failureCode = failureCode,
                failureMessage = failureMessage,
            )
    }
}

/**
 * Counts of what the runtime tried to install, for the snapshot's summary line.
 *
 * These are counts and not a state: "seven of nine optional features installed" is
 * actionable in a way that "degraded" is not.
 */
data class FeatureSummary(
    val essential: Int = 0,
    val optional: Int = 0,
    val ready: Int = 0,
    val degraded: Int = 0,
    val failed: Int = 0,
    val notAttempted: Int = 0,
) {
    /** Every feature the summary accounts for. */
    val total: Int get() = essential + optional

    /** The features that are running in some form. */
    val running: Int get() = ready + degraded

    /** Whether anything has been recorded at all. */
    val isEmpty: Boolean
        get() = total == 0 && ready == 0 && degraded == 0 && failed == 0 && notAttempted == 0

    /** Whether every optional feature failed, which is different from one failing. */
    val allOptionalFailed: Boolean get() = optional > 0 && failed >= optional
}
