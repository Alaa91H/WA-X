package com.wmods.wppenhacer.platform

import com.wmods.wppenhacer.resolver.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeSafetyTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 1_000_000L

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 1_000_000L
    }

    private fun killSwitch() = FeatureKillSwitch(store) { now }

    private fun guard() = StartupGuard(store) { now }

    // --- kill switch (T81, T82) -------------------------------------------------------

    @Test
    fun threeRepeatedStartupFailuresDisableTheFeature() {
        val killSwitch = killSwitch()
        repeat(2) { killSwitch.recordStartupFailure("privacy.profiles") }
        assertEquals(FeatureSwitchState.ENABLED, killSwitch.stateOf("privacy.profiles"))
        assertEquals(
            FeatureSwitchState.AUTOMATICALLY_DISABLED,
            killSwitch.recordStartupFailure("privacy.profiles"),
        )
        assertFalse(killSwitch.isLoadable("privacy.profiles"))
        assertTrue(killSwitch.reasonFor("privacy.profiles")!!.contains("3 repeated startup failures"))
    }

    @Test
    fun aSuccessfulStartResetsTheStrikeCount() {
        val killSwitch = killSwitch()
        repeat(2) { killSwitch.recordStartupFailure("privacy.profiles") }
        killSwitch.recordStartupSuccess("privacy.profiles")
        assertEquals(0, killSwitch.failuresFor("privacy.profiles"))
        killSwitch.recordStartupFailure("privacy.profiles")
        assertEquals(FeatureSwitchState.ENABLED, killSwitch.stateOf("privacy.profiles"))
    }

    @Test
    fun manualRetryReenablesAnAutomaticallyDisabledFeature() {
        val killSwitch = killSwitch()
        repeat(3) { killSwitch.recordStartupFailure("privacy.profiles") }
        assertEquals(FeatureSwitchState.ENABLED, killSwitch.retry("privacy.profiles"))
        assertEquals(0, killSwitch.failuresFor("privacy.profiles"))
    }

    @Test
    fun retryNeverOverridesTheUsersOwnChoice() {
        val killSwitch = killSwitch()
        killSwitch.setEnabled("privacy.profiles", false)
        assertEquals(FeatureSwitchState.MANUALLY_DISABLED, killSwitch.retry("privacy.profiles"))
        assertFalse(killSwitch.isLoadable("privacy.profiles"))
    }

    @Test
    fun theUserCannotEnableAnIncompatibleFeature() {
        val killSwitch = killSwitch()
        killSwitch.markIncompatible("privacy.profiles", "required resolver missing")
        assertEquals(
            FeatureSwitchState.INCOMPATIBLE,
            killSwitch.setEnabled("privacy.profiles", true),
        )
        assertFalse(killSwitch.isLoadable("privacy.profiles"))
        assertEquals(FeatureSwitchState.ENABLED, killSwitch.clearIncompatible("privacy.profiles"))
    }

    @Test
    fun temporaryDisableExpiresByItself() {
        val killSwitch = killSwitch()
        killSwitch.disableTemporarilyFor("privacy.profiles", "resolver timeout", durationMillis = 5_000)
        assertFalse(killSwitch.isLoadable("privacy.profiles"))
        now += 5_001
        assertTrue(killSwitch.isLoadable("privacy.profiles"))
    }

    @Test
    fun blockedFeaturesAreListedWithReasons() {
        val killSwitch = killSwitch()
        killSwitch.setEnabled("a", false)
        killSwitch.markIncompatible("b", "not on this build")
        val blocked = killSwitch.blocked()
        assertEquals(setOf("a", "b"), blocked.map { it.featureId }.toSet())
        assertTrue(killSwitch.renderBlocked().contains("b"))
    }

    // --- safe mode (T80) --------------------------------------------------------------

    @Test
    fun aCrashLoopTriggersSafeMode() {
        val guard = guard()
        repeat(3) { guard.markStartupAttempt() }
        assertTrue(guard.isCrashLooping())
        val safeMode = SafeModeController(store, guard) { now }
        val reason = safeMode.evaluateAutomaticTriggers(0, Confidence.EXACT)
        assertEquals(SafeModeReason.REPEATED_STARTUP_CRASHES, reason)
        assertTrue(safeMode.isActive())
        assertTrue(safeMode.describe().contains("Safe Mode is ON"))
    }

    @Test
    fun aCriticalResolverFailureTriggersSafeMode() {
        val safeMode = SafeModeController(store, guard()) { now }
        val reason = safeMode.evaluateAutomaticTriggers(2, Confidence.EXACT)
        assertEquals(SafeModeReason.CRITICAL_RESOLVER_FAILURE, reason)
    }

    @Test
    fun lowCompatibilityConfidenceTriggersSafeMode() {
        val safeMode = SafeModeController(store, guard()) { now }
        val reason = safeMode.evaluateAutomaticTriggers(0, Confidence.NONE)
        assertEquals(SafeModeReason.LOW_COMPATIBILITY_CONFIDENCE, reason)
    }

    @Test
    fun aHealthyStartDoesNotTriggerSafeMode() {
        val safeMode = SafeModeController(store, guard()) { now }
        assertNull(safeMode.evaluateAutomaticTriggers(0, Confidence.EXACT))
        assertFalse(safeMode.isActive())
    }

    @Test
    fun safeModeLoadsOnlyTheRecoveryFeatures() {
        val safeMode = SafeModeController(store, guard()) { now }
        safeMode.requestManual()
        assertTrue(safeMode.isActive())
        assertTrue(safeMode.allows(PlatformFeatures.DIAGNOSTICS))
        assertTrue(safeMode.allows(PlatformFeatures.KILL_SWITCH))
        assertFalse(safeMode.allows(PlatformFeatures.RULES_ENGINE))
        assertFalse(safeMode.allows(PlatformFeatures.PRIVATE_VAULT))
    }

    @Test
    fun leavingSafeModeClearsTheCrashLoopSoRecoveryIsSticky() {
        val guard = guard()
        repeat(3) { guard.markStartupAttempt() }
        val safeMode = SafeModeController(store, guard) { now }
        safeMode.evaluateAutomaticTriggers(0, Confidence.EXACT)
        safeMode.exit()
        assertFalse(safeMode.isActive())
        assertFalse(guard.isCrashLooping())
        assertEquals(0, guard.consecutiveFailedStarts())
    }

    @Test
    fun evaluatingTriggersWhileSafeModeIsAlreadyOnKeepsTheOriginalReason() {
        val safeMode = SafeModeController(store, guard()) { now }
        safeMode.requestManual()
        val reason = safeMode.evaluateAutomaticTriggers(5, Confidence.NONE)
        assertEquals(SafeModeReason.MANUAL, reason)
    }

    // --- compatibility summary (T84) --------------------------------------------------

    @Test
    fun anAllClearBuildIsSupported() {
        val summary =
            CompatibilitySummary(
                packageName = "com.whatsapp",
                whatsappVersion = "2.26.40.21",
                criticalPassed = 31,
                criticalTotal = 31,
                optionalPassed = 78,
                optionalTotal = 78,
                fallbacksActive = 0,
                disabledFeatures = 0,
            )
        assertEquals(CompatibilityStatus.SUPPORTED, summary.overall)
        assertEquals(0, summary.criticalFailures)
    }

    @Test
    fun optionalFailuresMakeTheBuildDegraded() {
        val summary =
            CompatibilitySummary(
                packageName = "com.whatsapp",
                whatsappVersion = "2.26.40.21",
                criticalPassed = 31,
                criticalTotal = 31,
                optionalPassed = 74,
                optionalTotal = 78,
                fallbacksActive = 4,
                disabledFeatures = 2,
            )
        assertEquals(CompatibilityStatus.DEGRADED, summary.overall)
    }

    @Test
    fun aCriticalFailureMakesTheBuildIncompatible() {
        val summary =
            CompatibilitySummary(
                packageName = "com.whatsapp",
                whatsappVersion = "2.27.1.0",
                criticalPassed = 30,
                criticalTotal = 31,
                optionalPassed = 78,
                optionalTotal = 78,
                fallbacksActive = 0,
                disabledFeatures = 0,
            )
        assertEquals(CompatibilityStatus.INCOMPATIBLE, summary.overall)
        assertEquals(1, summary.criticalFailures)
    }

    @Test
    fun theReportRendersTheRoadmapShape() {
        val summary =
            CompatibilitySummary(
                packageName = "com.whatsapp",
                whatsappVersion = "2.27.70",
                criticalPassed = 31,
                criticalTotal = 31,
                optionalPassed = 74,
                optionalTotal = 78,
                fallbacksActive = 4,
                disabledFeatures = 2,
            )
        val report = summary.renderReport()
        assertTrue(report.startsWith("WaEnhancer Compatibility Report"))
        assertTrue(report.contains("Package: com.whatsapp"))
        assertTrue(report.contains("Critical Resolvers: 31/31"))
        assertTrue(report.contains("Optional Resolvers: 74/78"))
        assertTrue(report.contains("Fallbacks: 4"))
        assertTrue(report.contains("Disabled Features: 2"))
        assertTrue(report.contains("Critical Failures: 0"))
        assertTrue(report.endsWith("Overall: DEGRADED"))
    }

    @Test
    fun theCardRendersTheCompactShape() {
        val summary =
            CompatibilitySummary(
                packageName = "com.whatsapp",
                whatsappVersion = "2.27.70",
                criticalPassed = 31,
                criticalTotal = 31,
                optionalPassed = 74,
                optionalTotal = 78,
                fallbacksActive = 3,
                disabledFeatures = 2,
            )
        assertEquals(
            listOf(
                "Critical resolvers: 31/31",
                "Optional resolvers: 74/78",
                "Fallbacks active: 3",
                "Disabled features: 2",
                "Overall: DEGRADED",
            ),
            summary.renderCard().lines(),
        )
    }

    @Test
    fun sharedResolversAreCountedOnce() {
        val summary =
            CompatibilitySummary.from(
                packageName = "com.whatsapp",
                whatsappVersion = "2.26.40.21",
                criticalResolvers = listOf("loadA", "loadB"),
                optionalResolvers = listOf("loadB", "loadC"),
                disabledFeatures = 0,
            ) { id -> if (id == "loadC") ResolverPosture.MISSING else ResolverPosture.RESOLVED }
        assertEquals(2, summary.criticalTotal)
        assertEquals(2, summary.criticalPassed)
        assertEquals(1, summary.optionalTotal)
        assertEquals(0, summary.optionalPassed)
    }

    // --- canary (T83) -----------------------------------------------------------------

    private fun fingerprint(
        version: String = "2.26.40.21",
        hash: String = "apk-a",
    ) = TargetFingerprint("com.whatsapp", version, 1L, hash)

    @Test
    fun aKnownBuildEnablesEveryFeatureWhoseResolversExist() {
        val plan =
            CompatibilityCanary().plan(
                features = listOf(feature("a"), feature("b", required = listOf("loadA"))),
                previous = fingerprint(),
                current = fingerprint(),
                postureOf = { ResolverPosture.RESOLVED },
            )
        assertFalse(plan.newBuildDetected)
        assertEquals(listOf("a", "b"), plan.enabled)
        assertTrue(plan.deferred.isEmpty())
        assertTrue(plan.blocked.isEmpty())
    }

    @Test
    fun aChangedFingerprintDefersFeaturesOnTheirFallbackPath() {
        val plan =
            CompatibilityCanary().plan(
                features =
                    listOf(
                        feature("stable", required = listOf("loadA")),
                        feature("uncertain", required = listOf("loadB")),
                    ),
                previous = fingerprint(hash = "apk-a"),
                current = fingerprint(hash = "apk-b"),
                postureOf = { id -> if (id == "loadB") ResolverPosture.FALLBACK else ResolverPosture.RESOLVED },
            )
        assertTrue(plan.newBuildDetected)
        assertEquals(listOf("stable"), plan.enabled)
        assertEquals(listOf("uncertain"), plan.deferred)
        assertTrue(plan.blocked.isEmpty())
    }

    @Test
    fun aMissingRequiredResolverBlocksTheFeature() {
        val plan =
            CompatibilityCanary().plan(
                features = listOf(feature("broken", required = listOf("loadGone"))),
                previous = null,
                current = fingerprint(),
                postureOf = { ResolverPosture.MISSING },
            )
        assertEquals(listOf("broken"), plan.blocked)
        assertEquals(1, plan.summary.disabledFeatures)
    }

    @Test
    fun anUndeclaredVersionIsTreatedAsANewBuildEvenWithoutAPreviousFingerprint() {
        val plan =
            CompatibilityCanary().plan(
                features = listOf(feature("a", required = listOf("loadA"))),
                previous = null,
                current = fingerprint(version = "2.27.99.99"),
                postureOf = { ResolverPosture.RESOLVED },
            )
        assertTrue(plan.newBuildDetected)
        assertEquals(listOf("a"), plan.enabled)
    }

    @Test
    fun criticalAndOptionalResolversAreCountedSeparately() {
        val plan =
            CompatibilityCanary().plan(
                features =
                    listOf(
                        feature("critical", required = listOf("loadA"), critical = true),
                        feature("optional", required = listOf("loadB"), optional = listOf("loadC")),
                    ),
                previous = fingerprint(),
                current = fingerprint(),
                postureOf = { id -> if (id == "loadC") ResolverPosture.MISSING else ResolverPosture.RESOLVED },
            )
        assertEquals(1, plan.summary.criticalTotal)
        assertEquals(1, plan.summary.criticalPassed)
        assertEquals(2, plan.summary.optionalTotal)
        assertEquals(1, plan.summary.optionalPassed)
        assertTrue(plan.describe().contains("Compatibility canary"))
    }

    @Test
    fun aPlanWithEverythingWorkingSaysNothingWasDeferred() {
        val plan =
            CompatibilityCanary().plan(
                features = listOf(feature("a")),
                previous = fingerprint(),
                current = fingerprint(),
                postureOf = { ResolverPosture.RESOLVED },
            )
        assertFalse(plan.describe().contains("Deferred"))
        assertEquals(CompatibilityStatus.DEGRADED, plan.summary.overall)
    }

    private fun feature(
        id: String,
        critical: Boolean = false,
        required: List<String> = emptyList(),
        optional: List<String> = emptyList(),
    ): FeatureMetadata =
        FeatureMetadata(
            id = id,
            displayName = id,
            category = FeatureCategory.GENERAL,
            preferenceKeys = emptyList(),
            startupPolicy = if (critical) StartupPolicy.EAGER_CRITICAL else StartupPolicy.LAZY,
            requiredResolvers = required,
            optionalResolvers = optional,
            permissions = emptyList(),
            supportedWhatsAppVersions = PlatformFeatureCatalog.WHATSAPP_VERSIONS,
            supportedBusinessVersions = PlatformFeatureCatalog.BUSINESS_VERSIONS,
            compatibilityConfidence = Confidence.EXACT,
            fallbackBehavior = FallbackBehavior.COMPAT_PATH,
            diagnostics = DiagnosticsMetadata(critical = critical),
            tests = listOf("RuntimeSafetyTest"),
        )
}
