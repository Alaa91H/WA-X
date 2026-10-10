package com.wax.module.diagnostics.selftest

/** A report can be exported even when no scan has completed or a scan crashed. */
object DiagnosticScanFallback {
    fun unrun(
        config: DiagnosticEngine.RunConfig,
        nowMillis: Long,
    ): DiagnosticEngine.Report =
        DiagnosticEngine.Report(
            scanId = "not-run-$nowMillis",
            startedAtMillis = nowMillis,
            finishedAtMillis = nowMillis,
            config = config,
            results = emptyList(),
            order = emptyList(),
        )

    fun failed(
        config: DiagnosticEngine.RunConfig,
        reasonCode: String,
        nowMillis: Long,
    ): DiagnosticEngine.Report {
        val check =
            AtomicCheckResult(
                id = "diagnostics.runner",
                title = "Diagnostic scan runner",
                scope = "diagnostics",
                status = DiagnosticStatus.FAIL,
                evidenceLevel = EvidenceLevel.L1_LIFECYCLE,
                expected = "Runner produces a result",
                observedEvidence = "Runner failed: $reasonCode",
                verification = VerificationState.NOT_OBSERVED,
                timestampMillis = nowMillis,
                whatsappBuild = config.whatsappBuild,
                severity = "high",
                confidence = 1.0,
                failureClass = FailureClass.CRASHED,
                remediation = "Retry the scan or export this partial failure report.",
            )
        return DiagnosticEngine.Report(
            scanId = "failed-$nowMillis",
            startedAtMillis = nowMillis,
            finishedAtMillis = nowMillis,
            config = config,
            results = listOf(check),
            order = listOf(check.id),
        )
    }
}
