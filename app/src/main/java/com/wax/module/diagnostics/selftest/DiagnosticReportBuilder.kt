package com.wax.module.diagnostics.selftest

import com.wax.module.diagnostics.selftest.appendQuoted
import com.wax.module.diagnostics.selftest.jsonArray

/**
 * Assembles the exact file set the issue's ZIP contract requires.
 *
 * Anything the Manager could not collect is written into the manifest as a
 * declared missing source. It is never replaced with a fabricated log, and
 * there is deliberately no code path that invents a `logs/sanitized-runtime.log`.
 */
object DiagnosticReportBuilder {
    data class Environment(
        val appVersion: String,
        val appBuildSha: String,
        /** Kept separate from the commit: the two identify different things. */
        val appVersionCode: Long,
        val whatsappPackage: String,
        val whatsappVersion: String,
        val androidVersion: String,
        val androidSdk: Int,
        val abi: String,
    )

    data class Inputs(
        val report: DiagnosticEngine.Report,
        val environment: Environment,
        val hooks: List<String>,
        val resolverStates: Map<String, String>,
        val sanitizedLog: String?,
    )

    fun entries(inputs: Inputs): List<DiagnosticZipExporter.Entry> {
        val report = inputs.report
        val summary = report.summary
        val declaredMissing =
            buildList {
                if (inputs.sanitizedLog.isNullOrBlank()) add("logs/sanitized-runtime.log")
                if (inputs.hooks.isEmpty()) add("hooks.json")
                if (inputs.resolverStates.isEmpty()) add("resolvers.json")
            }
        val entries = mutableListOf<DiagnosticZipExporter.Entry>()
        entries += entry("manifest.json", manifest(inputs, summary, declaredMissing))
        entries += entry("summary.md", summaryMarkdown(report, summary, declaredMissing))
        entries += entry("results.json", resultsJson(report))
        entries += entry("environment.json", environmentJson(inputs.environment))
        entries += entry("hooks.json", hooksJson(inputs.hooks))
        entries += entry("resolvers.json", resolversJson(inputs.resolverStates))
        entries += entry("errors.json", errorsJson(report))
        entries += entry("timings.json", timingsJson(report))
        entries += entry("redaction-report.json", redactionJson(report))
        // Per-feature drilldown, as the contract's optional tests/*.json.
        for (result in report.results.filter { it.scope.startsWith("feature:") }) {
            val id = result.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            entries += entry("tests/$id.json", result.toJson())
        }
        inputs.sanitizedLog?.takeIf { it.isNotBlank() }?.let {
            entries += entry("logs/sanitized-runtime.log", it)
        }
        // Digests cover the payload above. The checksum file cannot contain its
        // own digest, so it is appended last and excluded from itself.
        entries +=
            DiagnosticZipExporter.Entry(
                "checksums.sha256",
                DiagnosticZipExporter().checksums(entries),
            )
        return entries
    }

    private fun entry(
        name: String,
        content: String,
    ) = DiagnosticZipExporter.Entry(name, content.toByteArray())

    private fun manifest(
        inputs: Inputs,
        summary: DiagnosticSummary,
        declaredMissing: List<String>,
    ): String =
        buildString {
            append('{')
            append("\"schema_version\":").appendQuoted(DiagnosticSchema.SCHEMA_VERSION).append(',')
            append("\"scan_id\":").appendQuoted(inputs.report.scanId).append(',')
            append("\"started_utc\":").appendQuoted(inputs.report.startedAtMillis.toString()).append(',')
            append("\"finished_utc\":").appendQuoted(inputs.report.finishedAtMillis.toString()).append(',')
            append("\"mode\":").appendQuoted(inputs.report.config.mode.name).append(',')
            append("\"scope\":").appendQuoted(inputs.report.config.scope).append(',')
            append("\"consent\":").append("\"user_initiated_export\"").append(',')
            append("\"app_version\":").appendQuoted(inputs.environment.appVersion).append(',')
            append("\"app_build_sha\":").appendQuoted(inputs.environment.appBuildSha).append(',')
            append("\"app_version_code\":").append(inputs.environment.appVersionCode).append(',')
            append("\"whatsapp_package\":").appendQuoted(inputs.environment.whatsappPackage).append(',')
            append("\"whatsapp_version\":").appendQuoted(inputs.environment.whatsappVersion).append(',')
            append("\"android_version\":").appendQuoted(inputs.environment.androidVersion).append(',')
            append("\"android_sdk\":").append(inputs.environment.androidSdk).append(',')
            append("\"abi\":").appendQuoted(inputs.environment.abi).append(',')
            append("\"test_counts\":").append(summary.toJson()).append(',')
            append("\"declared_missing_sources\":").append(jsonArray(declaredMissing))
            append('}')
        }

    /** Bilingual by contract: the Arabic section first, then English. */
    private fun summaryMarkdown(
        report: DiagnosticEngine.Report,
        summary: DiagnosticSummary,
        declaredMissing: List<String>,
    ): String =
        buildString {
            appendLine("# WA X تشخيص ذاتي / WA X self-test")
            appendLine()
            appendLine("المسح / Scan: `${report.scanId}`")
            appendLine("النمط / Mode: `${report.config.mode}`")
            appendLine("المجموع / Total: ${summary.total}")
            appendLine(
                "نجح/فشل/محجوب / passed/failed/blocked: " +
                    "${summary.passed}/${summary.failed}/${summary.blocked}",
            )
            appendLine(
                "غير مختبر / inconclusive: ${summary.inconclusive} " +
                    "(not tested ${summary.notTested}, needs external " +
                    "${summary.needsExternalVerification})",
            )
            appendLine("غير مدعوم / unsupported: ${summary.unsupported}")
            appendLine()
            appendLine("## الجذر / Root causes")
            if (summary.clusters.isEmpty()) {
                appendLine("لا يوجد / none")
            } else {
                for (cluster in summary.clusters) {
                    appendLine("- `${cluster.rootCauseId}` ${cluster.rootTitle}")
                    if (cluster.symptomIds.size > 1) {
                        appendLine("  - symptoms: " + cluster.symptomIds.joinToString(", "))
                    }
                    if (cluster.remediation.isNotBlank()) {
                        appendLine("  - fix: ${cluster.remediation}")
                    }
                }
            }
            appendLine()
            appendLine("## النتائج / Results")
            for (result in report.results) {
                appendLine(
                    "- `${result.id}` ${result.status} (${result.evidenceLevel}, " +
                        "${result.verification}) — ${result.remediation}",
                )
            }
            if (declaredMissing.isNotEmpty()) {
                appendLine()
                appendLine("## مصادر غير متاحة / Declared missing sources")
                for (missing in declaredMissing) {
                    appendLine("- $missing")
                }
            }
        }

    private fun resultsJson(report: DiagnosticEngine.Report): String =
        buildString {
            append("{\"scan_id\":").appendQuoted(report.scanId).append(",\"checks\":[")
            append(report.results.joinToString(",") { it.toJson() })
            append("]}")
        }

    private fun environmentJson(environment: Environment): String =
        buildString {
            append('{')
            append("\"app_version\":").appendQuoted(environment.appVersion).append(',')
            append("\"app_build_sha\":").appendQuoted(environment.appBuildSha).append(',')
            append("\"whatsapp_package\":").appendQuoted(environment.whatsappPackage).append(',')
            append("\"whatsapp_version\":").appendQuoted(environment.whatsappVersion).append(',')
            append("\"android_version\":").appendQuoted(environment.androidVersion).append(',')
            append("\"android_sdk\":").append(environment.androidSdk).append(',')
            append("\"abi\":").appendQuoted(environment.abi)
            append('}')
        }

    private fun hooksJson(hooks: List<String>): String = "{\"hooks\":" + jsonArray(hooks) + "}"

    private fun resolversJson(states: Map<String, String>): String =
        buildString {
            append('{')
            states.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                appendQuoted(entry.key).append(':').appendQuoted(entry.value)
            }
            append('}')
        }

    private fun errorsJson(report: DiagnosticEngine.Report): String =
        buildString {
            val failures =
                report.results.filter {
                    it.status == DiagnosticStatus.FAIL || it.status == DiagnosticStatus.BLOCKED
                }
            append("{\"errors\":[")
            append(
                failures.joinToString(",") { failure ->
                    buildString {
                        append('{')
                        append("\"id\":").appendQuoted(failure.id)
                        append(",\"failure_class\":").appendQuoted(failure.failureClass.name)
                        append(",\"severity\":").appendQuoted(failure.severity)
                        append(",\"confidence\":").append(failure.confidence)
                        append(",\"observed\":").appendQuoted(failure.observedEvidence)
                        append(",\"remediation\":").appendQuoted(failure.remediation)
                        append('}')
                    }
                },
            )
            append("]}")
        }

    private fun timingsJson(report: DiagnosticEngine.Report): String =
        buildString {
            append('{')
            append("\"total_millis\":").append(report.finishedAtMillis - report.startedAtMillis).append(',')
            append("\"per_check\":{")
            report.results.forEachIndexed { index, result ->
                if (index > 0) append(',')
                appendQuoted(result.id).append(':').append(result.durationMillis)
            }
            append("}}")
        }

    private fun redactionJson(report: DiagnosticEngine.Report): String =
        buildString {
            append('{')
            append("\"policy\":").appendQuoted("local_only_never_auto_uploaded").append(',')
            append("\"applied_to\":").append(jsonArray(report.results.map { it.id })).append(',')
            append("\"unredacted_dump_available\":false")
            append('}')
        }

    /**
     * The redaction pass writes its own account of what it removed.
     *
     * The generic file above is part of the archive contract, but it is written
     * before the redactor runs, so it cannot claim what the redactor actually
     * did. This one replaces it afterwards with the real counts, and the archive
     * checksums are rebuilt over the final content so the file on disk matches
     * what the user was shown in the preview.
     */
    fun redactionReportJson(report: ExportRedactor.RedactionReport): String =
        buildString {
            append('{')
            append("\"policy\":").appendQuoted("local_only_never_auto_uploaded").append(',')
            append("\"total_replacements\":").append(report.total).append(',')
            appendField("jids", report.jidsRedacted)
            appendField("phone_numbers", report.numbersRedacted)
            appendField("message_like", report.messageLikeRedacted)
            appendField("tokens", report.tokensRedacted)
            appendField("paths", report.pathsRedacted)
            append("\"unredacted_dump_available\":false")
            append('}')
        }

    private fun StringBuilder.appendField(
        key: String,
        value: Int,
    ) {
        append('"')
            .append(key)
            .append("\":")
            .append(value)
            .append(',')
    }

    /**
     * Swaps in the real redaction account and re-seals the archive.
     *
     * Everything after this point is derived from the redacted content, so the
     * checksums are rebuilt rather than carried over: a digest over pre-redaction
     * bytes would make the archive look tampered with when it is in fact
     * correct.
     */
    fun withRedactionReport(
        entries: List<DiagnosticZipExporter.Entry>,
        redaction: ExportRedactor.RedactionReport,
    ): List<DiagnosticZipExporter.Entry> {
        val body =
            entries
                .filter { it.name != REDACTION_ENTRY && it.name != CHECKSUMS_ENTRY }
                .plus(
                    DiagnosticZipExporter.Entry(
                        REDACTION_ENTRY,
                        redactionReportJson(redaction).toByteArray(),
                    ),
                )
        return body.plus(
            DiagnosticZipExporter.Entry(CHECKSUMS_ENTRY, DiagnosticZipExporter().checksums(body)),
        )
    }

    const val REDACTION_ENTRY = "redaction-report.json"
}
