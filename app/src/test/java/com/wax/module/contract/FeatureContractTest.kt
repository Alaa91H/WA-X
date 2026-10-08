package com.wax.module.contract

import com.wax.module.diagnostics.FailureCode
import com.wax.module.platform.TargetApp
import com.wax.module.settings.EffectiveSettingsResolver
import com.wax.module.settings.InMemorySettingsStore
import com.wax.module.settings.SettingsScope
import com.wax.module.settings.SettingsSnapshot
import com.wax.module.xposed.features.others.DebugFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A01's strict gate, as an executable statement.
 *
 * > Runtime features can be unit-tested with fakes without a real Xposed framework.
 *
 * The test that matters is [aFeatureWrittenAgainstTheContractRunsOnAJvmWithFakes]: it constructs
 * a real feature class from `main`, hands it fakes, and starts it. If that compiles and runs here,
 * with no `android.jar` on the classpath and no framework installed, the gate holds.
 *
 * The rest are the properties the contracts exist to have.
 */
class FeatureContractTest {
    /**
     * The loader a test hands a feature.
     *
     * A real `ClassLoader`, taken explicitly, because handing one in is part of what the contract
     * promises: a feature gets its target's loader from the context rather than reading a global.
     */
    private val targetLoader: ClassLoader = requireNotNull(FeatureContractTest::class.java.classLoader)

    /**
     * A real [SettingsSnapshot] over an in-memory store.
     *
     * The snapshot type is production code, not a fake: the point of the gate is that a feature
     * can be given the *real* settings model and still run here, so faking it would prove less.
     */
    private fun settings(
        seed: Map<SettingsScope, Map<String, String>> = mapOf(SettingsScope.Global to mapOf("enablelogs" to "true")),
    ): SettingsSnapshot = EffectiveSettingsResolver(InMemorySettingsStore(seed)).snapshotFor(TargetApp.WHATSAPP)

    private fun context(
        settings: SettingsSnapshot = settings(),
        hooks: Fakes.RecordingHookEngine = Fakes.RecordingHookEngine(availableClasses = setOf("com.whatsapp.Foo")),
        capabilities: Fakes.FakeCapabilityProvider = Fakes.FakeCapabilityProvider(availableClasses = setOf("com.whatsapp.Foo")),
        logger: Fakes.RecordingLogger = Fakes.RecordingLogger(),
        diagnostics: Fakes.RecordingDiagnosticSink = Fakes.RecordingDiagnosticSink(),
        clock: Fakes.FakeClock = Fakes.FakeClock(),
    ) = TestContext(settings, capabilities, hooks, diagnostics, clock, logger, targetLoader)

    /** A context assembled entirely from fakes. It is the only way a feature is started here. */
    private data class TestContext(
        override val settings: SettingsSnapshot,
        override val capabilities: CapabilityProvider,
        override val hooks: HookEngine,
        override val diagnostics: DiagnosticSink,
        override val clock: RuntimeClock,
        override val logger: RuntimeLogger,
        override val targetClassLoader: ClassLoader,
    ) : FeatureContext

    /**
     * The gate itself.
     *
     * No framework, no device, no Android runtime. The only thing the feature knows about its
     * surroundings is the context it was handed.
     */
    @Test
    fun aFeatureWrittenAgainstTheContractRunsOnAJvmWithFakes() {
        val feature = DebugFeature()
        val context = context()

        val result = feature.start(context)

        assertTrue(result.isRunning)
        assertEquals("nothing to install", result.summary)
        assertEquals(
            "A feature reports the hooks it installed rather than assuming; here there were none.",
            0,
            (result as FeatureStartResult.Installed).hooks,
        )
        assertEquals("Debug Feature", feature.featureId)
    }

    /** The hook engine a feature hooks through is reachable and reports what it installed. */
    @Test
    fun aFeatureCanHookThroughTheContract() {
        val hooks = Fakes.RecordingHookEngine(availableClasses = setOf("com.whatsapp.Foo"))
        val context = context(hooks = hooks)

        val installed =
            context.hooks.hookMethod("com.whatsapp.Foo", "bar") { _, _ ->
            }

        assertTrue(installed)
        assertEquals(1, context.hooks.installedCount())
        assertEquals("com.whatsapp.Foo", hooks.hookAttempts.single().className)
        assertTrue(context.hooks.hasClass("com.whatsapp.Foo"))
        assertFalse(context.hooks.hasClass("com.whatsapp.Missing"))
    }

    /** A class the target does not have is reported as absent, not as an exception. */
    @Test
    fun anAbsentCapabilityIsReportedRatherThanThrown() {
        val capabilities = Fakes.FakeCapabilityProvider(availableClasses = emptySet())

        assertFalse(capabilities.hasClass("com.whatsapp.Foo"))
        assertEquals(null, capabilities.findClass("com.whatsapp.Foo"))
    }

    /**
     * "This build is unsupported" and "the engine did not start" are different facts.
     *
     * The two used to be indistinguishable to a feature, which is how an engine failure was
     * reported as a missing class.
     */
    @Test
    fun anEngineThatNeverStartedIsNotAnUnsupportedBuild() {
        val broken = Fakes.FakeCapabilityProvider(availableClasses = emptySet(), resolution = false)
        val unsupported = Fakes.FakeCapabilityProvider(availableClasses = emptySet(), resolution = true)

        assertFalse(broken.resolutionAvailable())
        assertTrue(unsupported.resolutionAvailable())
        assertFalse(broken.hasClass("com.whatsapp.Foo"))
        assertFalse(unsupported.hasClass("com.whatsapp.Foo"))
    }

    /** The clock a feature measures against is the one it was handed. */
    @Test
    fun aFeatureMeasuresAgainstTheClockItWasGiven() {
        val clock = Fakes.FakeClock(now = 5_000L, elapsed = 1_000L)

        assertEquals(5_000L, clock.nowMillis())
        assertEquals(1_000L, clock.elapsedRealtimeMillis())
        clock.advance(250L)
        assertEquals(5_250L, clock.nowMillis())
        assertEquals(
            "Both readings advance together, which is what makes a measured duration equal to the " +
                "elapsed time a feature observed.",
            250L,
            clock.elapsedRealtimeMillis() - 1_000L,
        )
    }

    /** A diagnostic report goes to the sink and comes back, with its code intact. */
    @Test
    fun aFailureReachesTheDiagnosticSinkWithItsOwnCode() {
        val sink = Fakes.RecordingDiagnosticSink()

        val report = sink.report("Some Feature", FailureCode.CLASS_NOT_FOUND, "no com.whatsapp.Foo")

        assertEquals(FailureCode.CLASS_NOT_FOUND, report.code)
        assertEquals("Some Feature", report.featureId)
        assertEquals(1, sink.reports.size)
    }

    /** A throwable is classified once, by the sink, rather than by each feature. */
    @Test
    fun aThrowableIsClassifiedByTheSinkNotByTheFeature() {
        val sink = Fakes.RecordingDiagnosticSink()

        val report = sink.reportThrowable("Some Feature", IllegalArgumentException("no such thing"))

        assertEquals(FailureCode.UNEXPECTED, report.code)
        assertEquals("no such thing", report.message)
    }

    /** Every start result says whether the feature is doing something. */
    @Test
    fun everyStartResultSaysWhetherItIsRunning() {
        val installed = FeatureStartResult.Installed("ok")
        val degraded = FeatureStartResult.Degraded("partial", "the preview")
        val skipped = FeatureStartResult.Skipped("unsupported", "com.whatsapp.Foo")
        val failed = FeatureStartResult.Failed("broken", FailureCode.CONSTRUCTION_FAILED)

        assertTrue(installed.isRunning)
        assertTrue(degraded.isRunning)
        assertFalse(skipped.isRunning)
        assertFalse(failed.isRunning)
        assertTrue(skipped.isInapplicable)
        assertFalse(
            "A failure is not the same as inapplicability: one is a fault and the other is an " +
                "ordinary outcome on a build the feature does not target.",
            failed.isInapplicable,
        )
    }

    /**
     * The contract package carries no Android, Xposed or DexKit type.
     *
     * Asserted over the source because the compiler cannot be asked: a type can appear in a
     * signature without failing anything at the point of declaration. This is the check CI runs
     * as `tools/quality/check_feature_contracts.py`, and it is here as well so a failure names
     * the file rather than only appearing in a build log.
     */
    @Test
    fun theContractPackageCarriesNoPlatformType() {
        val forbidden =
            listOf(
                "android.",
                "androidx.",
                "de.robv.android.xposed",
                "org.luckypray.dexkit",
            )
        val root = rootSourceDirectory()
        val contractDirectory = java.io.File(root, "java/com/wax/module/contract")
        val files = contractDirectory.listFiles { file: java.io.File -> file.name.endsWith(".kt") }.orEmpty()

        assertTrue("No contract sources were found at $contractDirectory", files.isNotEmpty())
        for (file in files) {
            val text = file.readText()
            for (marker in forbidden) {
                assertFalse(
                    "${file.name} mentions $marker. The contracts are what makes a feature testable " +
                        "without a device, and a platform type in a signature undoes that.",
                    Regex("""^\s*import\s+""" + Regex.escape(marker)).containsMatchIn(text),
                )
            }
        }
    }

    private fun rootSourceDirectory(): String {
        val candidates = listOf("src/main", "../src/main", "app/src/main")
        return candidates.first { java.io.File(it).isDirectory }
    }
}
