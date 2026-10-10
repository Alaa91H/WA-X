package com.wax.module.diagnostics.selftest

import com.wax.module.diagnostics.ReportRedactor

/**
 * Redaction for exported diagnostics (#170).
 *
 * Reuses the existing [ReportRedactor] rather than forking a second redactor,
 * and adds the export-specific rules that the failure-report path does not
 * need: filesystem paths and anything that looks like a credential.
 *
 * The output is a *report* of what was removed, not just the cleaned text, so
 * a user can see what the export will contain before they confirm it.
 */
class ExportRedactor {
    private val pathPattern = Regex("(/[A-Za-z0-9_.\\-]+){2,}/?")
    private val tokenPattern = Regex(
        "(?i)\\b(token|secret|password|passwd|api[_-]?key|auth|bearer)\\b\\s*[=:]\\s*\\S+",
    )
    private val dataPathPattern = Regex("(?i)\\b(data/data|shared_prefs|databases|files)/\\S+")

    data class RedactionReport(
        val jidsRedacted: Int,
        val numbersRedacted: Int,
        val messageLikeRedacted: Int,
        val tokensRedacted: Int,
        val pathsRedacted: Int,
    ) {
        val total: Int
            get() = jidsRedacted + numbersRedacted + messageLikeRedacted +
                tokensRedacted + pathsRedacted

        fun toJson(): String = buildString {
            append('{')
            append("\"jids\":").append(jidsRedacted).append(',')
            append("\"phone_numbers\":").append(numbersRedacted).append(',')
            append("\"message_like\":").append(messageLikeRedacted).append(',')
            append("\"tokens\":").append(tokensRedacted).append(',')
            append("\"paths\":").append(pathsRedacted)
            append('}')
        }
    }

    data class Redacted(val text: String, val report: RedactionReport)

    /** Cleans one free-form value and accumulates what was removed. */
    fun redact(value: String, state: MutableReportState = MutableReportState()): String {
        var text = value
        text = redactAndCount(pathPattern, text, state)
        text = redactAndCount(dataPathPattern, text, state)
        val tokenCount = tokenPattern.findAll(text).count()
        text = tokenPattern.replace(text, ReportRedactor.PLACEHOLDER)
        state.tokens += tokenCount
        val jids = JID_PATTERN.findAll(text).count()
        text = JID_PATTERN.replace(text, ReportRedactor.PLACEHOLDER)
        state.jids += jids
        // Count before delegating: `ReportRedactor` collapses both phone
        // numbers and message-like payloads, and a redaction report that
        // reported neither would understate what the export removed.
        val numbers = PHONE_PATTERN.findAll(text).count()
        val messageLike = ReportRedactor.redact(text)
        state.numbers += numbers
        state.messages += if (messageLike == text) 0 else 1
        return messageLike
    }

    /** Replaces every match and records how many were removed. */
    private fun redactAndCount(
        pattern: Regex,
        text: String,
        state: MutableReportState,
    ): String {
        val removed = pattern.findAll(text).count()
        state.paths += removed
        return pattern.replace(text, ReportRedactor.PLACEHOLDER)
    }

    fun redactAll(values: Collection<String>): Redacted {
        val state = MutableReportState()
        val cleaned = values.map { redact(it, state) }
        return Redacted(
            cleaned.joinToString("\n"),
            RedactionReport(
                jidsRedacted = state.jids,
                numbersRedacted = state.numbers,
                messageLikeRedacted = state.messages,
                tokensRedacted = state.tokens,
                pathsRedacted = state.paths,
            ),
        )
    }

    /**
     * Redacts archive entries for real, sharing one accumulator across them so
     * `redaction-report.json` describes the whole export and not just one file.
     *
     * There is deliberately no unredacted alternative here: an export that
     * offered one would turn a diagnostics feature into a data-exfiltration
     * path.
     */
    fun redactEntries(
        entries: List<DiagnosticZipExporter.Entry>,
    ): RedactedEntries {
        val state = MutableReportState()
        val hadChecksums = entries.any { it.name == DiagnosticZipExporter.CHECKSUMS_ENTRY }
        val cleaned = entries
            .filter { it.name != DiagnosticZipExporter.CHECKSUMS_ENTRY }
            .map { entry ->
                DiagnosticZipExporter.Entry(
                    entry.name,
                    redact(String(entry.content), state).toByteArray(),
                )
            }
        // Redaction changes the bytes, so the digests are taken from the
        // redacted payload. Keeping the originals would ship an archive that
        // fails its own verification, which is worse than shipping none.
        val withChecksums = if (hadChecksums) {
            cleaned + DiagnosticZipExporter.Entry(
                DiagnosticZipExporter.CHECKSUMS_ENTRY,
                DiagnosticZipExporter().checksums(cleaned),
            )
        } else {
            cleaned
        }
        return RedactedEntries(
            entries = withChecksums,
            report = RedactionReport(
                jidsRedacted = state.jids,
                numbersRedacted = state.numbers,
                messageLikeRedacted = state.messages,
                tokensRedacted = state.tokens,
                pathsRedacted = state.paths,
            ),
        )
    }

    data class RedactedEntries(
        val entries: List<DiagnosticZipExporter.Entry>,
        val report: RedactionReport,
    )

    class MutableReportState(
        var jids: Int = 0,
        var numbers: Int = 0,
        var messages: Int = 0,
        var tokens: Int = 0,
        var paths: Int = 0,
    )

    private companion object {
        /** A JID, i.e. a local part with a known WhatsApp domain. */
        val JID_PATTERN =
            Regex(
                "[A-Za-z0-9_.+-]+@(s\\.whatsapp\\.net|g\\.us|lid|broadcast|newsletter)",
            )

        /** The phone shapes `ReportRedactor` removes, counted for the report. */
        val PHONE_PATTERN = Regex("\\+?\\d[\\d\\s().-]{6,}\\d|\\b\\d{6,}\\b")
    }
}
