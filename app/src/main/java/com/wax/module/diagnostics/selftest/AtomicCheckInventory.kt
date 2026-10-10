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
            Definition(
                id = ENV_ANDROID,
                title = "Android version and ABI",
                scope = "device",
                level = EvidenceLevel.L0_PACKAGE,
                dependsOn = emptyList(),
                expected = "Android version, SDK level and ABI are readable",
                remediation = "",
                severity = "info",
            ),
            Definition(
                id = ENV_TARGET,
                title = "WhatsApp version and package",
                scope = "target",
                level = EvidenceLevel.L0_PACKAGE,
                dependsOn = listOf(ENV_ANDROID),
                expected = "Target package name and version are readable",
                remediation = "",
                severity = "info",
            ),
            Definition(
                id = ENV_SCOPE,
                title = "Framework scope",
                scope = "framework",
                level = EvidenceLevel.L0_PACKAGE,
                dependsOn = listOf(ENV_TARGET),
                expected = "Target package is inside the module scope",
                remediation = "",
                severity = "info",
            ),
            Definition(
                id = FRAMEWORK_API102,
                title = "libxposed API 102 runtime",
                scope = "framework",
                level = EvidenceLevel.L0_PACKAGE,
                dependsOn = listOf(ENV_SCOPE),
                expected = "The modern entry loaded and reports API 102",
                remediation = "",
                severity = "high",
            ),
            Definition(
                id = MODULE_LOADED,
                title = "Module loaded",
                scope = "module",
                level = EvidenceLevel.L1_LIFECYCLE,
                dependsOn = listOf(FRAMEWORK_API102),
                expected = "The module callback fired",
                remediation = "",
                severity = "high",
            ),
            Definition(
                id = APP_ATTACH,
                title = "Application.attach observed",
                scope = "target",
                level = EvidenceLevel.L1_LIFECYCLE,
                dependsOn = listOf(MODULE_LOADED),
                expected = "Application.attach was intercepted",
                remediation = "",
                severity = "high",
            ),
            Definition(
                id = PACKAGE_CALLBACK,
                title = "Target package callback",
                scope = "target",
                level = EvidenceLevel.L1_LIFECYCLE,
                dependsOn = listOf(MODULE_LOADED),
                expected = "The target package callback fired",
                remediation = "",
                severity = "high",
            ),
            Definition(
                id = MANAGER_IPC,
                title = "Manager IPC authentication",
                scope = "manager",
                level = EvidenceLevel.L1_LIFECYCLE,
                dependsOn = listOf(APP_ATTACH),
                expected = "A UID-authorized provider call was accepted",
                remediation = "",
                severity = "high",
            ),
            Definition(
                id = HEARTBEAT,
                title = "Runtime heartbeat",
                scope = "manager",
                level = EvidenceLevel.L1_LIFECYCLE,
                dependsOn = listOf(MANAGER_IPC),
                expected = "A fresh heartbeat exists for this target",
                remediation = "",
                severity = "medium",
            ),
            Definition(
                id = DEXKIT_NATIVE,
                title = "DexKit native library",
                scope = "resolver",
                level = EvidenceLevel.L2_RESOLVER,
                dependsOn = listOf(MANAGER_IPC),
                expected = "The DexKit library loads",
                remediation = "",
                severity = "high",
            ),
            Definition(
                id = CONTACT_CLASS,
                title = "Contact class resolver",
                scope = "resolver",
                level = EvidenceLevel.L2_RESOLVER,
                dependsOn = listOf(DEXKIT_NATIVE),
                expected = "Exactly one contact class candidate",
                remediation = "Report the WhatsApp build",
                severity = "high",
            ),
            Definition(
                id = CONTACT_DATA_CLASS,
                title = "Contact data class resolver",
                scope = "resolver",
                level = EvidenceLevel.L2_RESOLVER,
                dependsOn = listOf(CONTACT_CLASS),
                expected = "Exactly one WaContactData candidate",
                remediation = "The contact data class was renamed or removed in this build",
                severity = "high",
            ),
            Definition(
                id = JID_CLASS,
                title = "JID class resolver",
                scope = "resolver",
                level = EvidenceLevel.L2_RESOLVER,
                dependsOn = listOf(DEXKIT_NATIVE),
                expected = "Exactly one jid.Jid candidate",
                remediation = "The JID class suffix no longer matches this build",
                severity = "high",
            ),
            Definition(
                id = JID_RAW_STRING,
                title = "JID raw-string accessor",
                scope = "resolver",
                level = EvidenceLevel.L2_RESOLVER,
                dependsOn = listOf(JID_CLASS),
                expected = "Exactly one unambiguous raw-string method",
                remediation = "Several methods match; the accessor cannot be chosen safely",
                severity = "high",
            ),
            Definition(
                id = MESSAGE_CLASS,
                title = "Message class resolver",
                scope = "resolver",
                level = EvidenceLevel.L2_RESOLVER,
                dependsOn = listOf(DEXKIT_NATIVE),
                expected = "Exactly one message class candidate",
                remediation = "",
                severity = "high",
            ),
            Definition(
                id = MESSAGE_KEY_CLASS,
                title = "Message key resolver",
                scope = "resolver",
                level = EvidenceLevel.L2_RESOLVER,
                dependsOn = listOf(MESSAGE_CLASS),
                expected = "Exactly one message key class candidate",
                remediation = "",
                severity = "high",
            ),
            Definition(
                id = REGISTRY,
                title = "Feature registry",
                scope = "registry",
                level = EvidenceLevel.L2_RESOLVER,
                dependsOn = listOf(MANAGER_IPC),
                expected = "Every registered feature has one id and a factory",
                remediation = "",
                severity = "medium",
            ),
            Definition(
                id = PREF_READBACK,
                title = "Preference readback",
                scope = "preference",
                level = EvidenceLevel.L2_RESOLVER,
                dependsOn = listOf(REGISTRY),
                expected = "A requested preference round-trips to the target",
                remediation = "",
                severity = "medium",
            ),
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
