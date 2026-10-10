package com.wax.module.diagnostics.selftest

/**
 * The machine-readable inventory of atomic checks (#170).
 *
 * Every entry has a stable id, a scope, its preconditions, the dependency it
 * sits behind and the pipeline level it can reach. The order of
 * [PIPELINE] is the *causal* order — resolver before hook before callback —
 * because "first failed dependency" is only meaningful if the sequence is the
 * real chain, and [P0_PRIVACY_ORDER] is the owner's priority order for the
 * everyday privacy features.
 */
object AtomicCheckInventory {
    data class Definition(
        val id: String,
        val title: String,
        val scope: String,
        val level: EvidenceLevel,
        val dependsOn: List<String>,
        val expected: String,
        val remediation: String,
        val severity: String,
        val externalConfirmationRequired: Boolean = false,
    )

    // Stage 1: environment and framework.
    const val ENV_ANDROID = "env.android"
    const val ENV_TARGET = "env.target"
    const val ENV_SCOPE = "env.scope"
    const val FRAMEWORK_API102 = "framework.api102"

    // Stage 2: lifecycle and manager IPC.
    const val MODULE_LOADED = "lifecycle.module_loaded"
    const val APP_ATTACH = "lifecycle.application_attach"
    const val PACKAGE_CALLBACK = "lifecycle.package_callback"
    const val MANAGER_IPC = "lifecycle.manager_ipc"
    const val HEARTBEAT = "lifecycle.heartbeat"

    // Stage 3: resolvers.
    const val DEXKIT_NATIVE = "resolver.dexkit_native"
    const val CONTACT_CLASS = "resolver.contact_class"
    const val CONTACT_DATA_CLASS = "resolver.contact_data_class"
    const val JID_CLASS = "resolver.jid_class"
    const val JID_RAW_STRING = "resolver.jid_raw_string"
    const val MESSAGE_CLASS = "resolver.message_class"
    const val MESSAGE_KEY_CLASS = "resolver.message_key_class"

    // Stage 4: registry and preferences.
    const val REGISTRY = "registry.inventory"
    const val PREF_READBACK = "preference.readback"

    // Stage 5: hook lifecycle, per feature.
    const val HOOK_PREFIX = "hook."
    const val TRIGGER_PREFIX = "trigger."

    /**
     * Causal order. `CONTACT_DATA_CLASS_MISSING` sits before `JID_CLASS` and
     * both sit before the feature that depends on the pair, which is what makes
     * the observed three-step chain collapse into one cluster.
     */
    val PIPELINE: List<Definition> =
        listOf(
            Definition(ENV_ANDROID, "Android version and ABI", "device", EvidenceLevel.L0_PACKAGE,
                emptyList(), "Android version, SDK level and ABI are readable", "", "info"),
            Definition(ENV_TARGET, "WhatsApp version and package", "target", EvidenceLevel.L0_PACKAGE,
                listOf(ENV_ANDROID), "Target package name and version are readable", "", "info"),
            Definition(ENV_SCOPE, "Framework scope", "framework", EvidenceLevel.L0_PACKAGE,
                listOf(ENV_TARGET), "Target package is inside the module scope", "", "info"),
            Definition(FRAMEWORK_API102, "libxposed API 102 runtime", "framework", EvidenceLevel.L0_PACKAGE,
                listOf(ENV_SCOPE), "The modern entry loaded and reports API 102", "", "high"),

            Definition(MODULE_LOADED, "Module loaded", "module", EvidenceLevel.L1_LIFECYCLE,
                listOf(FRAMEWORK_API102), "The module callback fired", "", "high"),
            Definition(APP_ATTACH, "Application.attach observed", "target", EvidenceLevel.L1_LIFECYCLE,
                listOf(MODULE_LOADED), "Application.attach was intercepted", "", "high"),
            Definition(PACKAGE_CALLBACK, "Target package callback", "target", EvidenceLevel.L1_LIFECYCLE,
                listOf(MODULE_LOADED), "The target package callback fired", "", "high"),
            Definition(MANAGER_IPC, "Manager IPC authentication", "manager", EvidenceLevel.L1_LIFECYCLE,
                listOf(APP_ATTACH), "A UID-authorized provider call was accepted", "", "high"),
            Definition(HEARTBEAT, "Runtime heartbeat", "manager", EvidenceLevel.L1_LIFECYCLE,
                listOf(MANAGER_IPC), "A fresh heartbeat exists for this target", "", "medium"),

            Definition(DEXKIT_NATIVE, "DexKit native library", "resolver", EvidenceLevel.L2_RESOLVER,
                listOf(MANAGER_IPC), "The DexKit library loads", "", "high"),
            Definition(CONTACT_CLASS, "Contact class resolver", "resolver", EvidenceLevel.L2_RESOLVER,
                listOf(DEXKIT_NATIVE), "Exactly one contact class candidate", "Report the WhatsApp build", "high"),
            Definition(CONTACT_DATA_CLASS, "Contact data class resolver", "resolver",
                EvidenceLevel.L2_RESOLVER, listOf(CONTACT_CLASS),
                "Exactly one WaContactData candidate",
                "The contact data class was renamed or removed in this build", "high"),
            Definition(JID_CLASS, "JID class resolver", "resolver", EvidenceLevel.L2_RESOLVER,
                listOf(DEXKIT_NATIVE), "Exactly one jid.Jid candidate",
                "The JID class suffix no longer matches this build", "high"),
            Definition(JID_RAW_STRING, "JID raw-string accessor", "resolver", EvidenceLevel.L2_RESOLVER,
                listOf(JID_CLASS), "Exactly one unambiguous raw-string method",
                "Several methods match; the accessor cannot be chosen safely", "high"),
            Definition(MESSAGE_CLASS, "Message class resolver", "resolver", EvidenceLevel.L2_RESOLVER,
                listOf(DEXKIT_NATIVE), "Exactly one message class candidate", "", "high"),
            Definition(MESSAGE_KEY_CLASS, "Message key resolver", "resolver", EvidenceLevel.L2_RESOLVER,
                listOf(MESSAGE_CLASS), "Exactly one message key class candidate", "", "high"),

            Definition(REGISTRY, "Feature registry", "registry", EvidenceLevel.L2_RESOLVER,
                listOf(MANAGER_IPC), "Every registered feature has one id and a factory", "", "medium"),
            Definition(PREF_READBACK, "Preference readback", "preference", EvidenceLevel.L2_RESOLVER,
                listOf(REGISTRY), "A requested preference round-trips to the target", "", "medium"),
        )

    /** Owner's priority order for the everyday privacy behaviours. */
    val P0_PRIVACY_ORDER: List<String> =
        listOf(
            "hide_second_tick",
            "hide_blue_tick",
            "show_blue_after_reply",
            "hide_typing",
            "hide_recording",
            "last_seen_online",
            "status_viewed",
        )

    /**
     * A feature check depends on the resolver chain it actually uses, so a
     * broken contact/JID chain blocks exactly the features that read it and
     * nothing else.
     */
    fun featureCheck(
        featureId: String,
        title: String,
        resolvers: List<String>,
        preferenceKey: String,
        externalConfirmationRequired: Boolean,
    ): Definition = Definition(
        id = HOOK_PREFIX + featureId,
        title = "$title hook registered",
        scope = "feature:$featureId",
        level = EvidenceLevel.L3_HOOK,
        dependsOn = resolvers + PREF_READBACK,
        expected = "The hook for $featureId is installed (preference $preferenceKey)",
        remediation = "Enable the feature in the WA X Control Center, then restart WhatsApp",
        severity = "high",
        externalConfirmationRequired = externalConfirmationRequired,
    )

    fun triggerCheck(featureId: String, title: String, hookId: String): Definition = Definition(
        id = TRIGGER_PREFIX + featureId,
        title = "$title callback invoked",
        scope = "feature:$featureId",
        level = EvidenceLevel.L4_TRIGGER,
        dependsOn = listOf(hookId),
        expected = "The hook callback fired at least once",
        remediation = "Use the feature in WhatsApp, then run the scan again",
        severity = "medium",
    )

    fun all(): List<Definition> = PIPELINE

    fun byId(id: String): Definition? = all().firstOrNull { it.id == id }

    /** The causal order, with feature checks appended after their pipeline. */
    fun orderWith(features: List<Definition>): List<String> =
        PIPELINE.map { it.id } + features.map { it.id }
}
