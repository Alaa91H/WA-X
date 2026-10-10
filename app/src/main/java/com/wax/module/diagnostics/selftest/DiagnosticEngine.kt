package com.wax.module.diagnostics.selftest

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the atomic inventory and produces a report that never overstates.
 *
 * Design rules taken directly from #170:
 * - work is bounded, off the caller's thread, with a per-check timeout;
 * - progress is reported as **completed counts**, never invented percentages;
 * - cancellation stops scheduling further checks and releases the executor;
 * - a check that produced no observation can only be reported `NOT_TESTED`,
 *  never `PASS`.
 */
class DiagnosticEngine(
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "wax-diagnostics").apply { isDaemon = true }
        },
) {
    /** One unit of real work, supplied by the Manager side. */
    fun interface Probe {
        /**
         * Returns what was observed, or null when the probe could not observe
         * anything. A null observation must never be turned into a PASS.
         */
        fun run(): Observation?
    }

    data class Observation(
        val evidence: String,
        val level: EvidenceLevel,
        val verification: VerificationState = VerificationState.HOOKED,
        val failureClass: FailureClass = FailureClass.NONE,
        val expectedMatch: Boolean = true,
        val durationMillis: Long = 0L,
        /**
         * Set only when the observed situation does not fit pass or fail at all.
         *
         * A feature switched off in preferences and a feature whose migration is
         * still pending are real observations that are neither a success nor a
         * defect, so the probe states the status directly instead of squeezing
         * them through the pass/fail axis. A `PASS` here still has to survive the
         * same honesty pass as any other result.
         */
        val statusOverride: DiagnosticStatus? = null,
    )

    data class RunConfig(
        val mode: Mode,
        val perCheckTimeoutMillis: Long,
        val whatsappBuild: String,
        val scope: String,
    ) {
        enum class Mode {
            /** Cheap, startup-safe subset. */
            QUICK_CHECK,

            /** Explicit user action: the whole inventory. */
            DEEP_SCAN,
        }

        companion object {
            fun quick(
                whatsappBuild: String,
                scope: String,
            ) = RunConfig(
                Mode.QUICK_CHECK,
                2_000L,
                whatsappBuild,
                scope,
            )

            fun deep(
                whatsappBuild: String,
                scope: String,
            ) = RunConfig(
                Mode.DEEP_SCAN,
                15_000L,
                whatsappBuild,
                scope,
            )
        }
    }

    data class Report(
        val scanId: String,
        val startedAtMillis: Long,
        val finishedAtMillis: Long,
        val config: RunConfig,
        val results: List<AtomicCheckResult>,
        val order: List<String>,
    ) {
        val summary: DiagnosticSummary by lazy {
            DiagnosticSummary(
                total = results.size,
                passed = results.count { it.status == DiagnosticStatus.PASS },
                failed = results.count { it.status == DiagnosticStatus.FAIL },
                blocked = results.count { it.status == DiagnosticStatus.BLOCKED },
                notTested = results.count { it.status == DiagnosticStatus.NOT_TESTED },
                unsupported = results.count { it.status == DiagnosticStatus.UNSUPPORTED },
                needsExternalVerification =
                    results.count {
                        it.status == DiagnosticStatus.NEEDS_EXTERNAL_VERIFICATION
                    },
                clusters = RootCauseClusterer.cluster(results),
            )
        }

        fun firstFailedDependency(): AtomicCheckResult? = RootCauseClusterer.firstFailedDependency(results, order)
    }

    private val completed = AtomicInteger()
    private val cancelled = AtomicBoolean(false)
    private val observed = ConcurrentHashMap<String, AtomicCheckResult>()
    private val progress = CopyOnWriteArrayList<String>()

    fun completedCount(): Int = completed.get()

    fun isCancelled(): Boolean = cancelled.get()

    fun progressSoFar(): List<String> = progress.toList()

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Runs the inventory. [probes] supplies the real work; a missing probe
     * yields `NOT_TESTED`, which is the honest result for "not measured".
     */
    fun run(
        config: RunConfig,
        definitions: List<AtomicCheckInventory.Definition>,
        probes: Map<String, Probe>,
        onProgress: (completed: Int, total: Int, lastId: String) -> Unit = { _, _, _ -> },
    ): Report {
        val scanId = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        completed.set(0)
        cancelled.set(false)
        observed.clear()
        progress.clear()

        val ordered =
            if (config.mode == RunConfig.Mode.DEEP_SCAN) {
                definitions
            } else {
                definitions.filter { it.id in quickChecks }
            }
        val total = ordered.size
        val byId = ordered.associateBy { it.id }

        for (definition in ordered) {
            if (cancelled.get()) break
            val dependencies = definition.dependsOn.mapNotNull { observed[it] }
            val dependencyFailed =
                dependencies.any {
                    it.status == DiagnosticStatus.FAIL || it.status == DiagnosticStatus.BLOCKED
                }
            if (dependencyFailed) {
                // Blocked, not failed: the failure belongs to the dependency
                // and the cluster must not multiply it.
                record(definition, blockedResult(definition, config))
            } else {
                record(definition, runProbe(definition, config, probes[definition.id]))
            }
            completed.incrementAndGet()
            progress.add(definition.id)
            onProgress(completed.get(), total, definition.id)
        }

        val results = ordered.mapNotNull { observed[it.id] }
        // A final honesty pass: no PASS may survive without its observation.
        val honest =
            results.map { result ->
                val corrected = result.honestStatus(results.associateBy { it.id })
                if (corrected == result.status) {
                    result
                } else {
                    result.copy(
                        status = corrected,
                        failureClass =
                            if (corrected == DiagnosticStatus.NOT_TESTED) {
                                FailureClass.NONE
                            } else {
                                FailureClass.DEPENDENCY_MISSING
                            },
                    )
                }
            }
        return Report(
            scanId = scanId,
            startedAtMillis = started,
            finishedAtMillis = System.currentTimeMillis(),
            config = config,
            results = honest,
            order = ordered.map { it.id },
        )
    }

    private fun runProbe(
        definition: AtomicCheckInventory.Definition,
        config: RunConfig,
        probe: Probe?,
    ): AtomicCheckResult {
        val now = System.currentTimeMillis()
        if (probe == null) {
            return AtomicCheckResult(
                id = definition.id,
                title = definition.title,
                scope = definition.scope,
                status = DiagnosticStatus.NOT_TESTED,
                evidenceLevel = definition.level,
                expected = definition.expected,
                observedEvidence = "no probe registered for this check",
                verification = VerificationState.NOT_OBSERVED,
                timestampMillis = now,
                whatsappBuild = config.whatsappBuild,
                severity = definition.severity,
                confidence = 0.0,
                failureClass = FailureClass.NONE,
                remediation = definition.remediation,
                dependsOn = definition.dependsOn,
                externalConfirmationRequired = definition.externalConfirmationRequired,
            )
        }
        val task: Future<Observation?> = executor.submit<Observation?> { probe.run() }
        val observation =
            try {
                task.get(config.perCheckTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (failure: Exception) {
                task.cancel(true)
                // The probe failed rather than the scan: say which, so an export
                // never shows a crashed check as merely a slow one.
                val timedOut = failure is TimeoutException
                return AtomicCheckResult(
                    id = definition.id,
                    title = definition.title,
                    scope = definition.scope,
                    status = DiagnosticStatus.FAIL,
                    evidenceLevel = definition.level,
                    expected = definition.expected,
                    observedEvidence =
                        if (timedOut) {
                            "probe exceeded ${config.perCheckTimeoutMillis}ms"
                        } else {
                            "probe failed: ${failure.javaClass.simpleName}"
                        },
                    verification = VerificationState.NOT_OBSERVED,
                    timestampMillis = System.currentTimeMillis(),
                    whatsappBuild = config.whatsappBuild,
                    severity = definition.severity,
                    confidence = 1.0,
                    failureClass = if (timedOut) FailureClass.TIMEOUT else FailureClass.CRASHED,
                    remediation = definition.remediation,
                    dependsOn = definition.dependsOn,
                    externalConfirmationRequired = definition.externalConfirmationRequired,
                )
            }
        if (observation == null) {
            // A probe that could not observe anything is NOT evidence of success.
            return AtomicCheckResult(
                id = definition.id,
                title = definition.title,
                scope = definition.scope,
                status =
                    if (definition.externalConfirmationRequired) {
                        DiagnosticStatus.NEEDS_EXTERNAL_VERIFICATION
                    } else {
                        DiagnosticStatus.NOT_TESTED
                    },
                evidenceLevel = definition.level,
                expected = definition.expected,
                observedEvidence = "",
                verification = VerificationState.NOT_OBSERVED,
                timestampMillis = now,
                whatsappBuild = config.whatsappBuild,
                severity = definition.severity,
                confidence = 0.0,
                failureClass = FailureClass.NONE,
                remediation = definition.remediation,
                dependsOn = definition.dependsOn,
                externalConfirmationRequired = definition.externalConfirmationRequired,
            )
        }
        // A check whose expectation held and that only reached hook
        // registration is not a pass when it still needs a second account.
        val needsExternal =
            definition.externalConfirmationRequired &&
                observation.verification != VerificationState.EXTERNALLY_VERIFIED
        val candidateStatus =
            observation.statusOverride
                ?: when {
                    !observation.expectedMatch -> DiagnosticStatus.FAIL
                    needsExternal -> DiagnosticStatus.NEEDS_EXTERNAL_VERIFICATION
                    else -> DiagnosticStatus.PASS
                }
        // An explicit statusOverride=PASS must not bypass the external peer
        // proof requirement. A triggered callback is not sender-visible proof.
        val status =
            if (needsExternal && candidateStatus == DiagnosticStatus.PASS) {
                DiagnosticStatus.NEEDS_EXTERNAL_VERIFICATION
            } else {
                candidateStatus
            }
        return AtomicCheckResult(
            id = definition.id,
            title = definition.title,
            scope = definition.scope,
            status = status,
            evidenceLevel = observation.level,
            expected = definition.expected,
            observedEvidence = observation.evidence,
            verification = observation.verification,
            timestampMillis = now,
            whatsappBuild = config.whatsappBuild,
            severity = definition.severity,
            confidence = if (status == DiagnosticStatus.PASS) 1.0 else 0.5,
            failureClass =
                if (status == DiagnosticStatus.FAIL) {
                    observation.failureClass
                } else {
                    FailureClass.NONE
                },
            remediation = definition.remediation,
            durationMillis = observation.durationMillis,
            dependsOn = definition.dependsOn,
            externalConfirmationRequired = definition.externalConfirmationRequired,
        )
    }

    /**
     * The honest result for a check whose dependency did not pass: it names the
     * dependants it could not run, and carries no confidence of its own.
     */
    private fun blockedResult(
        definition: AtomicCheckInventory.Definition,
        config: RunConfig,
    ): AtomicCheckResult {
        val blocking =
            definition.dependsOn.filter { id ->
                val status = observed[id]?.status
                status == DiagnosticStatus.FAIL || status == DiagnosticStatus.BLOCKED
            }
        return AtomicCheckResult(
            id = definition.id,
            title = definition.title,
            scope = definition.scope,
            status = DiagnosticStatus.BLOCKED,
            evidenceLevel = definition.level,
            expected = definition.expected,
            observedEvidence = "blocked by " + blocking.joinToString(", "),
            verification = VerificationState.NOT_OBSERVED,
            timestampMillis = System.currentTimeMillis(),
            whatsappBuild = config.whatsappBuild,
            severity = definition.severity,
            confidence = 1.0,
            failureClass = FailureClass.DEPENDENCY_MISSING,
            remediation = definition.remediation,
            dependsOn = definition.dependsOn,
            externalConfirmationRequired = definition.externalConfirmationRequired,
        )
    }

    private fun record(
        definition: AtomicCheckInventory.Definition,
        result: AtomicCheckResult,
    ) {
        observed[definition.id] = result
    }

    /** Releases the worker; a scan outliving the screen would leak a thread. */
    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    /**
     * Quick Check deliberately covers only the cheap, startup-safe prefix, so
     * it can run on demand without a full DEX scan.
     *
     * [run] enforces that prefix itself in quick mode, so no call site can
     * accidentally start a DEX walk on the cheap path.
     */
    fun quickSubset(): List<AtomicCheckInventory.Definition> = AtomicCheckInventory.PIPELINE.filter { it.id in quickChecks }

    /** Cheap, startup-safe checks: what Quick Check may run on demand. */
    private val quickChecks =
        setOf(
            AtomicCheckInventory.ENV_ANDROID,
            AtomicCheckInventory.ENV_TARGET,
            AtomicCheckInventory.ENV_SCOPE,
            AtomicCheckInventory.FRAMEWORK_API102,
            AtomicCheckInventory.MODULE_LOADED,
            AtomicCheckInventory.APP_ATTACH,
            AtomicCheckInventory.MANAGER_IPC,
            AtomicCheckInventory.DEXKIT_NATIVE,
        )
}
