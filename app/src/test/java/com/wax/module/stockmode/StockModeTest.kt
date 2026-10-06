package com.wax.module.stockmode

import com.wax.module.platform.AccountSupport
import com.wax.module.platform.FeatureCategory
import com.wax.module.platform.FeatureMetadata
import com.wax.module.platform.PlatformFeatureCatalog
import com.wax.module.platform.PlatformFeatures
import com.wax.module.platform.RiskLevel
import com.wax.module.platform.StartupPolicy
import com.wax.module.platform.StockModeFallback
import com.wax.module.platform.VisualImpact
import com.wax.module.resolver.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stock WhatsApp Mode.
 *
 * The contract is a visual one, so the tests are about what a user would see: which features are
 * allowed to touch the interface, that the preferences Stock Mode hides come back untouched, and
 * that the parity check actually fails when a screen gains, loses or reorders an item. A check
 * that only ever passes proves nothing, so each failure mode is planted deliberately.
 */
class StockModeTest {
    private fun metadata(
        id: String,
        impact: VisualImpact,
        fallback: StockModeFallback = StockModeFallback.NONE_NEEDED,
    ): FeatureMetadata =
        FeatureMetadata(
            id = id,
            displayName = "Test feature",
            category = FeatureCategory.THEME,
            preferenceKeys = listOf("wae.test"),
            startupPolicy = StartupPolicy.LAZY,
            requiredResolvers = emptyList(),
            optionalResolvers = emptyList(),
            permissions = emptyList(),
            supportedWhatsAppVersions = listOf("2.26.40.xx"),
            supportedBusinessVersions = listOf("2.26.39.xx"),
            compatibilityConfidence = Confidence.EXACT,
            fallbackBehavior = com.wax.module.platform.FallbackBehavior.DISABLE_FEATURE,
            diagnostics =
                com.wax.module.platform
                    .DiagnosticsMetadata(),
            tests = listOf("StockModeTest"),
            visualImpact = impact,
            stockModeFallback = fallback,
            riskLevel = RiskLevel.LOW,
            accountSupport = AccountSupport.NOT_APPLICABLE,
        )

    // --- presentation decisions ---------------------------------------------------------

    @Test
    fun aFeatureThatAddsInterfaceIsSuppressedWhileStockModeIsOn() {
        val outcome = StockModePolicy.decide(metadata("theme.badge", VisualImpact.INJECTS_UI, StockModeFallback.MANAGER_ONLY), true)
        assertEquals(PresentationDecision.SUPPRESSED, outcome.decision)
        assertFalse(outcome.isVisibleInsideWhatsApp)
        assertTrue(outcome.reason.contains("configure it from WA X"))
    }

    @Test
    fun theSameFeatureIsAllowedWhenStockModeIsOff() {
        val outcome = StockModePolicy.decide(metadata("theme.badge", VisualImpact.INJECTS_UI, StockModeFallback.MANAGER_ONLY), false)
        assertEquals(PresentationDecision.NATIVE_STOCK, outcome.decision)
        assertTrue(outcome.isVisibleInsideWhatsApp)
    }

    @Test
    fun hidingReorderingAndRestylingAreAllSuppressed() {
        listOf(VisualImpact.HIDES_NATIVE_UI, VisualImpact.REORDERS_NATIVE_UI, VisualImpact.RESTYLES_NATIVE_UI).forEach { impact ->
            val outcome = StockModePolicy.decide(metadata("theme.x", impact, StockModeFallback.MANAGER_ONLY), true)
            assertEquals("$impact must be suppressed", PresentationDecision.SUPPRESSED, outcome.decision)
        }
    }

    @Test
    fun invisibleWorkKeepsRunningInBothModes() {
        listOf(VisualImpact.NONE, VisualImpact.MODIFIES_NATIVE_STATE).forEach { impact ->
            val metadata = metadata("privacy.quiet", impact)
            assertEquals(PresentationDecision.NATIVE_STOCK, StockModePolicy.decide(metadata, true).decision)
            assertTrue(StockModePolicy.isActiveInWhatsApp(metadata, true))
        }
    }

    @Test
    fun aSuppressedPolicyKeepsRunningEvenWithNoControlVisible() {
        // The case this rule exists for: Automatic View Once hides its send-time cue under Stock
        // Mode, and the policy the user configured still applies to every send.
        val metadata = metadata("outgoing.example", VisualImpact.INJECTS_UI, StockModeFallback.POLICY_ONLY)
        assertFalse(StockModePolicy.decide(metadata, true).isVisibleInsideWhatsApp)
        assertTrue(StockModePolicy.isActiveInWhatsApp(metadata, true))
    }

    @Test
    fun aSuppressedControlDoesNotKeepRunningInsideWhatsApp() {
        // Relocating the control is not the same as preserving the behaviour: a feature whose
        // only surface was an injected button stops working in WhatsApp, and the user drives it
        // from where the fallback says.
        val metadata = metadata("theme.badge", VisualImpact.INJECTS_UI, StockModeFallback.MANAGER_ONLY)
        assertFalse(StockModePolicy.decide(metadata, true).isVisibleInsideWhatsApp)
        assertFalse(StockModePolicy.isActiveInWhatsApp(metadata, true))
        assertTrue(StockModePolicy.isActiveInWhatsApp(metadata, false))
    }

    @Test
    fun anExternalOnlyFeatureIsNeverSuppressed() {
        listOf(true, false).forEach { stockMode ->
            val outcome = StockModePolicy.decide(metadata("outgoing.studio", VisualImpact.EXTERNAL_ONLY), stockMode)
            assertEquals(PresentationDecision.EXTERNAL, outcome.decision)
            assertTrue(outcome.isVisibleInsideWhatsApp)
        }
    }

    @Test
    fun theAutomaticallyEnabledViewOnceCueIsPolicyOnlyUnderStockMode() {
        val feature = PlatformFeatureCatalog.metadata().single { it.id == PlatformFeatures.AUTO_VIEW_ONCE }
        val outcome = StockModePolicy.decide(feature, stockModeEnabled = true)
        assertEquals(PresentationDecision.SUPPRESSED, outcome.decision)
        assertEquals(StockModeFallback.POLICY_ONLY, outcome.fallback)
        assertTrue(outcome.reason.contains("policy you already configured"))
    }

    @Test
    fun theCatalogSatisfiesTheStockModeContractInFull() {
        val catalog = PlatformFeatureCatalog.metadata()
        val outcomes = StockModePolicy.decideAll(catalog, stockModeEnabled = true)
        outcomes
            .filter { it.decision == PresentationDecision.SUPPRESSED }
            .forEach { outcome ->
                assertNotEquals(
                    "${outcome.featureId} is suppressed, so it needs a real fallback",
                    StockModeFallback.NONE_NEEDED,
                    outcome.fallback,
                )
            }
        // Exactly the features that inject interface of their own are suppressed. The list is
        // asserted in full rather than counted, so a new feature that adds something to
        // WhatsApp has to state its fallback here instead of slipping through.
        assertEquals(
            listOf(PlatformFeatures.AUTO_VIEW_ONCE, PlatformFeatures.STATUS_AUDIO_STUDIO),
            StockModePolicy.suppressedFeatures(catalog, true).map { it.featureId },
        )
        // Both of them declare a real fallback, so their behaviour survives the suppression
        // instead of just vanishing with the interface.
        val viewOnce = outcomes.single { it.featureId == PlatformFeatures.AUTO_VIEW_ONCE }
        assertEquals(StockModeFallback.POLICY_ONLY, viewOnce.fallback)
        assertTrue(StockModePolicy.isActiveInWhatsApp(catalog.single { it.id == PlatformFeatures.AUTO_VIEW_ONCE }, true))

        val statusAudio = outcomes.single { it.featureId == PlatformFeatures.STATUS_AUDIO_STUDIO }
        assertEquals(PresentationDecision.SUPPRESSED, statusAudio.decision)
        assertEquals(StockModeFallback.SHARE_SHEET, statusAudio.fallback)
        assertFalse(
            StockModePolicy.isActiveInWhatsApp(catalog.single { it.id == PlatformFeatures.STATUS_AUDIO_STUDIO }, true),
        )
    }

    // --- the saved customisation --------------------------------------------------------

    @Test
    fun enablingStockModeSuppressesVisualPreferencesWithoutDeletingThem() {
        val saved = mapOf("custom_toolbar" to true, "hide_tabs" to true, "menu_icons" to false)
        val on = StockModeState(enabled = true, savedVisualPreferences = saved)
        assertTrue(on.effectiveVisualPreferences.isEmpty())
        assertEquals(saved, on.savedVisualPreferences)
        assertTrue(on.isSuppressingSavedPreferences)
    }

    @Test
    fun theRoundTripThroughStockModeRestoresTheCustomisationExactly() {
        val saved = mapOf("custom_toolbar" to true, "hide_tabs" to true, "menu_icons" to false)
        val original = StockModeState(enabled = false, savedVisualPreferences = saved)
        val restored = original.toggled(true).toggled(false)
        assertEquals(original, restored)
        assertEquals(saved, restored.effectiveVisualPreferences)
        assertFalse(restored.isSuppressingSavedPreferences)
    }

    @Test
    fun disablingStockModeWithNothingSavedIsStillAnEmptyMap() {
        assertEquals(emptyMap<String, Boolean>(), StockModeState(enabled = false).effectiveVisualPreferences)
    }

    @Test
    fun whatsAppAndBusinessKeepIndependentStates() {
        val whatsapp = StockModeState(enabled = true, savedVisualPreferences = mapOf("hide_tabs" to true))
        val business = StockModeState(enabled = false, savedVisualPreferences = mapOf("hide_tabs" to true))
        assertTrue(whatsapp.effectiveVisualPreferences.isEmpty())
        assertEquals(mapOf("hide_tabs" to true), business.effectiveVisualPreferences)
    }

    // --- parity -------------------------------------------------------------------------

    private fun officialConversation(): UiSurfaceSnapshot =
        UiSurfaceSnapshot(UiSurface.CONVERSATION, listOf("Attach", "Camera", "Voice", "Send"))

    @Test
    fun parityHoldsWhenTheScreensAreIndistinguishable() {
        val reference = listOf(officialConversation(), UiSurfaceSnapshot(UiSurface.CHAT_LIST, listOf("Search", "New chat")))
        val report = StockModeParityChecker.compare(reference, reference)
        assertTrue(report.isStockIdentical)
        assertEquals("Every checked screen matches the official build.", report.describe())
    }

    @Test
    fun parityFailsWhenAnItemWasAdded() {
        val reference = listOf(officialConversation())
        val actual = listOf(officialConversation().copy(items = officialConversation().items + "Quick settings"))
        val report = StockModeParityChecker.compare(reference, actual)
        assertFalse(report.isStockIdentical)
        assertEquals(ParityDifferenceKind.ADDED_ITEM, report.differences.single().kind)
        assertEquals("Quick settings", report.differences.single().item)
    }

    @Test
    fun parityFailsWhenAnOfficialItemWasRemoved() {
        val reference = listOf(officialConversation())
        val actual = listOf(officialConversation().copy(items = listOf("Attach", "Camera", "Send")))
        val report = StockModeParityChecker.compare(reference, actual)
        assertEquals(ParityDifferenceKind.REMOVED_ITEM, report.differences.single().kind)
    }

    @Test
    fun parityFailsWhenTheOrderChanged() {
        val reference = listOf(officialConversation())
        val actual = listOf(officialConversation().copy(items = listOf("Camera", "Attach", "Voice", "Send")))
        val report = StockModeParityChecker.compare(reference, actual)
        assertEquals(ParityDifferenceKind.REORDERED_ITEMS, report.differences.single().kind)
    }

    @Test
    fun parityFailsWhenASurfaceIsMissingOrUnexpected() {
        val reference = listOf(officialConversation(), UiSurfaceSnapshot(UiSurface.SETTINGS, listOf("Account")))
        val actual = listOf(officialConversation(), UiSurfaceSnapshot(UiSurface.SEARCH, listOf("Search messages")))
        val report = StockModeParityChecker.compare(reference, actual)
        assertEquals(
            setOf(ParityDifferenceKind.MISSING_SURFACE, ParityDifferenceKind.UNEXPECTED_SURFACE),
            report.differences.map { it.kind }.toSet(),
        )
    }

    @Test
    fun parityIgnoresNothingAboutStructureAndEverythingAboutContent() {
        // Two runs with different message text but identical structure are the same screen: the
        // check only ever sees labels, which is why it can be run on every build.
        val first = listOf(UiSurfaceSnapshot(UiSurface.CHAT_LIST, listOf("Search", "New chat")))
        val second = listOf(UiSurfaceSnapshot(UiSurface.CHAT_LIST, listOf("Search", "New chat")))
        assertTrue(StockModeParityChecker.compare(first, second).isStockIdentical)
    }

    // --- branding -----------------------------------------------------------------------

    @Test
    fun brandingIsDetectedInAnythingThatWouldBeShownInsideWhatsApp() {
        val leaks = StockModeParityChecker.brandingLeaks(listOf("WA X settings", "Open WA Enhancer", "LSPosed module status", "Account"))
        assertEquals(listOf("WA X settings", "Open WA Enhancer", "LSPosed module status"), leaks)
        assertFalse(StockModeParityChecker.isBrandingFree(leaks))
    }

    @Test
    fun ordinaryWhatsAppLabelsAreBrandingFree() {
        val labels =
            listOf(
                "Attach",
                "Camera",
                "Voice message",
                "Privacy",
                "Linked devices",
                "Send",
                "Updates",
                "Status",
                "Settings",
            )
        assertTrue(StockModeParityChecker.brandingLeaks(labels).toString(), StockModeParityChecker.isBrandingFree(labels))
    }

    @Test
    fun brandingDetectionIgnoresCaseAndSpacing() {
        assertFalse(StockModeParityChecker.isBrandingFree(listOf("wa    x")))
        assertFalse(StockModeParityChecker.isBrandingFree(listOf("WA-X")))
    }
}
