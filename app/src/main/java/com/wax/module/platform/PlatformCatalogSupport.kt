package com.wax.module.platform

import com.wax.module.resolver.Confidence

/**
 * The values [PlatformFeatureCatalog] is built from: the version matrix it declares support for,
 * and — further down this file — the builder its table rows use.
 *
 * Both live outside the catalog so the declaration table can be read in one screen. The catalog
 * re-exposes the two lists as `WHATSAPP_VERSIONS` / `BUSINESS_VERSIONS`, which is what every
 * declaration and the compatibility tests read; these are the literals behind them.
 */
internal object PlatformCatalogSupport {
    /** WhatsApp versions the current module declares support for. */
    val WHATSAPP_VERSIONS: List<String> =
        listOf(
            "2.26.32.xx",
            "2.26.34.xx",
            "2.26.35.xx",
            "2.26.36.xx",
            "2.26.37.xx",
            "2.26.38.xx",
            "2.26.39.xx",
            "2.26.40.xx",
        )

    /** WhatsApp Business versions the current module declares support for. */
    val BUSINESS_VERSIONS: List<String> =
        listOf(
            "2.26.32.xx",
            "2.26.34.xx",
            "2.26.35.xx",
            "2.26.36.xx",
            "2.26.37.xx",
            "2.26.38.xx",
            "2.26.39.xx",
        )
}

/**
 * Builds one [FeatureMetadata] declaration from the short form the catalog table uses.
 *
 * `id`, `name`, `category` and `tests` are required; every other field already has the value a
 * declaration should get unless it says otherwise, so a row only names the fields it differs on
 * and adding a field to [FeatureMetadata] is a change here plus the rows that care about it.
 */
internal fun feature(
    id: String,
    name: String,
    category: FeatureCategory,
    policy: StartupPolicy = StartupPolicy.LAZY,
    tests: List<String>,
    preferenceKeys: List<String> = emptyList(),
    requiredResolvers: List<String> = emptyList(),
    optionalResolvers: List<String> = emptyList(),
    fallback: FallbackBehavior = FallbackBehavior.DISABLE_FEATURE,
    critical: Boolean = false,
    permissions: List<String> = emptyList(),
    tags: Set<String> = emptySet(),
    confidence: Confidence = Confidence.EXACT,
    visualImpact: VisualImpact = VisualImpact.NONE,
    stockModeFallback: StockModeFallback = StockModeFallback.NONE_NEEDED,
    riskLevel: RiskLevel = RiskLevel.LOW,
    restartRequirement: RestartRequirement = RestartRequirement.NONE,
    accountSupport: AccountSupport = AccountSupport.ACCOUNT_AWARE,
    availability: FeatureAvailability = FeatureAvailability.AVAILABLE,
): FeatureMetadata =
    FeatureMetadata(
        id = id,
        displayName = name,
        category = category,
        preferenceKeys = preferenceKeys,
        startupPolicy = policy,
        requiredResolvers = requiredResolvers,
        optionalResolvers = optionalResolvers,
        permissions = permissions,
        supportedWhatsAppVersions = PlatformCatalogSupport.WHATSAPP_VERSIONS,
        supportedBusinessVersions = PlatformCatalogSupport.BUSINESS_VERSIONS,
        compatibilityConfidence = confidence,
        fallbackBehavior = fallback,
        diagnostics = DiagnosticsMetadata(critical = critical, tags = tags),
        tests = tests,
        availability = availability,
        visualImpact = visualImpact,
        stockModeFallback = stockModeFallback,
        riskLevel = riskLevel,
        restartRequirement = restartRequirement,
        accountSupport = accountSupport,
    )

/**
 * The declarations that are not part of the milestone table above: the wave of additions
 * added after it, gathered here for the same reason the builder is.
 *
 * [PlatformFeatureCatalog] holds the milestone table and appends these, so the table stays
 * one screen long and its class stays inside the size the static analysis allows. They are
 * still declarations in the same package, registered by the same call, and read by
 * `tools/quality/check_matrix_ids.py`, which reads every source file in this package rather
 * than one file by name — so moving a row out of the table cannot make it undocumented.
 */
internal fun additionDeclarations(): List<FeatureMetadata> =
    listOf(
        // --- feature additions, wave F1 -------------------------------------------------
        // The download policy only changes what is fetched, never what is shown inside
        // WhatsApp, so it stays active under Stock Mode.
        feature(
            PlatformFeatures.MEDIA_POLICY,
            "Per-chat media policy",
            FeatureCategory.MEDIA,
            StartupPolicy.EAGER_NORMAL,
            tests = listOf("MediaPolicyTest"),
            preferenceKeys = listOf("wae.media.policy."),
            optionalResolvers = listOf("loadMediaDownload"),
            tags = setOf("media", "downloads", "policy", "local-only"),
            confidence = Confidence.LIKELY,
            visualImpact = VisualImpact.EXTERNAL_ONLY,
        ),
        // A settings choice the attach flow reads; it injects nothing and can never change
        // how WhatsApp looks, only which picker it opens.
        feature(
            PlatformFeatures.MEDIA_SOURCE_MODE,
            "Photo picker direct mode",
            FeatureCategory.MEDIA,
            StartupPolicy.LAZY,
            tests = listOf("MediaSourcePolicyTest"),
            preferenceKeys = listOf("wae.media.source."),
            tags = setOf("media", "permissions", "picker", "local-only"),
            confidence = Confidence.LIKELY,
            accountSupport = AccountSupport.TARGET_SCOPED,
        ),
        // It adds an entry to the Status composer, so it has a Stock Mode fallback that
        // reaches the same editor from the share sheet instead of from inside WhatsApp.
        feature(
            PlatformFeatures.STATUS_AUDIO_STUDIO,
            "Status Audio Studio",
            FeatureCategory.MEDIA,
            StartupPolicy.LAZY,
            tests = listOf("StatusAudioStudioTest"),
            preferenceKeys = listOf("wae.status.audio."),
            optionalResolvers = listOf("loadStatusComposer", "loadStatusPublish"),
            tags = setOf("status", "audio", "voice-status", "drafts"),
            confidence = Confidence.LIKELY,
            riskLevel = RiskLevel.MEDIUM,
            visualImpact = VisualImpact.INJECTS_UI,
            stockModeFallback = StockModeFallback.SHARE_SHEET,
            accountSupport = AccountSupport.NOT_APPLICABLE,
            restartRequirement = RestartRequirement.TARGET_RESTART,
        ),
        // Notifications are Android's, not WhatsApp's, so the cooldown is invisible inside
        // the hooked app and stays active while Stock Mode is on.
        feature(
            PlatformFeatures.NOTIFICATION_COOLDOWN,
            "Notification burst cooldown",
            FeatureCategory.NOTIFICATION,
            StartupPolicy.EAGER_NORMAL,
            tests = listOf("NotificationCooldownTest"),
            preferenceKeys = listOf("wae.notifications.cooldown."),
            optionalResolvers = listOf("loadNotificationListener"),
            tags = setOf("notifications", "cooldown", "burst"),
            confidence = Confidence.LIKELY,
            visualImpact = VisualImpact.EXTERNAL_ONLY,
        ),
        // The same reasoning as the cooldown, for the same reason: the beep and the floating
        // banner are WA X's own notification, so nothing is drawn inside WhatsApp and the
        // feature survives Stock Mode. Eager because the first activity of a chat has to be
        // seen, and a policy that loads late can only report the ones after it.
        feature(
            PlatformFeatures.PRESENCE_ALERTS,
            "Contact activity alerts",
            FeatureCategory.NOTIFICATION,
            StartupPolicy.EAGER_NORMAL,
            tests = listOf("PresenceAlertTest", "PresenceAlertEngineTest"),
            preferenceKeys = listOf("wae.presence.alert."),
            optionalResolvers = listOf("loadChatState", "loadMediaTransfer"),
            tags = setOf("notifications", "presence", "typing", "recording", "uploads"),
            confidence = Confidence.LIKELY,
            visualImpact = VisualImpact.EXTERNAL_ONLY,
        ),
    )
