package com.wax.module.platform

/**
 * Everything the platform is allowed to know when deciding whether a feature runs.
 *
 * The type is the access contract in data form. Every field describes a technical or safety
 * fact about the device and the installed client — which target, which version, whether the
 * resolvers matched, whether the permission was granted, whether Labs is on, what the
 * isolation engine decided. There is no field for a purchase, a donation, a licence, an
 * activation code or an entitlement, and there is no way to add one without editing this
 * class, which is what the no-paywall tests and the repository scanner watch.
 *
 * [online] exists for the opposite reason: connectivity is a fact worth recording in
 * diagnostics, and the contract says it must never decide availability. It is carried here
 * only so a test can vary it alone and prove the decision does not move. A feature that goes
 * dark because a server could not answer is exactly the failure mode the contract forbids.
 */
data class FeatureFacts(
    /** The hooked application the question is about. */
    val target: TargetApp,
    /** The version of the installed client, for diagnostics. Never parsed for gating here. */
    val installedVersion: String? = null,
    /** Whether the feature declares support for this target at all. */
    val targetSupported: Boolean = true,
    /** Whether the installed version is inside the feature's declared compatibility range. */
    val versionSupported: Boolean = true,
    /** Whether every resolver the feature declared as required resolved on this build. */
    val resolversResolved: Boolean = true,
    /** Whether the Android permissions the feature declared were granted. */
    val permissionsGranted: Boolean = true,
    /**
     * Whether a capability outside the resolver layer is present: root for a root-only path,
     * a platform API for a version-specific hook, the hardware a feature needs.
     */
    val capabilityPresent: Boolean = true,
    /** Whether the user opted into experimental work. */
    val labsEnabled: Boolean = false,
    /** What the isolation engine currently holds about this feature. */
    val killSwitchState: FeatureSwitchState = FeatureSwitchState.ENABLED,
    /** Recorded for diagnostics. Availability must not depend on it. */
    val online: Boolean = true,
)

/**
 * The answer for one feature: why it is in the state it is in.
 *
 * Availability and the user's own toggle are reported separately and on purpose. "The user
 * turned it off" and "this WhatsApp version broke it" need different wording and different
 * recovery, and collapsing them is how a compatibility failure gets blamed on the user.
 */
data class FeatureAvailabilityReport(
    val featureId: String,
    val availability: FeatureAvailability,
    /** Whether the user's own switch is on. Independent of [availability]. */
    val enabledByUser: Boolean,
    /** The sentence to display. Comes from [FeatureAvailability] and is payment-free. */
    val explanation: String,
    /** The mechanism behind the state, for diagnostics. Contains no user data. */
    val detail: String,
) {
    /** Whether the feature is usable right now, which needs both answers to be positive. */
    val isUsable: Boolean get() = availability.isAvailable && enabledByUser

    /** One line for diagnostics and the feature browser. */
    fun toDisplayLine(): String = "$featureId [${availability.name}] $detail"
}

/**
 * Decides whether a feature may run, from technical facts alone.
 *
 * The evaluation order is fixed and each step is exhaustive, because the alternative — a
 * scoring or priority scheme — produces answers nobody can explain. Reading the order top to
 * bottom tells the user which single fact is holding a feature back, which is what the
 * compatibility screens have to be able to say.
 *
 * The policy is deliberately pure: no clock, no storage, no network. A decision that
 * consulted a remote service could turn a project feature off during an outage, which the
 * access contract forbids outright.
 */
object FeatureAccessPolicy {
    /**
     * Evaluates [metadata] against [facts].
     *
     * The order is:
     * 1. a declaration that says the feature is not built yet;
     * 2. the target;
     * 3. the version;
     * 4. an incompatibility verdict the canary recorded for this build;
     * 5. a bounded disable recorded by the isolation engine;
     * 6. the Labs gate for an experimental feature;
     * 7. a missing resolver, permission or capability;
     * 8. otherwise available.
     */
    fun evaluate(
        metadata: FeatureMetadata,
        facts: FeatureFacts,
    ): FeatureAvailabilityReport {
        val enabledByUser = facts.killSwitchState != FeatureSwitchState.MANUALLY_DISABLED
        val (availability, detail) =
            when {
                metadata.availability == FeatureAvailability.NOT_IMPLEMENTED ->
                    FeatureAvailability.NOT_IMPLEMENTED to "declared as not implemented"

                !facts.targetSupported ->
                    FeatureAvailability.UNSUPPORTED_TARGET to
                        "the feature does not declare support for ${facts.target.displayName}"

                !facts.versionSupported ->
                    FeatureAvailability.UNSUPPORTED_VERSION to
                        "the installed version is outside the declared compatibility range"

                facts.killSwitchState == FeatureSwitchState.INCOMPATIBLE ->
                    FeatureAvailability.UNSUPPORTED_VERSION to
                        "marked incompatible with this build by the compatibility canary"

                facts.killSwitchState == FeatureSwitchState.TEMPORARILY_DISABLED ||
                    facts.killSwitchState == FeatureSwitchState.AUTOMATICALLY_DISABLED ->
                    FeatureAvailability.TEMPORARILY_DISABLED to
                        "held back by the isolation engine (${facts.killSwitchState.name.lowercase()})"

                metadata.availability == FeatureAvailability.EXPERIMENTAL && !facts.labsEnabled ->
                    FeatureAvailability.EXPERIMENTAL to "experimental, and Labs is off"

                !facts.resolversResolved ->
                    FeatureAvailability.MISSING_CAPABILITY to "a required resolver did not resolve"

                !facts.permissionsGranted ->
                    FeatureAvailability.MISSING_CAPABILITY to "a required permission is not granted"

                !facts.capabilityPresent ->
                    FeatureAvailability.MISSING_CAPABILITY to "a required capability is unavailable"

                else -> FeatureAvailability.AVAILABLE to "available"
            }
        return FeatureAvailabilityReport(
            featureId = metadata.id,
            availability = availability,
            enabledByUser = enabledByUser,
            explanation = availability.explanation,
            detail = detail,
        )
    }

    /**
     * Evaluates every feature in [features] for one target.
     *
     * A feature whose version range does not include [installedVersion] is unsupported even
     * when the caller passed [FeatureFacts.versionSupported] as true, because the versions a
     * feature declares are part of its own contract and not the caller's to override.
     */
    fun evaluateAll(
        features: List<FeatureMetadata>,
        facts: FeatureFacts,
    ): List<FeatureAvailabilityReport> =
        features.map { metadata ->
            evaluate(metadata, facts.copy(versionSupported = facts.versionSupported && declaresVersion(metadata, facts)))
        }

    /**
     * Whether [metadata] declares support for the version in [facts].
     *
     * An unreadable installed version is treated as "cannot tell", which fails closed: a
     * feature is not enabled on a build the module could not identify.
     */
    private fun declaresVersion(
        metadata: FeatureMetadata,
        facts: FeatureFacts,
    ): Boolean {
        val declared =
            if (facts.target ==
                TargetApp.WHATSAPP_BUSINESS
            ) {
                metadata.supportedBusinessVersions
            } else {
                metadata.supportedWhatsAppVersions
            }
        if (declared.isEmpty()) return false
        val installed = facts.installedVersion?.trim().orEmpty()
        if (installed.isEmpty()) return false
        // Declared ranges are `x.y.z.xx` prefixes, the same shape the runtime version gate
        // uses; a comparison here would have to re-implement that gate and would disagree
        // with it. So the prefix form is honoured and anything else is "cannot tell".
        return declared.any { range -> installed.startsWith(range.substringBeforeLast('.')) || range == installed }
    }

    /** Renders a report as the sentence the interface may show. */
    fun describe(report: FeatureAvailabilityReport): String =
        if (report.isUsable) {
            "Available."
        } else if (!report.enabledByUser) {
            "Off — you turned this feature off."
        } else {
            report.explanation
        }
}
