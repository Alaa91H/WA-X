package com.wax.module.platform

/**
 * A WhatsApp build WA X can be scoped to.
 *
 * Introduced because the module is moving to a single APK (§13). The two product
 * flavors existed only to give WhatsApp and WhatsApp Business separate application
 * ids, and separate application ids meant separate preference files by accident
 * rather than by design. [TargetPackageRegistry] keeps that separation explicit and
 * independent of how the APK is packaged.
 */
enum class TargetApp(
    /** The application id of the target. */
    val packageName: String,
    /** The name users recognise. */
    val displayName: String,
    /** Short code used in persisted keys and log lines. */
    val code: String,
) {
    /** WhatsApp, the main target. */
    WHATSAPP(SupportedPackages.WHATSAPP, "WhatsApp", "whatsapp"),

    /** WhatsApp Business, the second target. */
    WHATSAPP_BUSINESS(SupportedPackages.WHATSAPP_BUSINESS, "WhatsApp Business", "business"),
    ;

    companion object {
        /** The target for a package name, or null when it is not a supported one. */
        fun fromPackageName(packageName: String?): TargetApp? = entries.firstOrNull { it.packageName == packageName }

        /** The target for a persisted code such as "business". */
        fun fromCode(code: String?): TargetApp? = entries.firstOrNull { it.code == code }

        /** Every supported package name, in declaration order. */
        val allPackageNames: List<String> = entries.map { it.packageName }
    }
}

/**
 * The single authority on which packages WA X hooks.
 *
 * Everything that previously spelled out `com.whatsapp` or `com.whatsapp.w4b`
 * reads from here, so the supported set is stated once and the rest of the codebase
 * compares against [TargetApp] rather than against a string.
 *
 * The two sets are deliberately different and must not be merged:
 *
 *  * [TargetApp.allPackageNames] is the WhatsApp family. These are the only packages
 *    the module enhances and the only ones feature loading runs in.
 *  * [SupportedPackages.HOOK_SCOPE] additionally holds the system framework and the
 *    settings provider. They are not targets, they are infrastructure: the package
 *    visibility bypass and the install-downgrade patch have to run inside
 *    `system_server`, and the settings bridge is answered from the settings provider.
 */
object TargetPackageRegistry {
    /** The targets, in declaration order. */
    val targets: List<TargetApp> = TargetApp.entries.toList()

    /** Every target package name. */
    val packageNames: Set<String> = TargetApp.allPackageNames.toSet()

    // Convenience aliases. Not `const val`, because an enum property is not a
    // compile-time constant; the literal constants remain on [SupportedPackages] for
    // the few call sites that need a compile-time value.
    val WHATSAPP: String = TargetApp.WHATSAPP.packageName
    val WHATSAPP_BUSINESS: String = TargetApp.WHATSAPP_BUSINESS.packageName

    /**
     * Whether [packageName] is a package WA X enhances.
     *
     * Null is rejected on purpose: an unresolved package name is a failure to
     * identify the process, and guessing a target there is how a hook ends up in the
     * wrong application.
     */
    @JvmStatic
    fun isTarget(packageName: String?): Boolean = TargetApp.fromPackageName(packageName) != null

    /** The target for [packageName], or null. */
    @JvmStatic
    fun targetOf(packageName: String?): TargetApp? = TargetApp.fromPackageName(packageName)

    /**
     * Whether the module may install a hook into [packageName] at all.
     *
     * Targets plus the infrastructure processes. Anything outside this set is rejected
     * by the entry point before a single hook is installed.
     */
    @JvmStatic
    fun isInHookScope(packageName: String?): Boolean = packageName != null && packageName in SupportedPackages.HOOK_SCOPE

    /** The display name for a package, falling back to the package name. */
    @JvmStatic
    fun displayName(packageName: String?): String = TargetApp.fromPackageName(packageName)?.displayName ?: (packageName ?: "unknown")
}
