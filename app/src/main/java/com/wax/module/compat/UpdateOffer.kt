package com.wax.module.compat

/**
 * Update-offer decisions, isolated from the network and the UI.
 *
 * Both the startup [com.wax.module.UpdateChecker] and the settings screen used
 * to normalise and compare versions inline, with the same two rules copy-pasted. They
 * now share this implementation so the two entry points cannot disagree.
 */
object UpdateOffer {
    /** Default changelog shown when a release carries no body. */
    const val DEFAULT_CHANGELOG: String = "No changelog available."

    /**
     * Normalises a release tag such as `v1.6.3` to a bare version `1.6.3`.
     */
    fun normaliseTag(tag: String?): String = tag?.removePrefix("v")?.trim().orEmpty()

    /**
     * Normalises the module's own version.
     *
     * Debug builds carry a `-dev+<hash>` suffix (`1.6.2-dev+544991A8`); only the
     * leading semantic version is comparable with a release tag.
     */
    fun normaliseModuleVersion(versionName: String?): String =
        versionName
            ?.substringBefore("-dev")
            ?.substringBefore("+")
            ?.trim()
            .orEmpty()

    /**
     * Decides whether an update dialog should be shown.
     *
     * A release is offered when its version differs from the installed one and the
     * user has not ignored that exact version. A blank release version is never
     * offered, which keeps a malformed API response from looking like an update.
     */
    fun shouldOffer(
        releaseVersion: String?,
        currentVersion: String?,
        ignoredVersion: String?,
    ): Boolean {
        val release = releaseVersion?.trim().orEmpty()
        if (release.isEmpty()) return false
        if (release == currentVersion?.trim()) return false
        return release != ignoredVersion?.trim()
    }
}
