package com.wax.module.compat

/**
 * Which release channel an installed WhatsApp build came from.
 *
 * The channel is an *input*, not something WA X infers. A build number does not carry the
 * channel in a way that can be read reliably, and guessing it would mean telling a user their
 * stable build is a beta. The loader passes what it knows, and [UNKNOWN] is the honest default.
 *
 * The channel changes the wording of a compatibility decision, never whether the module loads:
 * a pre-release build is tolerated on the same terms as any other undeclared build, so a beta
 * user is not silently excluded from a module that would work for them.
 */
enum class VersionChannel(
    val label: String,
) {
    STABLE("stable"),
    BETA("beta"),
    ALPHA("alpha"),

    /** The channel could not be determined. Treated exactly like stable for acceptance. */
    UNKNOWN("unknown"),
    ;

    /** Whether this channel ships pre-release builds. */
    val isPrerelease: Boolean get() = this == BETA || this == ALPHA
}

/**
 * A parsed WhatsApp build number.
 *
 * WhatsApp version names are four dotted numbers (`2.26.40.75`) or a truncated form of the same
 * thing (`2.26.40`, `2.26`), sometimes with a suffix. Only the numbers are kept: the suffix is
 * not a channel (the loader reports that separately) and treating it as one is how a stable
 * build gets mislabelled.
 *
 * The ordering implemented here is numeric, which is the whole reason this type exists. Family
 * comparison as strings is wrong in exactly the case that matters: `"2.10" < "2.9"` in string
 * order, so a module that lexically compared families would call the newer series older.
 */
data class BuildNumber(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int,
) : Comparable<BuildNumber> {
    /** The three-segment form a declared entry uses, for example `2.26.40`. */
    val release: String get() = "$major.$minor.$patch"

    /** The series this build belongs to, for example `2.26`. */
    val family: VersionFamily get() = VersionFamily(major, minor)

    override fun compareTo(other: BuildNumber): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.build })

    companion object {
        /** Parses a version name, or null when it carries no usable build number. */
        fun parse(raw: String?): BuildNumber? {
            if (raw.isNullOrBlank()) return null
            val numeric =
                raw
                    .trim()
                    .substringBefore('-')
                    .substringBefore(' ')
                    .trim()
            val parts = numeric.split('.')
            if (parts.size < 2) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            return BuildNumber(
                major = numbers[0],
                minor = numbers[1],
                patch = numbers.getOrElse(2) { 0 },
                build = numbers.getOrElse(3) { 0 },
            )
        }
    }
}

/**
 * The `major.minor` series a build belongs to, ordered numerically.
 *
 * This is deliberately a separate type from [BuildNumber]: `2.26.41.5` is a later *build* than a
 * declared `2.26.40.xx`, but the same *family*, and the two comparisons drive different verdicts.
 * Folding them together is how a routine patch gets reported as a new series.
 */
data class VersionFamily(
    val major: Int,
    val minor: Int,
) : Comparable<VersionFamily> {
    override fun compareTo(other: VersionFamily): Int = compareValuesBy(this, other, { it.major }, { it.minor })
}

/**
 * How the interface should describe an installed build.
 *
 * Three states rather than two, because the middle one is real and has to be visible: a build the
 * declaration does not cover is loaded, so calling it "unsupported" would be wrong, and calling it
 * "supported" would claim a verification the project has not done. The tone is what the status
 * cards switch on.
 */
enum class VersionStatusTone {
    /** A declared build. The only tone backed by the compatibility declaration. */
    SUPPORTED,

    /** Loaded, but no declaration covers it. Shown as loaded and unverified. */
    UNVERIFIED,

    /** Refused, or unreadable. Shown as unsupported. */
    UNSUPPORTED,
}

/** The outcome of comparing an installed build against the declared range. */
enum class VersionVerdict {
    /** The installed build matches a declared entry. The only verdict backed by the declaration. */
    DECLARED,

    /** Undeclared, but between the oldest and newest declared family. */
    UNVERIFIED_INSIDE_RANGE,

    /** Undeclared and from a family newer than anything declared, for example a new series. */
    UNVERIFIED_NEWER_FAMILY,

    /** From a family older than the oldest declared one. */
    UNSUPPORTED_OLDER_FAMILY,

    /** The installed version or the declared list could not be read. Fails closed. */
    UNREADABLE,
}

/**
 * Whether an undeclared build is loaded or refused.
 *
 * The default is the maximum-compatibility reading, which is what the module wants: a build the
 * maintainer has never seen is loaded and marked unverified rather than refused, because the
 * capability checks, the compatibility canary and the kill switch already exist to contain a
 * build that turns out not to work. Turning either flag off restores a strict allow-list for a
 * deployment that prefers refusal.
 */
data class VersionTolerancePolicy(
    /** Load an undeclared build whose family sits between two declared families. */
    val tolerateUnverifiedInsideRange: Boolean = true,
    /** Load an undeclared build from a newer family, for example a new `2.27` series. */
    val tolerateUnverifiedNewerFamily: Boolean = true,
)

/**
 * What WA X decided about one installed build, and why.
 *
 * [accepted] is the load decision. [isDeclared] and [isExperimental] are what diagnostics and the
 * interface need: a tolerated build must be *reported* as tolerated, because a bug reported from
 * one has to be triaged as unverified rather than as a regression in a build the maintainer
 * supports. [explanation] never claims verification the project cannot produce — it says
 * "unverified" exactly when the verdict is one of the tolerated ones.
 */
data class VersionAssessment(
    val verdict: VersionVerdict,
    val installed: BuildNumber?,
    val channel: VersionChannel,
    val accepted: Boolean,
    val explanation: String,
) {
    /** Whether the build is inside the declared range rather than merely tolerated. */
    val isDeclared: Boolean get() = verdict == VersionVerdict.DECLARED

    /** Whether the build was loaded without the declaration covering it. */
    val isExperimental: Boolean get() = accepted && !isDeclared

    /** The three-state answer the status cards show. */
    val tone: VersionStatusTone
        get() =
            when {
                isDeclared -> VersionStatusTone.SUPPORTED
                accepted -> VersionStatusTone.UNVERIFIED
                else -> VersionStatusTone.UNSUPPORTED
            }

    /** One line for the audit log. Carries no message content and no account data. */
    fun toDisplayLine(): String = "version ${verdict.name.lowercase()} (${channel.label}): $explanation"
}

/**
 * Compares an installed build against the declared range, channel included.
 *
 * This is the rule the startup gate and the settings screen both use, written once. It is
 * deliberately a *widening* of [TargetVersions.isSupported] rather than a replacement: the
 * strict prefix match is still what "declared" means, and this adds a documented, testable
 * answer for the builds the declaration does not cover — which is every beta, every alpha, every
 * new patch of a series the maintainer declared, and every new family.
 */
object ChannelAwareVersionGate {
    /** Assesses [versionName] against [declaredVersions]. */
    fun assess(
        versionName: String?,
        declaredVersions: List<String>,
        channel: VersionChannel = VersionChannel.UNKNOWN,
        policy: VersionTolerancePolicy = VersionTolerancePolicy(),
    ): VersionAssessment {
        val installed =
            BuildNumber.parse(versionName)
                ?: return VersionAssessment(
                    verdict = VersionVerdict.UNREADABLE,
                    installed = null,
                    channel = channel,
                    accepted = false,
                    explanation =
                        "The installed build number could not be read, so WA X treats this build as unsupported.",
                )

        val declared = TargetVersions.normalise(declaredVersions)
        if (TargetVersions.isSupported(versionName, declared)) {
            return VersionAssessment(
                verdict = VersionVerdict.DECLARED,
                installed = installed,
                channel = channel,
                accepted = true,
                explanation = "${installed.release} is inside the declared compatibility range.",
            )
        }

        val declaredBuilds = declared.mapNotNull { BuildNumber.parse(TargetVersions.prefixOf(it)) }
        if (declaredBuilds.isEmpty()) {
            return VersionAssessment(
                verdict = VersionVerdict.UNREADABLE,
                installed = installed,
                channel = channel,
                accepted = false,
                explanation =
                    "The declared version list could not be read, so ${installed.release} cannot be verified against it.",
            )
        }

        val oldest = declaredBuilds.min()
        val newest = declaredBuilds.max()
        val suffix = if (channel.isPrerelease) " (${channel.label} build)" else ""
        val installedFamily = installed.family
        val oldestFamily = oldest.family
        val newestFamily = newest.family

        return when {
            installedFamily < oldestFamily ->
                VersionAssessment(
                    verdict = VersionVerdict.UNSUPPORTED_OLDER_FAMILY,
                    installed = installed,
                    channel = channel,
                    accepted = false,
                    explanation =
                        "${installed.release} is older than the oldest declared build (${oldest.release}), " +
                            "which this build of WA X does not support$suffix.",
                )

            installedFamily > newestFamily ->
                VersionAssessment(
                    verdict = VersionVerdict.UNVERIFIED_NEWER_FAMILY,
                    installed = installed,
                    channel = channel,
                    accepted = policy.tolerateUnverifiedNewerFamily,
                    explanation =
                        "${installed.release} is newer than the newest declared build (${newest.release}), " +
                            "so it is loaded as unverified$suffix.",
                )

            else ->
                VersionAssessment(
                    verdict = VersionVerdict.UNVERIFIED_INSIDE_RANGE,
                    installed = installed,
                    channel = channel,
                    accepted = policy.tolerateUnverifiedInsideRange,
                    explanation =
                        "${installed.release} is not a declared build, but it sits inside the declared range " +
                            "(${oldest.release}-${newest.release}), so it is loaded as unverified$suffix.",
                )
        }
    }
}
