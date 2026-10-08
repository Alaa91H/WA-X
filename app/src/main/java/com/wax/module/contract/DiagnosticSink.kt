package com.wax.module.contract

import com.wax.module.diagnostics.FailureCode
import com.wax.module.diagnostics.FeatureFailureReport

/**
 * Records a structured failure against the feature that reported it.
 *
 * This is the one addition the previous contract could not make, and it is additive: today no
 * feature constructs a failure report at all. `FeatureLoader.recordFailure` is the sole producer,
 * and it is private, so a feature that failed had no way to say what it failed at beyond a log
 * line the user cannot reach.
 *
 * Two properties carry over from the report type and are worth stating here because they are the
 * contract:
 *
 * * **Redacted on the way in.** Every string goes through [ReportRedactor] at construction, so a
 *   feature cannot store a contact name or a JID even by accident.
 * * **Never throws.** A diagnostics channel that can fail turns a feature's problem into the
 *   runtime's problem, and the caller is inside a hook where there is nowhere to handle an
 *   exception.
 */
interface DiagnosticSink {
    /**
     * Records a failure and returns the report that was stored.
     *
     * Returning the report rather than `Unit` means a feature can include its own id in a later
     * message without reconstructing it.
     */
    fun report(
        featureId: String,
        code: FailureCode,
        message: String? = null,
        stage: String? = null,
    ): FeatureFailureReport

    /**
     * Records a throwable against this feature.
     *
     * The convenience most features need, because classifying a throwable by hand is how a
     * feature's failure ends up carrying a code that describes something else.
     */
    fun reportThrowable(
        featureId: String,
        throwable: Throwable,
        stage: String? = null,
    ): FeatureFailureReport
}
