package com.wax.module.bootstrap

import com.wax.module.health.HealthFreshness
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.SubsystemState

/**
 * What one stage did, as a record rather than as a log line.
 *
 * A log line is read once, by the person who happened to be looking. This is the form a card, a
 * diagnostics view and a test can all read, which is what turns "startup failed" into "the
 * resolution engine failed, so the feature set was skipped".
 *
 * Two properties are enforced by construction:
 *
 * * **No free text.** [message] is bounded and redacted by the caller; the claim is [stage] plus
 *   [failureCode] plus [outcome].
 * * **Duration is always present**, including for a stage that never ran. A skipped stage that
 *   reports no time is indistinguishable from one that took no time, and the timing is half of
 *   what this phase is for.
 */
data class ComponentResult(
    /** The stage this describes. */
    val stage: BootstrapStage,
    /** What happened. */
    val outcome: StageOutcome,
    /** Why it failed, when it did. */
    val failureCode: RuntimeFailureCode? = null,
    /** Bounded detail for the diagnostics view. */
    val message: String? = null,
    /** How long it took, or zero when it never ran. */
    val durationMillis: Long = 0L,
    /** The stages whose failure caused this one to be skipped, if that is why. */
    val skippedBecause: List<BootstrapStage> = emptyList(),
) {
    init {
        require(durationMillis >= 0L) { "a duration cannot be negative" }
        require(
            outcome != StageOutcome.FAILED || failureCode != null,
        ) { "a failed stage must name the code that describes how it failed" }
        require(
            !outcome.isImpaired || failureCode == null || failureCode.subsystem == stage.subsystem || stage.subsystem == null,
        ) {
            "${stage.name} reported ${failureCode?.name}, which belongs to another subsystem"
        }
    }

    /** Whether this stage is the reason the runtime is degraded or failed. */
    val isBlocking: Boolean get() = outcome == StageOutcome.FAILED && stage.criticality.canFailBootstrap

    /** The subsystem state this result maps to, or null when it implies none. */
    val subsystemState: SubsystemState?
        get() =
            when (outcome) {
                StageOutcome.SUCCEEDED -> SubsystemState.READY
                StageOutcome.DEGRADED -> SubsystemState.DEGRADED
                StageOutcome.FAILED -> SubsystemState.FAILED
                StageOutcome.SKIPPED -> SubsystemState.SKIPPED
                StageOutcome.AWAITING -> SubsystemState.STARTING
            }
}

/**
 * The state a whole bootstrap ended in.
 *
 * Distinct from [SubsystemState] because this is about the *sequence* rather than about a
 * subsystem, and because the two answer different questions: the aggregate asks "how is the
 * runtime", this asks "did startup finish".
 */
enum class BootstrapState {
    /** Every stage ran and nothing was impaired. */
    READY,

    /** Every stage that could run did, and something was lost on the way. */
    DEGRADED,

    /** A stage the runtime cannot do without did not work. */
    FAILED,

    /** The bootstrap has not finished; stages are still waiting for what they need. */
    IN_PROGRESS,
}

/**
 * The result of a bootstrap pass: every stage, in order, with the totals.
 *
 * The totals are here rather than in a log line because "startup took 40 seconds" and "the
 * resolver cache took 39 of them" are the two numbers a phase needs in order to decide whether
 * the next one is worth doing.
 */
data class BootstrapReport(
    /** Every stage's result, in the order the stages run. */
    val results: List<ComponentResult>,
    /** How long the whole pass took. */
    val totalMillis: Long,
    /** How many passes have produced this report, which is 1 until a later pass adds to it. */
    val pass: Int = 1,
) {
    init {
        require(totalMillis >= 0L) { "a duration cannot be negative" }
    }

    /** Results by stage. */
    val byStage: Map<BootstrapStage, ComponentResult> = results.associateBy { it.stage }

    /** The stages that did not work, in order. */
    fun failed(): List<ComponentResult> = results.filter { it.outcome == StageOutcome.FAILED }

    /** The stages that lost capability, in order. */
    fun degraded(): List<ComponentResult> = results.filter { it.outcome == StageOutcome.DEGRADED }

    /** The stages still waiting for what they need. */
    fun awaiting(): List<ComponentResult> = results.filter { it.outcome == StageOutcome.AWAITING }

    /** The stages never attempted because something they depend on did not work. */
    fun skipped(): List<ComponentResult> = results.filter { it.outcome == StageOutcome.SKIPPED }

    /** The results of every stage with the given criticality, in order. */
    fun withCriticality(criticality: StageCriticality): List<ComponentResult> = results.filter { it.stage.criticality == criticality }

    /** The single worst failure present, or null when nothing failed. */
    val worstFailureCode: RuntimeFailureCode?
        get() = RuntimeFailureCode.mostSevere(results.mapNotNull { it.failureCode })

    /**
     * How the sequence ended.
     *
     * The rule that matters here is the one about optional stages: an [StageCriticality.OPTIONAL]
     * failure can make a bootstrap DEGRADED and can never make it FAILED, can never skip a later
     * stage, and can never stop the runtime being used. That is the whole of M03's gate on that
     * point, and it is a property of the criticality rather than of any individual stage.
     */
    val state: BootstrapState
        get() {
            if (awaiting().isNotEmpty()) return BootstrapState.IN_PROGRESS
            if (failed().any { it.stage.criticality.canFailBootstrap }) return BootstrapState.FAILED
            if (failed().isNotEmpty() || degraded().isNotEmpty()) return BootstrapState.DEGRADED
            return BootstrapState.READY
        }

    /** Whether the runtime finished starting and nothing the module cannot do without is missing. */
    val isReady: Boolean get() = state == BootstrapState.READY || state == BootstrapState.DEGRADED

    /** Whether this report still describes a sequence that has something left to do. */
    val isIncomplete: Boolean get() = awaiting().isNotEmpty()

    /** How long the slowest stage took. */
    val slowestStage: ComponentResult? get() = results.maxByOrNull { it.durationMillis }

    /** The per-stage timings, for the diagnostics view. */
    fun timings(): List<Pair<BootstrapStage, Long>> = results.map { it.stage to it.durationMillis }

    /** A one-line summary that names the stages rather than the process. */
    fun summary(): String {
        val failed = failed()
        return when {
            failed.isEmpty() && degraded().isEmpty() -> "${results.size} stages, nothing impaired, ${totalMillis}ms"
            failed.isEmpty() -> "${results.size} stages, ${degraded().size} degraded, ${totalMillis}ms"
            else -> failed.joinToString(", ") { "${it.stage.name}:${it.failureCode?.name}" } + " (${totalMillis}ms)"
        }
    }

    /** How fresh the report is at [nowMillis]; a bootstrap report older than the stale threshold describes a process that is gone. */
    fun freshnessAt(
        timestampMillis: Long,
        nowMillis: Long,
    ): HealthFreshness = HealthFreshness.at(timestampMillis, nowMillis)
}
