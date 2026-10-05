package com.wax.module.resolver

import java.util.Collections

/**
 * One line of the diagnostics view: what a feature is doing and, when it is not doing
 * well, why.
 *
 * @param featureId the feature
 * @param health its current state
 * @param explanation a user-readable sentence, already redacted
 * @param code the machine-readable code, when the feature is not running
 */
data class FeatureStatusLine(
    val featureId: String,
    val health: FeatureHealth,
    val explanation: String,
    val code: com.wax.module.diagnostics.FailureCode? = null
) {

    /** Whether this line should be shown to the user at all. */
    val isNotable: Boolean get() = health.isNotable

    /** One line, safe to display and to copy out of the dialog. */
    fun toDisplayLine(): String = "$featureId [$health]: $explanation"
}

/**
 * The resolver state of the whole module, assembled for display.
 *
 * Reads from [ResolverRegistry] and [FeatureInstaller] rather than owning anything, so it
 * can be regenerated at any time and never drifts from what actually ran. This is T15's
 * deliverable: without it, a feature that silently stopped working is indistinguishable from
 * one the user never enabled.
 */
object ResolverDiagnostics {

    private val overrides = Collections.synchronizedList(ArrayList<FeatureStatusLine>())

    /**
     * Builds the report from the current session's recorded state.
     *
     * @param includeHealthy when false, only features worth telling the user about are
     *   returned, which is what the notification path wants
     */
    fun report(includeHealthy: Boolean = true): List<FeatureStatusLine> {
        val lines = LinkedHashMap<String, FeatureStatusLine>()

        // Features whose installation was attempted.
        for (outcome in FeatureInstaller.all()) {
            lines[outcome.featureId] = lineForOutcome(outcome)
        }

        // Resolvers that ran without a surrounding feature outcome.
        for (summary in ResolverRegistry.summary()) {
            val owner = lines[summary.resolverId]
            if (owner == null) {
                lines[summary.resolverId] = FeatureStatusLine(
                    featureId = summary.resolverId,
                    health = healthFor(summary.installable, summary.confidence),
                    explanation = UserExplanation.forCode(codeFor(summary.confidence), summary.reason),
                    code = codeFor(summary.confidence)
                )
            }
        }

        // Anything recorded explicitly, such as by a later phase.
        for (line in synchronized(overrides) { overrides.toList() }) {
            lines[line.featureId] = line
        }

        val all = lines.values.toList()
        return if (includeHealthy) all else all.filter { it.isNotable }
    }

    /** Records a line directly, for states produced outside the installer. */
    fun record(line: FeatureStatusLine) {
        overrides.add(line)
    }

    /** The subset a user should be told about. */
    fun notable(): List<FeatureStatusLine> = report(includeHealthy = false)

    /** Whether anything is worth telling the user at all. */
    fun hasSomethingToReport(): Boolean = notable().isNotEmpty()

    /**
     * Renders the report as shareable plain text.
     *
     * Every component has already been redacted, so this can be copied or attached to a
     * bug report without a second filtering pass.
     */
    fun render(includeHealthy: Boolean = false): String {
        val lines = report(includeHealthy)
        if (lines.isEmpty()) {
            return "No resolver problems detected. All features resolved normally."
        }
        val heading = if (includeHealthy) "WA X resolver status" else "WA X feature problems"
        return buildString {
            appendLine(heading)
            appendLine("features reported: ${lines.size}")
            appendLine()
            lines.forEach { appendLine("* ${it.toDisplayLine()}") }
        }
    }

    /** Drops recorded lines. */
    fun clear() {
        synchronized(overrides) { overrides.clear() }
    }

    private fun lineForOutcome(outcome: FeatureOutcome): FeatureStatusLine =
        FeatureStatusLine(
            featureId = outcome.featureId,
            health = outcome.health,
            explanation = explanationFor(outcome),
            code = outcome.code
        )

    private fun explanationFor(outcome: FeatureOutcome): String = when (outcome.health) {
        FeatureHealth.HEALTHY -> "working normally"
        FeatureHealth.FALLBACK ->
            "working through a compatibility path because the normal one is unavailable " +
                    "on this WhatsApp version"
        FeatureHealth.DEGRADED -> outcome.reason
        FeatureHealth.INCOMPATIBLE -> UserExplanation.forCode(
            com.wax.module.diagnostics.FailureCode.INCOMPATIBLE,
            outcome.reason
        )
        else -> UserExplanation.forCode(
            outcome.code ?: com.wax.module.diagnostics.FailureCode.UNEXPECTED,
            outcome.reason
        )
    }

    private fun healthFor(installable: Boolean, confidence: Confidence): FeatureHealth = when {
        !installable && confidence == Confidence.AMBIGUOUS -> FeatureHealth.FAILED
        !installable -> FeatureHealth.INCOMPATIBLE
        confidence == Confidence.LIKELY -> FeatureHealth.DEGRADED
        else -> FeatureHealth.HEALTHY
    }

    private fun codeFor(confidence: Confidence): com.wax.module.diagnostics.FailureCode =
        when (confidence) {
            Confidence.AMBIGUOUS ->
                com.wax.module.diagnostics.FailureCode.RESOLVER_AMBIGUOUS
            Confidence.NONE ->
                com.wax.module.diagnostics.FailureCode.RESOLVER_NOT_FOUND
            else -> com.wax.module.diagnostics.FailureCode.UNEXPECTED
        }
}