package com.wax.module.platform

import com.wax.module.resolver.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The all-features-free contract, from the outside.
 *
 * The tests are written against the two failure modes that matter. The first is a *gate*
 * reappearing: an identifier or a preference key that encodes an entitlement, which is why the
 * audit is exercised with planted violations rather than only with the real catalog. The second
 * is a *migration* that is too broad or too narrow: removing a real setting, or leaving an old
 * key behind for something to read again.
 */
class NoPaywallContractTest {
    @Before
    fun setUp() {
        FeatureRegistry.clear()
    }

    private fun validMetadata(
        id: String,
        preferenceKeys: List<String> = listOf("wae.test"),
    ): FeatureMetadata =
        FeatureMetadata(
            id = id,
            displayName = "Test feature",
            category = FeatureCategory.PRIVACY,
            preferenceKeys = preferenceKeys,
            startupPolicy = StartupPolicy.LAZY,
            requiredResolvers = emptyList(),
            optionalResolvers = emptyList(),
            permissions = emptyList(),
            supportedWhatsAppVersions = listOf("2.26.40.xx"),
            supportedBusinessVersions = listOf("2.26.39.xx"),
            compatibilityConfidence = Confidence.EXACT,
            fallbackBehavior = FallbackBehavior.DISABLE_FEATURE,
            diagnostics = DiagnosticsMetadata(),
            tests = listOf("NoPaywallContractTest"),
        )

    // --- audit --------------------------------------------------------------------------

    @Test
    fun theRealCatalogHasNoInternalAccessGate() {
        val violations = NoPaywallContract.audit(PlatformFeatureCatalog.metadata())
        assertTrue(NoPaywallContract.describe(violations), violations.isEmpty())
    }

    @Test
    fun aGateShapedFeatureIdIsReported() {
        val violations = NoPaywallContract.audit(listOf(validMetadata("privacy.premiumOnly")))
        assertTrue(violations.isNotEmpty())
        assertTrue(violations.map { it.token }.contains("premiumonly"))
        assertTrue(violations.all { it.subject.contains("privacy.premiumOnly") })
    }

    @Test
    fun aGateShapedPreferenceKeyIsReported() {
        val violations = NoPaywallContract.audit(listOf(validMetadata("privacy.test", listOf("wae.has_license"))))
        assertTrue(violations.isNotEmpty())
        assertTrue(violations.map { it.token }.contains("haslicense"))
    }

    @Test
    fun ordinarySupportWordingIsNotAGate() {
        // A Ko-fi link, a thank-you screen or a help page about donations is allowed to exist;
        // only an entitlement-shaped identifier is a violation.
        assertTrue(NoPaywallContract.gateTokensIn("support_development").isEmpty())
        assertTrue(NoPaywallContract.gateTokensIn("wae.support.kofi").isEmpty())
        assertTrue(NoPaywallContract.gateTokensIn("supported_versions").isEmpty())
    }

    @Test
    fun camelCaseAndSnakeCaseAgreeAboutWhatAGateIs() {
        assertEquals(NoPaywallContract.gateTokensIn("is_premium_only"), NoPaywallContract.gateTokensIn("isPremiumOnly"))
        assertTrue(NoPaywallContract.gateTokensIn("isPremiumOnly").contains("premiumonly"))
    }

    @Test
    fun anEmptyAuditSaysSo() {
        assertEquals("No internal access gate was found in the feature catalog.", NoPaywallContract.describe(emptyList()))
    }

    @Test
    fun paymentWordingIsDetectedInPresentationText() {
        assertTrue(NoPaywallContract.containsPaymentWording("Donate to unlock this feature"))
        assertTrue(NoPaywallContract.containsPaymentWording("Premium only"))
        assertFalse(NoPaywallContract.containsPaymentWording("Experimental — turn it on from Labs first."))
        assertFalse(NoPaywallContract.containsPaymentWording("Unavailable — not supported on this WhatsApp version."))
    }

    // --- migration ----------------------------------------------------------------------

    private fun seededStore(): InMemoryKeyValueStore =
        InMemoryKeyValueStore(
            mapOf(
                "is_premium" to "true",
                "wae.privacy.active" to "builtin.ghost",
                "antirevoke" to "true",
                "thememode" to "dark",
                "waxtarget.business.is_premium" to "true",
                "waxtarget.whatsapp.supporter_tier" to "gold",
                "unlocked_features" to "audio,video",
                "wae.outgoing.policy.global" to "{}",
            ),
        )

    @Test
    fun legacyEntitlementKeysAreFoundWhateverTheScopePrefix() {
        val found = NoPaywallContract.legacyEntitlementKeysIn(seededStore())
        assertEquals(
            listOf("is_premium", "unlocked_features", "waxtarget.business.is_premium", "waxtarget.whatsapp.supporter_tier"),
            found,
        )
    }

    @Test
    fun migrationRemovesLegacyKeysAndLeavesEveryRealSettingByteForByteIntact() {
        val store = seededStore()
        val before = store.keys().associateWith { store.getString(it) }
        val removed = NoPaywallContract.stripLegacyEntitlements(store)
        assertEquals(4, removed.size)
        val after = store.keys().associateWith { store.getString(it) }
        assertEquals(before.filterKeys { it !in removed }, after)
        assertEquals(
            mapOf(
                "wae.privacy.active" to "builtin.ghost",
                "antirevoke" to "true",
                "thememode" to "dark",
                "wae.outgoing.policy.global" to "{}",
            ),
            after,
        )
    }

    @Test
    fun migrationIsSafeToRepeatAtEveryStart() {
        val store = seededStore()
        NoPaywallContract.stripLegacyEntitlements(store)
        assertTrue(NoPaywallContract.stripLegacyEntitlements(store).isEmpty())
    }

    @Test
    fun migrationOnACleanStoreChangesNothing() {
        val store = InMemoryKeyValueStore(mapOf("antirevoke" to "true"))
        assertTrue(NoPaywallContract.stripLegacyEntitlements(store).isEmpty())
        assertEquals(mapOf("antirevoke" to "true"), store.keys().associateWith { store.getString(it) })
    }

    @Test
    fun aFormerEntitlementNeverChangesWhatAFeatureReports() {
        // The migration removes the keys, and the availability policy has no input for them in
        // the first place, so a user who once had an entitlement resolves to exactly the same
        // configuration as everyone else.
        val store = seededStore()
        NoPaywallContract.stripLegacyEntitlements(store)
        val facts = FeatureFacts(target = TargetApp.WHATSAPP, installedVersion = "2.26.40.21")
        PlatformFeatureCatalog.metadata().forEach { metadata ->
            assertEquals(
                FeatureAvailability.AVAILABLE,
                FeatureAccessPolicy.evaluate(metadata, facts).availability,
            )
        }
    }

    @Test
    fun noDonationOrSupportStateCanBeExpressedAsAFeatureInput() {
        // Structural rather than behavioural: the facts a feature is evaluated from have no
        // field for a payment state, and the gate audit is the check that keeps it that way.
        val fieldNames = FeatureFacts::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(
            "FeatureFacts must not carry an entitlement: $fieldNames",
            NoPaywallContract.gateTokensIn(fieldNames.joinToString(" ")).isEmpty(),
        )
    }
}
