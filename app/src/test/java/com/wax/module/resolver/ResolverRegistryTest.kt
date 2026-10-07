package com.wax.module.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResolverRegistryTest {
    @Before
    fun setUp() {
        ResolverRegistry.clear()
    }

    private fun record(
        id: String,
        outcome: Resolution<*>,
        at: Long = 0L,
    ) = ResolverRegistry.record(id, "com.whatsapp.$id", outcome, at)

    @Test
    fun nothingIsRecordedInitially() {
        assertTrue(ResolverRegistry.all().isEmpty())
    }

    @Test
    fun aRecordedOutcomeIsRetained() {
        record("loadReceipt", Resolution.exact("m"))
        assertEquals(1, ResolverRegistry.all().size)
    }

    @Test
    fun theLatestOutcomeForAResolverIsFound() {
        record("loadReceipt", Resolution.NotFound())
        record("loadReceipt", Resolution.exact("m"))
        assertEquals(
            Confidence.EXACT,
            ResolverRegistry.latestFor("loadReceipt")?.outcome?.confidence,
        )
    }

    @Test
    fun aResolverThatNeverRanHasNoLatestOutcome() {
        assertNull(ResolverRegistry.latestFor("neverRan"))
    }

    @Test
    fun unusableCollectsEverythingThatMustNotBeInstalled() {
        record("a", Resolution.exact("m"))
        record("b", Resolution.NotFound())
        record("c", Resolution.Ambiguous(listOf("x", "y")))
        record("d", Resolution.Incompatible("api"))
        val unusable = ResolverRegistry.unusable()
        assertEquals(3, unusable.size)
        assertTrue(unusable.none { it.resolverId == "a" })
    }

    @Test
    fun aLikelyResultCountsAsUsable() {
        record("a", Resolution.likely("m", "heuristic"))
        assertTrue(ResolverRegistry.unusable().isEmpty())
    }

    @Test
    fun summaryCollapsesRepeatsOfTheSameResolver() {
        repeat(3) { record("loadReceipt", Resolution.exact("m"), it.toLong()) }
        val summary = ResolverRegistry.summary()
        assertEquals(1, summary.size)
        assertEquals(3, summary.single().attempts)
    }

    @Test
    fun summaryReportsTheLatestStateNotTheFirst() {
        record("a", Resolution.exact("m"))
        record("a", Resolution.NotFound())
        val summary = ResolverRegistry.summary().single()
        assertEquals(Confidence.NONE, summary.confidence)
        assertFalse(summary.installable)
    }

    @Test
    fun summaryPreservesInsertionOrderOfFirstAppearance() {
        record("z", Resolution.exact("m"))
        record("a", Resolution.exact("m"))
        assertEquals(listOf("z", "a"), ResolverRegistry.summary().map { it.resolverId })
    }

    @Test
    fun aDisplayLineNamesTheIdConfidenceAndReason() {
        val line =
            ResolverRecord(
                "loadReceipt",
                "com.whatsapp.X",
                Resolution.NotFound(),
                0L,
            ).toDisplayLine()
        assertTrue(line.contains("loadReceipt"))
        assertTrue(line.contains("NONE"))
    }

    @Test
    fun clearDropsEverything() {
        record("a", Resolution.exact("m"))
        ResolverRegistry.clear()
        assertTrue(ResolverRegistry.all().isEmpty())
        assertTrue(ResolverRegistry.summary().isEmpty())
    }
}

class HookResolverContractTest {
    private val testClassLoader: ClassLoader
        get() = requireNotNull(javaClass.classLoader) { "Test class loader is unavailable" }

    @Before
    fun setUp() {
        ResolverRegistry.clear()
    }

    private class FixedResolver(
        override val id: String,
        private val outcome: Resolution<String>,
    ) : HookResolver<String> {
        override val target: String = "com.whatsapp.Fixed"
        var calls = 0

        override fun resolve(classLoader: ClassLoader): Resolution<String> {
            calls++
            return outcome
        }
    }

    @Test
    fun resolveRecordedReturnsTheSameOutcome() {
        val resolver = FixedResolver("a", Resolution.exact("m"))
        val outcome = resolver.resolveRecorded(testClassLoader)
        assertEquals("m", outcome.valueOrNull())
    }

    @Test
    fun resolveRecordedAlwaysRecords() {
        FixedResolver("a", Resolution.NotFound()).resolveRecorded(testClassLoader)
        assertEquals(1, ResolverRegistry.all().size)
    }

    @Test
    fun theRecordedEntryCarriesTheResolverIdAndTarget() {
        FixedResolver("loadReceipt", Resolution.exact("m"))
            .resolveRecorded(testClassLoader)
        val record = ResolverRegistry.latestFor("loadReceipt")
        assertEquals("com.whatsapp.Fixed", record?.target)
    }

    @Test
    fun recordingDoesNotChangeTheOutcome() {
        // Recording is a side channel; it must not turn a failure into a success.
        val resolver = FixedResolver("a", Resolution.Ambiguous(listOf("x", "y")))
        val outcome = resolver.resolveRecorded(testClassLoader)
        assertTrue(outcome is Resolution.Ambiguous)
        assertFalse(outcome.isInstallable)
    }

    @Test
    fun theResolverRunsExactlyOnce() {
        val resolver = FixedResolver("a", Resolution.exact("m"))
        resolver.resolveRecorded(testClassLoader)
        assertEquals(1, resolver.calls)
    }

    @Test
    fun theBaseClassDoesNotResolveOnItsOwn() {
        // resolveRecorded delegates; it must not swallow or transform the result.
        val resolver = FixedResolver("a", Resolution.Incompatible("api"))
        val outcome = resolver.resolveRecorded(testClassLoader)
        assertTrue(outcome is Resolution.Incompatible)
        assertEquals(1, ResolverRegistry.unusable().size)
    }
}
