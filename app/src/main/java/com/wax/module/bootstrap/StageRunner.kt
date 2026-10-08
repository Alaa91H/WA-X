package com.wax.module.bootstrap

import com.wax.module.health.HealthReporter
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.RuntimeHealthSnapshot
import com.wax.module.health.SubsystemState

/**
 * Runs the bootstrap stages, in order, once each, and contains whatever any one of them does.
 *
 * The three properties this exists to guarantee, and the reason the previous shape could not:
 *
 * 1. **Deterministic.** The order is [BootstrapStage]'s declaration order, not the order the code
 *    happens to be written in. Two runs perform the same sequence.
 * 2. **Idempotent.** A stage that already has a terminal result is not run again. That is what
 *    makes the two-pass bootstrap possible at all: a stage that needs the target's `Application`
 *    cannot run on the first pass, so the second pass has to be able to start where the first
 *    stopped instead of redoing - or skipping - everything above it.
 * 3. **Isolated.** A stage that throws is recorded as that stage's own failure and nothing more
 *    escapes. The stages that do not depend on it still run.
 *
 * A failure's blast radius comes from [StageCriticality] and from the stage's `dependsOn`, never
 * from a `return` in the middle of a function. The old bootstrap's `if (!engineStarted) return`
 * is exactly that: it stopped everything, including the stages that never needed the engine,
 * and it could not say which ones those were.
 *
 * **Every subsystem is written by exactly one stage.** The runner reports into [HealthReporter],
 * so the health document the Manager reads is assembled from stages that each own one part of it
 * and no two of them write the same one. Two writers for one subsystem is how a subsystem's
 * state becomes a function of which writer ran last.
 */
class StageRunner(
    private val health: HealthReporter,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val results = LinkedHashMap<BootstrapStage, ComponentResult>()
    private var passes = 0

    /**
     * Runs every stage that has not finished, in order, and returns the whole sequence.
     *
     * [execute] is called once per stage that is actually attempted. Its return value says what
     * happened; anything it throws is caught and recorded as the stage's own failure, because a
     * stage that throws must not be able to stop the stages that do not depend on it.
     *
     * [isApplicationAvailable] is asked once per pass, not per stage: it answers "has the
     * target's `Application` been created yet", which is a property of the moment rather than of
     * the stage.
     */
    fun run(
        execute: (BootstrapStage) -> StageOutcome,
        isApplicationAvailable: () -> Boolean,
    ): BootstrapReport =
        synchronized(lock) {
            passes++
            val applicationReady = runCatching { isApplicationAvailable() }.getOrDefault(false)
            val passStart = now()

            for (stage in BootstrapStage.ORDER) {
                val existing = results[stage]
                if (existing != null && existing.outcome.isTerminal) continue

                val blocked = stagesThatBlocked(stage)
                if (blocked.isNotEmpty()) {
                    record(stage, ComponentResult(stage, StageOutcome.SKIPPED, skippedBecause = blocked))
                    // Reported like any other outcome. A skipped stage that never reaches the
                    // health model stays UNKNOWN, and UNKNOWN is indistinguishable from "nobody
                    // has said anything" - which is the state a bootstrap that stopped early
                    // used to leave the whole interface in.
                    report(stage)
                    continue
                }

                if (stage.needsApplication && !applicationReady) {
                    record(stage, ComponentResult(stage, StageOutcome.AWAITING, message = AWAITING_APPLICATION))
                    report(stage)
                    continue
                }

                val startedAt = now()
                val outcome =
                    try {
                        execute(stage)
                    } catch (throwable: Throwable) {
                        // Caught here rather than at the call site because containment is the
                        // runner's whole job. The stage's own code is used: attributing this to
                        // the runtime as a whole is how a feature failure becomes "LSPosed is
                        // disabled".
                        StageOutcome.FAILED
                            .also { XposedLog.of(throwable, stage) }
                    }
                // A failure with no code is the shape of every message this program has removed:
                // something did not work and nothing can say what. The stage declares its own
                // code for exactly this case, so a failure is never recorded without one.
                record(
                    stage,
                    ComponentResult(
                        stage = stage,
                        outcome = outcome,
                        failureCode = if (outcome == StageOutcome.FAILED) stage.failureCode else null,
                        durationMillis = (now() - startedAt).coerceAtLeast(0L),
                    ),
                )
                report(stage)
            }

            BootstrapReport(results.values.toList(), totalMillis = (now() - passStart).coerceAtLeast(0L), pass = passes)
        }

    /** The results recorded so far, for a caller that wants them without running a pass. */
    fun snapshot(): BootstrapReport = synchronized(lock) { BootstrapReport(results.values.toList(), totalMillis = 0L, pass = passes) }

    /** Forgets every recorded result. For tests and for a session that is being replaced. */
    fun reset() {
        synchronized(lock) {
            results.clear()
            passes = 0
        }
    }

    /**
     * The failures that caused [stage] to be skipped, or an empty list when it was not.
     *
     * Transitive, and it returns the *root* cause rather than the immediate blocker. The
     * immediate blocker is usually another skipped stage, so naming it would give every stage
     * below an engine failure the same uninformative reason; the root is the one a reader needs
     * and the one the card shows.
     *
     * A stage that was *degraded* does not block anything. It still worked, and skipping
     * everything downstream of a degradation is how one lost capability turns into no runtime at
     * all.
     */
    private fun stagesThatBlocked(stage: BootstrapStage): List<BootstrapStage> {
        val causes = LinkedHashSet<BootstrapStage>()
        val seen = LinkedHashSet<BootstrapStage>()
        val queue = ArrayDeque(stage.dependsOn)
        while (queue.isNotEmpty()) {
            val dependency = queue.removeFirst()
            if (!seen.add(dependency)) continue
            val outcome = results[dependency]?.outcome
            if (outcome == StageOutcome.FAILED && dependency.criticality.skipsDependents) {
                causes += dependency
                // A cause explains itself; nothing past it is a separate reason.
                continue
            }
            if (outcome == StageOutcome.SKIPPED) {
                queue.addAll(dependency.dependsOn)
            }
        }
        return causes.sortedBy { it.ordinal }
    }

    private fun record(
        stage: BootstrapStage,
        result: ComponentResult,
    ) {
        synchronized(lock) {
            results[stage] = result
        }
    }

    /**
     * Writes this stage's outcome into the health model.
     *
     * The runner is the only writer for the subsystems stages own, which is what makes the
     * aggregate a single derived value rather than the last thing anybody said.
     */
    private fun report(stage: BootstrapStage) {
        val result = synchronized(lock) { results[stage] } ?: return
        val subsystem = stage.subsystem ?: return
        when (result.outcome) {
            StageOutcome.SUCCEEDED -> {
                health.report(subsystem, SubsystemState.READY)
            }

            StageOutcome.DEGRADED -> {
                health.report(
                    subsystem,
                    SubsystemState.DEGRADED,
                    result.failureCode ?: RuntimeFailureCode.UNKNOWN,
                    result.message,
                )
            }

            StageOutcome.FAILED -> {
                health.report(
                    subsystem,
                    SubsystemState.FAILED,
                    result.failureCode ?: stage.failureCode,
                    result.message,
                )
            }

            StageOutcome.SKIPPED -> {
                health.report(subsystem, SubsystemState.SKIPPED, message = SKIPPED_MESSAGE)
            }

            StageOutcome.AWAITING -> {
                health.report(subsystem, SubsystemState.STARTING)
            }
        }
    }

    companion object {
        const val AWAITING_APPLICATION: String = "awaiting the target application"
        const val SKIPPED_MESSAGE: String = "skipped because an earlier stage did not work"
    }
}

/**
 * Where a swallowed throwable goes.
 *
 * An interface rather than a direct `XposedBridge.log` call, for one reason: the runner is
 * tested on a JVM, where the framework's logging class does not exist, and a containment
 * mechanism whose logging cannot be loaded is a containment mechanism that has never been
 * exercised. Production installs an implementation that writes to the framework log.
 */
fun interface BootstrapLogSink {
    fun onStageFailure(
        stage: BootstrapStage,
        throwable: Throwable,
    )

    companion object {
        /** Where stage failures are reported. Replaced in the injected runtime. */
        @Volatile
        var sink: BootstrapLogSink = BootstrapLogSink { _, _ -> }
    }
}

/** Internal alias so the runner reads as one line at the catch site. */
internal object XposedLog {
    fun of(
        throwable: Throwable,
        stage: BootstrapStage,
    ) {
        BootstrapLogSink.sink.onStageFailure(stage, throwable)
    }
}

/** What the runner verified when it reads its own result back. */
val BootstrapReport.isSelfConsistent: Boolean
    get() =
        results.none { result ->
            result.outcome == StageOutcome.FAILED && result.failureCode == null
        } &&
            results.none { result ->
                result.skippedBecause.any { blocker -> byStage[blocker]?.outcome != StageOutcome.FAILED }
            }

/** The subsystems a finished bootstrap has a definitive state for. */
val RuntimeHealthSnapshot.stagesReported: Int
    get() = subsystemStates.values.count { it != SubsystemState.UNKNOWN }
