package com.wax.module.platform

import com.wax.module.resolver.Confidence

/**
 * The declaration every feature must provide before it may load.
 *
 * The plan's rule is that a feature is an isolated unit with declared dependencies,
 * compatibility requirements, diagnostics and tests — and that rule only holds if the
 * declaration is a single typed object rather than a convention spread across the code
 * base. This type is that object: the registry validates it, the compatibility canary
 * reads it, and diagnostics render it.
 *
 * Every field is intentionally required (no defaults for the meaningful ones): a missing
 * declaration should be a compile-time or registration-time error rather than a feature
 * that silently loads without a compatibility contract.
 */
data class FeatureMetadata(
    /** Stable, lower-case, dot-separated id. Never reused for a different feature. */
    val id: String,
    /** The name shown to the user. */
    val displayName: String,
    /** Which area of the product this belongs to. */
    val category: FeatureCategory,
    /** Preferences the feature reads, so a key cannot silently disappear in a refactor. */
    val preferenceKeys: List<String>,
    /** When the feature is allowed to load. */
    val startupPolicy: StartupPolicy,
    /** Resolvers whose failure makes the feature unavailable. */
    val requiredResolvers: List<String>,
    /** Resolvers the feature can use but does not depend on. */
    val optionalResolvers: List<String>,
    /** Android permissions the feature needs, if any. */
    val permissions: List<String>,
    /** WhatsApp versions the feature declares support for, in `x.y.z.xx` form. */
    val supportedWhatsAppVersions: List<String>,
    /** WhatsApp Business versions the feature declares support for. */
    val supportedBusinessVersions: List<String>,
    /** How much the feature's resolver contract is trusted, declared by its author. */
    val compatibilityConfidence: Confidence,
    /** What happens when a dependency does not resolve. */
    val fallbackBehavior: FallbackBehavior,
    /** Diagnostic classification used by the canary and the summary card. */
    val diagnostics: DiagnosticsMetadata,
    /** Test classes that cover the feature; a feature without tests is rejected. */
    val tests: List<String>,
) {
    /** Whether the feature declares support for the standard WhatsApp package. */
    val supportsWhatsApp: Boolean get() = supportedWhatsAppVersions.isNotEmpty()

    /** Whether the feature declares support for the Business package. */
    val supportsBusiness: Boolean get() = supportedBusinessVersions.isNotEmpty()

    /** Whether this feature gates a release: it is enabled first and disables last. */
    val isCritical: Boolean get() = diagnostics.critical

    /** One line for diagnostics and the feature browser. */
    fun toDisplayLine(): String = "$id [$category] $displayName (${startupPolicy.name.lowercase()})"
}

/** Product area, matching the release phases so filters line up with the roadmap. */
enum class FeatureCategory {
    /** T76-T85: profiles and runtime safety. */
    PRIVACY,

    /** T80-T85: startup safety, isolation, kill switch, canary. */
    RUNTIME_SAFETY,

    /** T86-T96: timelines, notes, scheduling. */
    MESSAGE_HISTORY,

    /** T97-T105: rules and Tasker. */
    AUTOMATION,

    /** T106-T115: translation, transcription, summaries. */
    INTELLIGENCE,

    /** T116-T124: media toolkit. */
    MEDIA,

    /** T125-T134: themes and accessibility. */
    THEME,

    /** T135-T143: notifications and calls. */
    NOTIFICATION,

    /** T144-T151: storage, vault, backup. */
    STORAGE,

    /** T152-T160: package profiles and accounts. */
    MULTI_ACCOUNT,

    /** Existing module features that have not been reclassified yet. */
    GENERAL,
}

/** When the feature loads, and what happens when it cannot. */
enum class StartupPolicy {
    /**
     * Loads before anything else and gates the module. Only for safety-critical units such
     * as the kill switch and diagnostics.
     */
    EAGER_CRITICAL,

    /** Loads during normal startup. */
    EAGER_NORMAL,

    /** Loads on first use, keeping startup time down. */
    LAZY,

    /** Only loads when the user opens it. */
    ON_DEMAND,

    /** Declared but not loaded unless the user opts in (developer/implicit features). */
    DISABLED_BY_DEFAULT,
}

/** The declared answer to "a dependency did not resolve". */
enum class FallbackBehavior {
    /** The feature has no alternatives and will stop when a dependency is missing. */
    NONE,

    /** A compatibility path exists and should be tried before giving up. */
    COMPAT_PATH,

    /** The feature can run reduced (for example manual refresh instead of live updates). */
    DEGRADE,

    /**
     * The feature stops, but only the feature: the platform records it and keeps running.
     * This is the default the isolation rule expects.
     */
    DISABLE_FEATURE,
}

/**
 * Diagnostics metadata.
 *
 * @param critical true for features that gate the canary and are never auto-enabled on an
 *   unverified WhatsApp build
 * @param tags free-form grouping used by the diagnostics filter ("privacy", "storage", …)
 */
data class DiagnosticsMetadata(
    val critical: Boolean = false,
    val tags: Set<String> = emptySet(),
)
