package com.wax.module.platform

/**
 * The single source of truth for every package name this module refers to.
 *
 * Package names used to be spelled out as string literals in seven different
 * files, which is how the module ended up hooking applications it does not
 * support: [AntiUpdater] installed a `PackageInstaller.createSession` hook in
 * every process on the device, and the entry point ran every hook stage for
 * every package. Every one of those sites now reads from here, and the hook
 * entry point refuses anything that is not in [HOOK_SCOPE].
 *
 * Two different sets exist on purpose and must not be merged:
 *
 *  * [ALL] is the WhatsApp family. These are the only packages the module
 *    enhances, and the only ones feature loading ever runs in.
 *  * [HOOK_SCOPE] additionally contains the system framework and the settings
 *    provider. They are not targets, they are infrastructure: the package
 *    visibility bypass and the install-downgrade patch have to run inside
 *    `system_server`, and the settings bridge is answered from
 *    `com.android.providers.settings`.
 */
object SupportedPackages {
    /** WhatsApp, the main target. */
    const val WHATSAPP: String = "com.whatsapp"

    /** WhatsApp Business, the second target. */
    const val WHATSAPP_BUSINESS: String = "com.whatsapp.w4b"

    /**
     * The Android framework package, which is also the name of the framework
     * process (`system_server`). Hooks that patch package visibility and install
     * downgrade checks run here.
     *
     * LSPosed displays this entry as "System Framework" and stores it internally
     * as `system`; the [HOOK_SCOPE] name `android` is what arrives in the hook
     * callback.
     */
    const val SYSTEM_FRAMEWORK: String = "android"

    /**
     * The system settings provider. [com.wax.module.xposed.bridge.ScopeHook]
     * answers `Settings.System` bridge calls from this process so a hooked
     * WhatsApp can reach the module's content providers on Android 11 and later.
     */
    const val SETTINGS_PROVIDER: String = "com.android.providers.settings"

    /** The packages this module enhances. Nothing outside this set gets features. */
    @JvmField
    val ALL: Set<String> = linkedSetOf(WHATSAPP, WHATSAPP_BUSINESS)

    /** The framework process name, used when the entry has to check the process. */
    const val SYSTEM_PROCESS: String = "android"

    /**
     * Every package the module is allowed to install a hook into: its targets
     * plus the two infrastructure processes described above.
     *
     * Anything outside this set is rejected by the entry point before a single
     * hook is installed, so a misconfigured LSPosed scope cannot make the module
     * run inside an unrelated application.
     */
    @JvmField
    val HOOK_SCOPE: Set<String> = linkedSetOf(WHATSAPP, WHATSAPP_BUSINESS, SYSTEM_FRAMEWORK, SETTINGS_PROVIDER)

    /** Human-readable names, used by the compatibility and diagnostics screens. */
    @JvmField
    val DISPLAY_NAMES: Map<String, String> =
        mapOf(
            WHATSAPP to "WhatsApp",
            WHATSAPP_BUSINESS to "WhatsApp Business",
        )

    /**
     * Whether [packageName] is a package the module enhances.
     *
     * Null is rejected on purpose: an unresolved package name is a failure to
     * identify the process, and guessing a target there is how a hook ends up in
     * the wrong app.
     */
    @JvmStatic
    fun isTarget(packageName: String?): Boolean = packageName != null && packageName in ALL

    /** Whether [packageName] is a target or one of the infrastructure processes. */
    @JvmStatic
    fun isInHookScope(packageName: String?): Boolean = packageName != null && packageName in HOOK_SCOPE

    /** Whether [packageName] is the system framework package and process. */
    @JvmStatic
    fun isSystemFramework(
        packageName: String?,
        processName: String?,
    ): Boolean = packageName == SYSTEM_FRAMEWORK && processName == SYSTEM_PROCESS

    /** The display name for a target package, or the package name itself if unknown. */
    @JvmStatic
    fun displayName(packageName: String): String = DISPLAY_NAMES[packageName] ?: packageName
}
