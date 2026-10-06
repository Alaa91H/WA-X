package com.wax.module.compat

/**
 * Update-offer decisions, isolated from the network and the UI.
 *
 * Version comparison is semantic rather than string-based: an older GitHub release is
 * never offered just because its text differs from the installed version, and values
 * such as 1.10.0 correctly sort after 1.9.9.
 */
object UpdateOffer {
    /** Default changelog shown when a release carries no body. */
    const val DEFAULT_CHANGELOG: String = "No changelog available."

    private data class SemanticVersion(
        val core: List<Long>,
        val prerelease: List<String>?,
    )

    /**
     * Normalises a release tag such as `v1.6.3` to a bare version `1.6.3`.
     */
    fun normaliseTag(tag: String?): String =
        tag
            ?.trim()
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.trim()
            .orEmpty()

    /**
     * Normalises the module's own version.
     *
     * Debug builds carry a `-dev+<hash>` suffix (`1.6.2-dev+544991A8`); the
     * release comparison intentionally uses the base version so a debug build from
     * 1.6.2 is not told to "update" to the same 1.6.2 release.
     */
    fun normaliseModuleVersion(versionName: String?): String {
        val value = versionName?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return normaliseTag(value.substringBefore("-dev"))
    }

    /** Returns true only for a syntactically comparable semantic version. */
    fun isValidVersion(version: String?): Boolean =
        parseSemanticVersion(normaliseTag(version)) != null

    /**
     * Compares a release version with the installed version.
     *
     * @return a positive number when the release is newer, zero when equivalent,
     *   a negative number when older, or null when either value is malformed.
     */
    fun compareVersions(
        releaseVersion: String?,
        currentVersion: String?,
    ): Int? {
        val release = parseSemanticVersion(normaliseTag(releaseVersion)) ?: return null
        val current = parseSemanticVersion(normaliseModuleVersion(currentVersion)) ?: return null
        return compareSemanticVersions(release, current)
    }

    /** True only when the release is strictly newer than the installed version. */
    fun isUpdateAvailable(
        releaseVersion: String?,
        currentVersion: String?,
    ): Boolean = (compareVersions(releaseVersion, currentVersion) ?: return false) > 0

    /**
     * Decides whether an update dialog should be shown.
     *
     * A release is offered only when it is strictly newer than the installed version
     * and the user has not ignored that exact release. Malformed versions fail closed.
     */
    fun shouldOffer(
        releaseVersion: String?,
        currentVersion: String?,
        ignoredVersion: String?,
    ): Boolean {
        val release = normaliseTag(releaseVersion)
        if (!isUpdateAvailable(release, currentVersion)) return false

        val ignored = normaliseTag(ignoredVersion)
        if (ignored.isEmpty()) return true

        return compareVersions(release, ignored) != 0
    }

    private fun parseSemanticVersion(raw: String): SemanticVersion? {
        if (raw.isBlank()) return null

        val versionWithoutBuildMetadata = raw.substringBefore("+")
        val prereleaseSeparator = versionWithoutBuildMetadata.indexOf('-')
        val coreText =
            if (prereleaseSeparator >= 0) {
                versionWithoutBuildMetadata.substring(0, prereleaseSeparator)
            } else {
                versionWithoutBuildMetadata
            }
        val prereleaseText =
            if (prereleaseSeparator >= 0) {
                versionWithoutBuildMetadata.substring(prereleaseSeparator + 1)
            } else {
                null
            }

        if (coreText.isBlank()) return null

        val core = ArrayList<Long>()
        for (part in coreText.split('.')) {
            if (part.isEmpty() || part.any { !it.isDigit() }) return null
            core += part.toLongOrNull() ?: return null
        }

        val prerelease =
            prereleaseText?.let { value ->
                if (value.isBlank()) return null
                val identifiers = value.split('.')
                if (
                    identifiers.any { identifier ->
                        identifier.isEmpty() ||
                            identifier.any { character ->
                                !character.isLetterOrDigit() && character != '-'
                            }
                    }
                ) {
                    return null
                }
                identifiers
            }

        return SemanticVersion(core = core, prerelease = prerelease)
    }

    private fun compareSemanticVersions(
        left: SemanticVersion,
        right: SemanticVersion,
    ): Int {
        val maxCoreSize = maxOf(left.core.size, right.core.size)
        for (index in 0 until maxCoreSize) {
            val leftPart = left.core.getOrElse(index) { 0L }
            val rightPart = right.core.getOrElse(index) { 0L }
            val comparison = leftPart.compareTo(rightPart)
            if (comparison != 0) return comparison
        }

        val leftPrerelease = left.prerelease
        val rightPrerelease = right.prerelease
        if (leftPrerelease == null && rightPrerelease == null) return 0
        if (leftPrerelease == null) return 1
        if (rightPrerelease == null) return -1

        val commonSize = minOf(leftPrerelease.size, rightPrerelease.size)
        for (index in 0 until commonSize) {
            val leftIdentifier = leftPrerelease[index]
            val rightIdentifier = rightPrerelease[index]
            val leftNumber = leftIdentifier.toLongOrNull()
            val rightNumber = rightIdentifier.toLongOrNull()

            val comparison =
                when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> leftIdentifier.compareTo(rightIdentifier)
                }

            if (comparison != 0) return comparison
        }

        return leftPrerelease.size.compareTo(rightPrerelease.size)
    }
}
