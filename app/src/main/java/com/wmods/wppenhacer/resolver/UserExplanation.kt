package com.wmods.wppenhacer.resolver

import com.wmods.wppenhacer.diagnostics.FailureCode

/**
 * Turns a technical outcome into something a user can read and act on.
 *
 * The gap this exists to close: a resolver that stops matching after a WhatsApp update
 * currently produces either a crash or nothing at all. Both are useless — the user cannot
 * tell whether a feature broke, whether it is their fault, or whether there is anything to
 * do. "This feature stopped working because WhatsApp changed an internal name, and it will
 * come back when the module updates" is actionable; a stack trace is not.
 *
 * Two rules hold for everything produced here:
 *  * it says which feature and what happened, and
 *  * it never contains anything identifying, because this text is shown in the UI and can
 *    be copied out of it. `ReportRedactor` is applied to the interpolated target too.
 */
object UserExplanation {

    /**
     * A short explanation of [outcome].
     *
     * @param target what the resolver was looking for, already safe to show; redacted here
     *   as a second line of defence
     */
    fun forOutcome(outcome: Resolution<*>, target: String? = null): String {
        val subject = target?.let { " (${com.wmods.wppenhacer.diagnostics.ReportRedactor.redactAndBound(it)})" }
            ?: ""
        return when (outcome) {
            is Resolution.Resolved<*> -> when (outcome.confidence) {
                Confidence.EXACT -> "works normally$subject"
                Confidence.LIKELY ->
                    "works, but the match was a guess$subject, so it may stop working " +
                            "after a WhatsApp update"

                else -> "could not be installed$subject"
            }

            is Resolution.NotFound ->
                "stopped working because WhatsApp changed something it depends on$subject. " +
                        "It will work again when WaEnhancer is updated for this version."

            is Resolution.Ambiguous ->
                "stopped working because WaEnhancer could not tell which part of WhatsApp " +
                        "to use$subject, so it was not installed."

            is Resolution.Incompatible ->
                "cannot run on this version$subject: ${outcome.reason}."
        }
    }

    /**
     * A short explanation of [outcome] for [featureId].
     */
    fun forOutcome(featureId: String, outcome: Resolution<*>): String =
        "$featureId ${forOutcome(outcome)}"

    /**
     * A short explanation of a [FailureCode], phrased as what happened.
     *
     * Deliberately says "WaEnhancer" rather than naming an internal class: the user cannot
     * act on a resolver name, only on the fact that the module needs an update.
     */
    fun forCode(code: FailureCode, detail: String? = null): String {
        val base = when (code) {
            FailureCode.RESOLVER_NOT_FOUND ->
                "WhatsApp changed something this feature depends on"
            FailureCode.RESOLVER_AMBIGUOUS ->
                "WaEnhancer could not identify the right part of WhatsApp"
            FailureCode.CLASS_NOT_FOUND ->
                "a part of WhatsApp that this feature needs no longer exists"
            FailureCode.MEMBER_NOT_FOUND ->
                "a WhatsApp method that this feature needs no longer exists"
            FailureCode.HOOK_INSTALL_FAILED ->
                "the feature could not attach itself to WhatsApp"
            FailureCode.CONSTRUCTION_FAILED ->
                "the feature could not start up"
            FailureCode.PREFERENCE_ERROR ->
                "the feature could not read its settings"
            FailureCode.TIMEOUT ->
                "the feature took too long and was skipped"
            FailureCode.ACCESS_DENIED ->
                "Android blocked the feature from accessing what it needs"
            FailureCode.INCOMPATIBLE ->
                "this feature does not support this WhatsApp or Android version"
            FailureCode.RESOLVER_INIT_FAILED ->
                "WaEnhancer could not read WhatsApp's internals at all"
            FailureCode.UNEXPECTED ->
                "the feature hit an unexpected problem"
        }
        val suffix = detail
            ?.takeIf { it.isNotBlank() }
            ?.let { " (${com.wmods.wppenhacer.diagnostics.ReportRedactor.redactAndBound(it, 120)})" }
            ?: ""
        return "$base.$suffix"
    }

    /** Whether this state is worth interrupting the user about. */
    fun shouldTellUser(health: FeatureHealth): Boolean = health.isNotable
}