package com.wax.module.diagnostics

/**
 * Serialises [FeatureFailureReport] to JSON without depending on the Android framework.
 *
 * `org.json` is part of the Android platform and is not usable from a plain unit test,
 * and the report format has to be verifiable in CI. The writer is therefore hand rolled
 * and deliberately small: a fixed key order, no reflection, and escaping limited to what
 * JSON actually requires.
 */
object FailureReportCodec {
    /** Bumped when the emitted shape changes incompatibly. */
    const val SCHEMA_VERSION: Int = 1

    /** Encodes a single report as a JSON object. */
    fun encode(report: FeatureFailureReport): String =
        buildString {
            append('{')
            appendNumberField("schemaVersion", SCHEMA_VERSION.toLong(), first = true)
            appendField("feature", report.featureId)
            appendField("code", report.code.name)
            appendField("moduleVersion", report.moduleVersion)
            appendField("whatsappVersion", report.whatsappVersion)
            appendField("package", report.packageName)
            if (report.resolver != null) appendField("resolver", report.resolver)
            if (report.exceptionClass != null) appendField("exception", report.exceptionClass)
            if (report.message != null) appendField("message", report.message)
            if (report.threadName != null) appendField("thread", report.threadName)
            if (report.timestampMillis > 0L) {
                appendNumberField("timestamp", report.timestampMillis)
            }
            if (report.frames.isNotEmpty()) {
                append(",\"frames\":[")
                report.frames.forEachIndexed { index, frame ->
                    if (index > 0) append(',')
                    appendQuoted(frame)
                }
                append(']')
            }
            append('}')
        }

    /**
     * Encodes a batch as a JSON array, newest last.
     *
     * The array form is what gets persisted, so a reader can process an entire session's
     * failures in one pass.
     */
    fun encodeAll(reports: List<FeatureFailureReport>): String =
        reports.joinToString(prefix = "[", postfix = "]", separator = ",") { encode(it) }

    /** Renders a batch as the shareable plain-text report. */
    fun renderText(reports: List<FeatureFailureReport>): String =
        buildString {
            appendLine("WA X feature failure report (schema $SCHEMA_VERSION)")
            appendLine("reports: ${reports.size}")
            reports.forEachIndexed { index, report ->
                appendLine()
                appendLine("[${index + 1}] ${report.toSummaryLine()}")
                appendLine(report.toDisplayText().prependIndent("  "))
            }
        }

    private fun StringBuilder.appendField(
        name: String,
        value: String,
        first: Boolean = false,
    ) {
        if (!first) append(',')
        appendQuoted(name)
        append(':')
        appendQuoted(value)
    }

    /** Emits a bare JSON number, so consumers can compare without parsing a string. */
    private fun StringBuilder.appendNumberField(
        name: String,
        value: Long,
        first: Boolean = false,
    ) {
        if (!first) append(',')
        appendQuoted(name)
        append(':')
        append(value)
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> {
                    append("\\\"")
                }

                '\\' -> {
                    append("\\\\")
                }

                '\n' -> {
                    append("\\n")
                }

                '\r' -> {
                    append("\\r")
                }

                '\t' -> {
                    append("\\t")
                }

                '\b' -> {
                    append("\\b")
                }

                '\u000C' -> {
                    append("\\f")
                }

                else -> {
                    // Control characters must be escaped; everything else, including
                    // Arabic text, is emitted as-is.
                    if (char < ' ') {
                        append("\\u")
                        append(HEX[(char.code shr 12) and 0xF])
                        append(HEX[(char.code shr 8) and 0xF])
                        append(HEX[(char.code shr 4) and 0xF])
                        append(HEX[char.code and 0xF])
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
