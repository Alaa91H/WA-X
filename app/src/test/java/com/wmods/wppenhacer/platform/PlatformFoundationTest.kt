package com.wmods.wppenhacer.platform

import com.wmods.wppenhacer.resolver.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlatformFoundationTest {
    @Before
    fun setUp() {
        FeatureRegistry.clear()
    }

    // --- MiniJson ---------------------------------------------------------------------

    @Test
    fun jsonRoundTripsNestedDocuments() {
        val document =
            jsonObject(
                "name" to jsonString("Midnight"),
                "schema" to jsonNumber(1L),
                "enabled" to jsonBoolean(true),
                "colors" to jsonObject("background" to jsonNumber(0xFF111417L)),
                "days" to jsonStrings(listOf("MONDAY", "FRIDAY")),
            )
        val parsed = MiniJson.parse(MiniJson.write(document))
        val fields = parsed?.objOrNull()
        assertNotNull(fields)
        assertEquals("Midnight", fields!!.string("name"))
        assertEquals(1L, fields.long("schema"))
        assertEquals(true, fields.boolean("enabled"))
        assertEquals(0xFF111417L, fields.obj("colors")!!.long("background"))
        assertEquals(listOf("MONDAY", "FRIDAY"), fields.stringList("days"))
    }

    @Test
    fun malformedJsonYieldsNullInsteadOfThrowing() {
        assertNull(MiniJson.parse("{"))
        assertNull(MiniJson.parse("[1, 2"))
        assertNull(MiniJson.parse("not json at all"))
        assertNull(MiniJson.parse("{\"a\": }"))
    }

    @Test
    fun blankInputYieldsNull() {
        assertNull(MiniJson.parse(null))
        assertNull(MiniJson.parse(""))
        assertNull(MiniJson.parse("   "))
    }

    @Test
    fun stringsWithQuotesAndUnicodeRoundTrip() {
        val original = "quote \" backslash \\ newline \n arabic رمز ١٢٣ \u0007"
        val parsed = MiniJson.parse(MiniJson.write(jsonObject("text" to jsonString(original))))
        assertEquals(original, parsed?.objOrNull()?.string("text"))
    }

    @Test
    fun integralNumbersDoNotGainDecimalPoints() {
        assertEquals("{\"n\":42}", MiniJson.write(jsonObject("n" to jsonNumber(42L))))
    }

    @Test
    fun nonFiniteNumbersBecomeNullRatherThanInvalidJson() {
        val written = MiniJson.write(jsonObject("n" to jsonNumber(Double.NaN)))
        assertEquals("{\"n\":null}", written)
        assertNotNull(MiniJson.parse(written))
    }

    // --- KeyValueStore ----------------------------------------------------------------

    @Test
    fun typedAccessorsRoundTripAndDefault() {
        val store = InMemoryKeyValueStore()
        store.putInt("i", 7)
        store.putLong("l", 9_000_000_000L)
        store.putBoolean("b", true)
        assertEquals(7, store.getInt("i"))
        assertEquals(9_000_000_000L, store.getLong("l"))
        assertTrue(store.getBoolean("b"))
        assertEquals(0, store.getInt("missing"))
        assertEquals(3, store.getInt("missing", 3))
        assertFalse(store.getBoolean("missing"))
    }

    @Test
    fun keysCanBeFilteredByPrefix() {
        val store = InMemoryKeyValueStore()
        store.putString("wae.a.x", "1")
        store.putString("wae.a.y", "2")
        store.putString("wae.b.z", "3")
        assertEquals(setOf("wae.a.x", "wae.a.y"), store.keys("wae.a."))
    }

    // --- FeatureRegistry ---------------------------------------------------------------

    @Test
    fun aCompleteDeclarationIsAccepted() {
        val result = FeatureRegistry.register(validMetadata("privacy.test"))
        assertTrue(result.isAccepted)
        assertNotNull(FeatureRegistry.get("privacy.test"))
    }

    @Test
    fun registrationIsIdempotentForTheSameDeclaration() {
        val metadata = validMetadata("privacy.test")
        assertTrue(FeatureRegistry.register(metadata).isAccepted)
        assertTrue(FeatureRegistry.register(metadata).isAccepted)
        assertEquals(1, FeatureRegistry.count())
    }

    @Test
    fun aConflictingDuplicateIsRejected() {
        FeatureRegistry.register(validMetadata("privacy.test"))
        val conflict = validMetadata("privacy.test").copy(displayName = "Different feature")
        val result = FeatureRegistry.register(conflict)
        assertFalse(result.isAccepted)
        assertTrue((result as RegistrationResult.Rejected).reasons.first().contains("already registered"))
    }

    @Test
    fun aDeclarationWithoutTestsIsRejected() {
        val result = FeatureRegistry.register(validMetadata("privacy.test").copy(tests = emptyList()))
        assertFalse(result.isAccepted)
        assertTrue((result as RegistrationResult.Rejected).reasons.any { it.contains("test") })
    }

    @Test
    fun anInvalidIdIsRejected() {
        val result = FeatureRegistry.register(validMetadata("Privacy.Test Feature"))
        assertFalse(result.isAccepted)
    }

    @Test
    fun resolverOverlapIsRejected() {
        val metadata =
            validMetadata("privacy.test").copy(
                requiredResolvers = listOf("loadA"),
                optionalResolvers = listOf("loadA"),
            )
        val result = FeatureRegistry.register(metadata)
        assertFalse(result.isAccepted)
        assertTrue((result as RegistrationResult.Rejected).reasons.any { it.contains("both required and optional") })
    }

    @Test
    fun aCriticalFeatureWithoutFallbackIsRejected() {
        val metadata =
            validMetadata("platform.test").copy(
                startupPolicy = StartupPolicy.EAGER_CRITICAL,
                diagnostics = DiagnosticsMetadata(critical = true),
                fallbackBehavior = FallbackBehavior.NONE,
            )
        val result = FeatureRegistry.register(metadata)
        assertFalse(result.isAccepted)
        assertTrue((result as RegistrationResult.Rejected).reasons.any { it.contains("fallback") })
    }

    @Test
    fun registrationReportsEveryProblemAtOnce() {
        val metadata =
            validMetadata("privacy.test").copy(
                displayName = "",
                tests = emptyList(),
                supportedWhatsAppVersions = emptyList(),
                supportedBusinessVersions = emptyList(),
            )
        val reasons = (FeatureRegistry.register(metadata) as RegistrationResult.Rejected).reasons
        assertTrue(reasons.size >= 3)
    }

    // --- catalog ----------------------------------------------------------------------

    @Test
    fun everyCatalogDeclarationPassesRegistration() {
        val results = PlatformFeatureCatalog.registerInto()
        val rejected = results.filterNot { it.isAccepted }
        assertTrue(
            "catalog declarations must all be valid: ${rejected.map { (it as RegistrationResult.Rejected).metadata.id to it.reasons }}",
            rejected.isEmpty(),
        )
    }

    @Test
    fun theCatalogCoversEveryPhase() {
        val categories = PlatformFeatureCatalog.metadata().map { it.category }.toSet()
        assertTrue(FeatureCategory.PRIVACY in categories)
        assertTrue(FeatureCategory.MESSAGE_HISTORY in categories)
        assertTrue(FeatureCategory.AUTOMATION in categories)
        assertTrue(FeatureCategory.INTELLIGENCE in categories)
        assertTrue(FeatureCategory.MEDIA in categories)
        assertTrue(FeatureCategory.THEME in categories)
        assertTrue(FeatureCategory.NOTIFICATION in categories)
        assertTrue(FeatureCategory.STORAGE in categories)
        assertTrue(FeatureCategory.MULTI_ACCOUNT in categories)
    }

    @Test
    fun catalogIdsAreUnique() {
        val ids = PlatformFeatureCatalog.metadata().map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun safeModeRecoveryFeaturesAreCatalogued() {
        val catalogued = PlatformFeatureCatalog.metadata().map { it.id }.toSet()
        PlatformFeatures.SAFE_MODE_RECOVERY.forEach { id ->
            assertTrue("$id must be declared", id in catalogued)
        }
    }

    private fun validMetadata(id: String): FeatureMetadata =
        FeatureMetadata(
            id = id,
            displayName = "Test feature",
            category = FeatureCategory.PRIVACY,
            preferenceKeys = listOf("wae.test"),
            startupPolicy = StartupPolicy.LAZY,
            requiredResolvers = emptyList(),
            optionalResolvers = emptyList(),
            permissions = emptyList(),
            supportedWhatsAppVersions = listOf("2.26.40.xx"),
            supportedBusinessVersions = listOf("2.26.39.xx"),
            compatibilityConfidence = Confidence.EXACT,
            fallbackBehavior = FallbackBehavior.DISABLE_FEATURE,
            diagnostics = DiagnosticsMetadata(),
            tests = listOf("PlatformFoundationTest"),
        )
}
