package com.wax.module.compat

/**
 * Target version matching for supported WhatsApp builds.
 *
 * The matching rule is a prefix match against a declared wildcard entry, where `xx`
 * is the wildcard: `2.26.40.xx` matches any `2.26.40.*` build. This is deliberately a
 * prefix match and not a semantic-version comparison, because WhatsApp build numbers
 * (`2.26.40.21`) have no ordering relationship the module relies on.
 *
 * The rule used to live inline in two places (the startup version gate in
 * [com.wax.module.xposed.core.FeatureLoader] and the settings screen in
 * `HomeFragment`). Both are now routed through here so they cannot drift apart.
 */
object TargetVersions {
    /** The wildcard suffix used by a declared version entry. */
    const val WILDCARD: String = ".xx"

    /**
     * Converts a declared entry such as `2.26.40.xx` into the prefix `2.26.40`.
     *
     * An entry that carries no wildcard is returned unchanged.
     */
    fun prefixOf(declaredVersion: String): String = declaredVersion.replace(WILDCARD, "")

    /**
     * Returns true when [versionName] matches any entry in [supportedVersions].
     *
     * A null or blank [versionName] never matches, which is what keeps the startup
     * gate failing closed when the package manager reports nothing usable.
     */
    fun isSupported(
        versionName: String?,
        supportedVersions: List<String>,
    ): Boolean {
        if (versionName.isNullOrBlank()) return false
        return supportedVersions.any { declared ->
            // An all-whitespace entry must not degrade into a prefix that matches
            // everything, so blank declarations are skipped rather than honoured.
            if (declared.isBlank()) return@any false
            versionName.startsWith(prefixOf(declared))
        }
    }

    /**
     * Assesses [versionName] against [supportedVersions], tolerance included.
     *
     * [isSupported] answers "is this build declared"; this answers "should the module load on
     * this build", which is the wider question the startup gate and the settings screen actually
     * have. The two are separate on purpose: the strict match stays the definition of a declared
     * build, and the tolerance rules are an explicit, testable addition on top of it.
     */
    fun assess(
        versionName: String?,
        supportedVersions: List<String>,
        channel: VersionChannel = VersionChannel.UNKNOWN,
        policy: VersionTolerancePolicy = VersionTolerancePolicy(),
    ): VersionAssessment = ChannelAwareVersionGate.assess(versionName, supportedVersions, channel, policy)

    /**
     * Whether the module should load on [versionName].
     *
     * True for a declared build, and for a tolerated undeclared one under the default policy.
     * An unreadable build number is always false.
     */
    fun accepts(
        versionName: String?,
        supportedVersions: List<String>,
        channel: VersionChannel = VersionChannel.UNKNOWN,
        policy: VersionTolerancePolicy = VersionTolerancePolicy(),
    ): Boolean = assess(versionName, supportedVersions, channel, policy).accepted

    /**
     * Drops blank declarations and duplicates while preserving declaration order.
     *
     * Used to normalise whatever source the version list came from before matching.
     */
    fun normalise(declaredVersions: List<String>): List<String> =
        declaredVersions
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * Picks the version list to enforce.
     *
     * [fromResources] wins when it holds anything usable; otherwise [fallback] is used
     * so a resource-loading failure degrades to the built-in list instead of falsely
     * reporting the installed version as unsupported.
     */
    fun resolve(
        declaredFromResources: List<String>,
        fallback: List<String>,
    ): List<String> {
        val normalised = normalise(declaredFromResources)
        return if (normalised.isNotEmpty()) normalised else normalise(fallback)
    }
}
