package com.wmods.wppenhacer.diagnostics

/**
 * A structured record of one feature failure.
 *
 * Everything in here is safe to show to a user and safe to attach to a bug report: the
 * free-text fields are redacted by [ReportRedactor] at construction time, so a report
 * cannot be built containing an identifier by accident.
 *
 * The field set answers the three questions the module needs when a feature breaks —
 * which feature, which resolver, which WhatsApp build — plus enough build context to
 * reproduce, and nothing that identifies the person using the module.
 */
data class FeatureFailureReport(
    val featureId: String,
    val code: FailureCode,
    val moduleVersion: String,
    val whatsappVersion: String,
    val packageName: String,
    val resolver: String? = null,
    val exceptionClass: String? = null,
    val message: String? = null,
    val frames: List<String> = emptyList(),
    val timestampMillis: Long = 0L,
    val threadName: String? = null,
) {
    /** Short single-line form used in logs and in the error dialog. */
    fun toSummaryLine(): String =
        buildString {
            append(featureId)
            append(' ')
            append(code.name)
            if (resolver != null) {
                append(" resolver=")
                append(resolver)
            }
            append(" wa=")
            append(whatsappVersion)
            append(" wae=")
            append(moduleVersion)
        }

    /**
     * Multi-line human readable form.
     *
     * Used by the crash screen and the diagnostics dialog. Still redacted: the strings it
     * prints come from fields that were redacted on construction.
     */
    fun toDisplayText(): String =
        buildString {
            appendLine("feature: $featureId")
            appendLine("code: ${code.name}")
            appendLine("module: $moduleVersion")
            appendLine("whatsapp: $whatsappVersion")
            appendLine("package: $packageName")
            if (resolver != null) appendLine("resolver: $resolver")
            if (threadName != null) appendLine("thread: $threadName")
            if (exceptionClass != null) appendLine("exception: $exceptionClass")
            if (!message.isNullOrBlank()) appendLine("message: $message")
            if (frames.isNotEmpty()) {
                appendLine("frames:")
                frames.forEach { append("  ").appendLine(it) }
            }
            if (timestampMillis > 0L) appendLine("at: $timestampMillis")
        }

    companion object {
        /**
         * Builds a report from a throwable, redacting every free-text field.
         *
         * @param featureId the feature that failed; use a stable id, not a display label
         * @param resolver the resolver that was being satisfied, when known
         * @param stage a short hint such as `resolve` or `hook`, used to sharpen the code
         */
        fun fromThrowable(
            featureId: String,
            throwable: Throwable,
            moduleVersion: String,
            whatsappVersion: String,
            packageName: String,
            resolver: String? = null,
            stage: String? = null,
            timestampMillis: Long = 0L,
            threadName: String? = null,
        ): FeatureFailureReport =
            FeatureFailureReport(
                featureId = featureId,
                code = FailureCode.classify(throwable, stage),
                moduleVersion = ReportRedactor.redactAndBound(moduleVersion),
                whatsappVersion = ReportRedactor.redactAndBound(whatsappVersion),
                packageName = ReportRedactor.redactAndBound(packageName),
                resolver = resolver?.let { ReportRedactor.redactAndBound(it) },
                exceptionClass = ReportRedactor.redactAndBound(throwable.javaClass.name),
                message = ReportRedactor.redactAndBound(throwable.message),
                frames = ReportRedactor.summariseStackTrace(throwable),
                timestampMillis = timestampMillis,
                threadName = threadName?.let { ReportRedactor.redactAndBound(it) },
            )
    }
}
