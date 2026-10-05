package com.wax.module.platform

import com.wax.module.compat.TargetVersions
import com.wax.module.platform.TargetPackageRegistry

/**
 * The identity of one installed target package.
 *
 * The version name alone is not enough to call a build "known": WhatsApp ships many builds
 * under the same name, and a rebuild can change internals without changing the version.
 * The APK fingerprint is what makes "this is a different binary than the one we verified"
 * detectable, which is the trigger the canary needs.
 */
data class TargetFingerprint(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val apkFingerprint: String,
) {
    /** Whether this is a different binary than [other]. */
    fun differsFrom(other: TargetFingerprint?): Boolean =
        other == null ||
            packageName != other.packageName ||
            versionCode != other.versionCode ||
            apkFingerprint != other.apkFingerprint

    /** One line for diagnostics; never contains user data. */
    fun toDisplayLine(): String = "$packageName $versionName ($versionCode)"
}

/**
 * What the canary decided to do with each feature on this build.
 *
 * The plan is data, not an action: the caller applies it through the kill switch, so the
 * decision can be shown, tested and logged without side effects. That separation is what
 * lets the simulator and the diagnostics screen reuse the same evaluation.
 */
data class CanaryPlan(
    /** True when the installed build is either undeclared or a different binary. */
    val newBuildDetected: Boolean,
    /** Features whose required resolvers all resolved; safe to load. */
    val enabled: List<String>,
    /** Features held back until a human verifies the new build. */
    val deferred: List<String>,
    /** Features whose required resolvers are missing; they cannot run here. */
    val blocked: List<String>,
    /** The T84 summary computed from this run. */
    val summary: CompatibilitySummary,
) {
    /** A user-facing explanation of the plan. */
    fun describe(): String =
        buildString {
            appendLine("Compatibility canary")
            appendLine("New WhatsApp build detected: ${if (newBuildDetected) "yes" else "no"}")
            appendLine("Enabled: ${enabled.size}")
            if (deferred.isNotEmpty()) appendLine("Deferred for verification: ${deferred.sorted().joinToString(", ")}")
            if (blocked.isNotEmpty()) appendLine("Blocked: ${blocked.sorted().joinToString(", ")}")
            appendLine()
            append(summary.renderCard())
        }
}

/**
 * The T83 staged-enablement policy for a new WhatsApp build.
 *
 * The failure this prevents is all-or-nothing: after an update, either every feature loads
 * against changed internals (and the module destabilises) or the whole module refuses to
 * start (and the user loses features that were never at risk). The canary checks each
 * feature's *declared* resolver contract and splits the module into three groups — load,
 * wait for verification, cannot run — so a partially understood build degrades instead of
 * breaking.
 *
 * Nothing here touches storage: the caller applies the plan (typically
 * `FeatureKillSwitch.markIncompatible` for blocked features and deferral for the rest) and
 * records the summary.
 */
class CompatibilityCanary {
    /**
     * Evaluates [features] against [current].
     *
     * @param previous the fingerprint stored at the last verified start, or null when this
     *   is a fresh install with no recorded baseline
     * @param postureOf the current state of one resolver; called once per resolver
     */
    fun plan(
        features: List<FeatureMetadata>,
        previous: TargetFingerprint?,
        current: TargetFingerprint,
        postureOf: (String) -> ResolverPosture,
    ): CanaryPlan {
        // Memoised because the same resolver is referenced by many features and a real
        // posture lookup can be expensive.
        val postures = HashMap<String, ResolverPosture>()

        fun posture(resolverId: String): ResolverPosture = postures.getOrPut(resolverId) { postureOf(resolverId) }

        val business = current.packageName == BUSINESS_PACKAGE
        val declaredVersions =
            features.flatMap { feature ->
                if (business) feature.supportedBusinessVersions else feature.supportedWhatsAppVersions
            }
        val declaredMatch = TargetVersions.isSupported(current.versionName, declaredVersions)
        val newBuildDetected = current.differsFrom(previous) || !declaredMatch

        val enabled = ArrayList<String>()
        val deferred = ArrayList<String>()
        val blocked = ArrayList<String>()

        for (feature in features) {
            val required = feature.requiredResolvers.map { posture(it) }
            when {
                // A missing required resolver is decisive: no amount of caution makes the
                // feature runnable here, and pretending otherwise would install a hook on a
                // target that does not exist.
                required.any { it == ResolverPosture.MISSING } -> blocked.add(feature.id)

                // On an unverified build, a required resolver that only works through a
                // fallback means the feature's assumptions are already wrong; it waits for
                // human verification rather than loading on a path nobody has tested.
                newBuildDetected && required.any { it == ResolverPosture.FALLBACK } ->
                    deferred.add(feature.id)

                else -> enabled.add(feature.id)
            }
        }

        val criticalResolvers =
            features
                .filter { it.isCritical }
                .flatMap { it.requiredResolvers }
        val referenced = features.flatMap { it.requiredResolvers + it.optionalResolvers }
        val optionalResolvers = referenced.toSet() - criticalResolvers.toSet()

        val summary =
            CompatibilitySummary.from(
                packageName = current.packageName,
                whatsappVersion = current.versionName,
                criticalResolvers = criticalResolvers,
                optionalResolvers = optionalResolvers,
                disabledFeatures = blocked.size,
                postureOf = ::posture,
            )

        return CanaryPlan(
            newBuildDetected = newBuildDetected,
            enabled = enabled,
            deferred = deferred,
            blocked = blocked,
            summary = summary,
        )
    }

    companion object {
        /** The target package name of WhatsApp Business. */
        const val BUSINESS_PACKAGE: String = SupportedPackages.WHATSAPP_BUSINESS
    }
}
