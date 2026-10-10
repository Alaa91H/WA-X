package com.wax.module.diagnostics.selftest

/**
 * The data model of the F155 atomic self-test.
 *
 * The rule this whole file exists to enforce: **an installed hook is not a
 * working feature**. Every result therefore carries both a status and the
 * evidence level that produced it, and the two cannot be faked apart — a
 * check with no observation is `NOT_TESTED`, never `PASS`.
 */
object DiagnosticSchema {
    const val SCHEMA_VERSION = "wax.diagnostics/1"
}

/** Lifecycle of one atomic check. */
enum class DiagnosticStatus {
    NOT_TESTED,
    RUNNING,
    PASS,
    FAIL,
    BLOCKED,
    UNSUPPORTED,
    NEEDS_EXTERNAL_VERIFICATION,
    ;

    /** Terminal states, i.e. everything except the two in-flight ones. */
    val isFinal: Boolean get() = this != NOT_TESTED && this != RUNNING
}

/**
 * How far the evidence actually reaches.
 *
 * L5 is deliberately **not** reachable without a consenting second account:
 * nothing inside WA X can observe whether another human saw a tick.
 */
enum class EvidenceLevel {
    /** Build, package, framework and scope. */
    L0_PACKAGE,

    /** Bootstrap lifecycle and manager IPC. */
    L1_LIFECYCLE,

    /** Resolver candidates enumerated and structurally verified. */
    L2_RESOLVER,

    /** Hook registered and unhookable. */
    L3_HOOK,

    /** Callback actually fired and state changed. */
    L4_TRIGGER,

    /** End-to-end, externally observable behaviour. */
    L5_EXTERNAL,
}

/** How far a feature has been proven to work, kept separate from the status. */
enum class VerificationState {
    /** Nothing observed yet. */
    NOT_OBSERVED,
    /** A hook is registered. This is the ceiling for a "smoke" claim. */
    HOOKED,
    /** The callback actually fired. */
    TRIGGERED,
    /** Locally observed behaviour, e.g. the hook changed local state. */
    LOCALLY_VERIFIED,
    /** Confirmed by a human observing the effect from another account. */
    EXTERNALLY_VERIFIED,
}

/** What a failed check means, so the UI can group symptoms by cause. */
enum class FailureClass {
    NONE,
    DEPENDENCY_MISSING,
    RESOLVER_AMBIGUOUS,
    SIGNATURE_UNSUPPORTED,
    PREFERENCE_DISABLED,
    SCOPE_MISSING,
    FRAMEWORK_UNAVAILABLE,
    IPC_DENIED,
    TIMEOUT,
    CRASHED,
}

/**
 * One atomic check result.
 *
 * [observedEvidence] is the only field that may justify `PASS`: an empty
 * observation forces the status back to `NOT_TESTED`, which is what the
 * false-PASS injection tests exercise.
 */
data class AtomicCheckResult(
    val id: String,
    val title: String,
    val scope: String,
    val status: DiagnosticStatus,
    val evidenceLevel: EvidenceLevel,
    val expected: String,
    val observedEvidence: String,
    val verification: VerificationState,
    val timestampMillis: Long,
    val whatsappBuild: String,
    val severity: String,
    val confidence: Double,
    val failureClass: FailureClass,
    val remediation: String,
    val durationMillis: Long = 0L,
    val dependsOn: List<String> = emptyList(),
    val externalConfirmationRequired: Boolean = false,
) {
    /**
     * The status an honest engine must report.
     *
     * A `PASS` without an observation is downgraded rather than trusted, and
     * a check that is wired to a dependency which failed cannot pass either.
     */
    fun honestStatus(dependencyResults: Map<String, AtomicCheckResult>): DiagnosticStatus {
        // A check behind a dependency that did not pass cannot pass itself:
        // that is how a single unresolved class used to present as a healthy
        // runtime. Unverified (`NOT_TESTED`) dependencies block too — a missing
        // observation is not evidence of success.
        val failedDependency =
            dependsOn.firstOrNull { id ->
                val dependencyStatus = dependencyResults[id]?.status ?: return@firstOrNull false
                dependencyStatus != DiagnosticStatus.PASS
            }
        return when {
            status == DiagnosticStatus.PASS && observedEvidence.isBlank() ->
                DiagnosticStatus.NOT_TESTED
            status == DiagnosticStatus.PASS && failedDependency != null ->
                DiagnosticStatus.BLOCKED
            else -> status
        }
    }

    fun toJson(): String = buildString {
        append('{')
        appendField("id", id)
        appendField("title", title)
        appendField("scope", scope)
        appendField("status", status.name)
        appendField("evidence_level", evidenceLevel.name)
        appendField("expected", expected)
        appendField("observed", observedEvidence)
        appendField("verification", verification.name)
        append("\"timestamp_millis\":").append(timestampMillis).append(',')
        appendField("whatsapp_build", whatsappBuild)
        appendField("severity", severity)
        append("\"confidence\":").append(confidence)
        appendField("failure_class", failureClass.name)
        appendField("remediation", remediation)
        append("\"duration_millis\":").append(durationMillis)
        append("\"depends_on\":[")
        append(dependsOn.joinToString(",") { "\"$it\"" })
        append(']')
        append("\"external_confirmation_required\":").append(externalConfirmationRequired)
        append('}')
    }

    private fun StringBuilder.appendField(key: String, value: String) {
        append('"').append(key).append("\":")
        appendQuoted(value)
        append(',')
    }
}

/**
 * Minimal JSON string escaping, kept here so reports cannot be malformed.
 *
 * Returns the receiver so a caller can keep building the same object instead of
 * splitting every entry into two statements.
 */
internal fun StringBuilder.appendQuoted(value: String): StringBuilder {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') {
                append("\\u").append(String.format("%04x", character.code))
            } else {
                append(character)
            }
        }
    }
    append('"')
    return this
}

internal fun jsonArray(values: List<String>): String =
    values.joinToString(",", prefix = "[", postfix = "]") { value ->
        buildString { appendQuoted(value) }
    }
