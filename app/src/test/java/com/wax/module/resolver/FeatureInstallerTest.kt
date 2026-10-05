package com.wax.module.resolver

import com.wax.module.diagnostics.FailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeatureInstallerTest {
    @Before
    fun setUp() {
        FeatureInstaller.clear()
    }

    // --- the plan's isolation requirement --------------------------------------------

    @Test
    fun oneFailingFeatureDoesNotStopTheOthers() {
        // The acceptance criterion for T14: corrupt one feature and watch the rest survive.
        val installed = mutableListOf<String>()

        val outcomes =
            listOf(
                FeatureInstaller.install("AntiRevoke") { installed.add("AntiRevoke") },
                FeatureInstaller.install("Broken") { throw RuntimeException("resolver exploded") },
                FeatureInstaller.install("HideSeen") { installed.add("HideSeen") },
                FeatureInstaller.install("Others") { installed.add("Others") },
            )

        assertEquals(
            "every healthy feature must still be installed",
            listOf("AntiRevoke", "HideSeen", "Others"),
            installed,
        )
        assertEquals(FeatureHealth.HEALTHY, outcomes[0].health)
        assertEquals(FeatureHealth.FAILED, outcomes[1].health)
        assertEquals(FeatureHealth.HEALTHY, outcomes[2].health)
        assertEquals(FeatureHealth.HEALTHY, outcomes[3].health)
    }

    @Test
    fun aFailureIsAttributedToTheFailingFeatureOnly() {
        FeatureInstaller.install("A") { }
        FeatureInstaller.install("Broken") { throw RuntimeException("boom") }
        FeatureInstaller.install("C") { }

        assertEquals(FeatureHealth.FAILED, FeatureInstaller.outcomeFor("Broken")?.health)
        assertEquals(FeatureHealth.HEALTHY, FeatureInstaller.outcomeFor("A")?.health)
        assertEquals(FeatureHealth.HEALTHY, FeatureInstaller.outcomeFor("C")?.health)
    }

    @Test
    fun anErrorDoesNotEscapeTheInstaller() {
        // Even Errors must be contained, or one bad feature ends the module.
        val outcome = FeatureInstaller.install("Broken") { throw StackOverflowError("deep") }
        assertFalse(outcome.isRunning)
    }

    @Test
    fun theFailureCodeClassifiesTheThrowable() {
        val outcome = FeatureInstaller.install("Broken") { throw ClassNotFoundException("gone") }
        assertEquals(FailureCode.CLASS_NOT_FOUND, outcome.code)
    }

    @Test
    fun theFailureReasonDoesNotLeakTheThrowableMessage() {
        // The message could contain user data; only the exception type is recorded.
        val outcome =
            FeatureInstaller.install("Broken") {
                throw RuntimeException("failed for 4915112345678@s.whatsapp.net")
            }
        assertFalse(outcome.reason.contains("4915"))
    }

    // --- aggregation -----------------------------------------------------------------

    @Test
    fun runningFeaturesAreListed() {
        FeatureInstaller.install("A") { }
        FeatureInstaller.install("B") { throw RuntimeException("x") }
        assertEquals(listOf("A"), FeatureInstaller.running().map { it.featureId })
    }

    @Test
    fun stoppedFeaturesAreListed() {
        FeatureInstaller.install("A") { }
        FeatureInstaller.install("B") { throw RuntimeException("x") }
        assertEquals(listOf("B"), FeatureInstaller.stopped().map { it.featureId })
    }

    @Test
    fun notableExcludesHealthyFeatures() {
        FeatureInstaller.install("A") { }
        FeatureInstaller.install("B") { throw RuntimeException("x") }
        assertEquals(listOf("B"), FeatureInstaller.notable().map { it.featureId })
    }

    @Test
    fun everyOutcomeIsRecordedInOrder() {
        FeatureInstaller.install("A") { }
        FeatureInstaller.install("B") { }
        assertEquals(listOf("A", "B"), FeatureInstaller.all().map { it.featureId })
    }

    @Test
    fun anUnattemptedFeatureHasNoOutcome() {
        assertNull(FeatureInstaller.outcomeFor("never"))
    }

    @Test
    fun theLatestOutcomeForARepeatedFeatureWins() {
        FeatureInstaller.install("A") { throw RuntimeException("first") }
        FeatureInstaller.install("A") { }
        assertEquals(FeatureHealth.HEALTHY, FeatureInstaller.outcomeFor("A")?.health)
    }

    // --- chain integration -----------------------------------------------------------

    @Test
    fun aChainOutcomeIsRecordedByTheInstaller() {
        val chain =
            FallbackChain
                .builder<String>("AntiRevoke")
                .primary { Resolution.exact("m") }
                .build()
        val outcome = FeatureInstaller.install("AntiRevoke", chain) { }
        assertEquals(FeatureHealth.HEALTHY, outcome.health)
        assertEquals(1, FeatureInstaller.all().size)
    }

    @Test
    fun aChainThatEndsUnusableIsRecordedAsNotRunning() {
        val chain =
            FallbackChain
                .builder<String>("AntiRevoke")
                .primary { Resolution.NotFound() }
                .build()
        val outcome = FeatureInstaller.install("AntiRevoke", chain) { }
        assertFalse(outcome.isRunning)
        assertTrue(FeatureInstaller.stopped().any { it.featureId == "AntiRevoke" })
    }

    @Test
    fun aChainInstallThatThrowsIsCapturedRatherThanPropagated() {
        val chain =
            FallbackChain
                .builder<String>("AntiRevoke")
                .primary { Resolution.exact("m") }
                .build()
        val outcome = FeatureInstaller.install("AntiRevoke", chain) { throw RuntimeException("x") }
        assertFalse(outcome.isRunning)
    }

    @Test
    fun clearDropsEverything() {
        FeatureInstaller.install("A") { }
        FeatureInstaller.clear()
        assertTrue(FeatureInstaller.all().isEmpty())
    }

    @Test
    fun recordedOutcomesFromElsewhereAreVisible() {
        FeatureInstaller.record(FeatureOutcome.incompatible("X", "gone on this build"))
        assertEquals(FeatureHealth.INCOMPATIBLE, FeatureInstaller.outcomeFor("X")?.health)
    }
}

class FeatureHealthTest {
    @Test
    fun healthyDegradedAndFallbackAllCountAsRunning() {
        assertTrue(FeatureHealth.HEALTHY.isRunning)
        assertTrue(FeatureHealth.DEGRADED.isRunning)
        assertTrue(FeatureHealth.FALLBACK.isRunning)
    }

    @Test
    fun theStoppedStatesDoNotCountAsRunning() {
        assertFalse(FeatureHealth.DISABLED.isRunning)
        assertFalse(FeatureHealth.INCOMPATIBLE.isRunning)
        assertFalse(FeatureHealth.FAILED.isRunning)
        assertFalse(FeatureHealth.UNKNOWN.isRunning)
    }

    @Test
    fun healthyAndUnknownAreNotNotable() {
        assertFalse(FeatureHealth.HEALTHY.isNotable)
        assertFalse(FeatureHealth.UNKNOWN.isNotable)
    }

    @Test
    fun everyStoppedStateIsNotable() {
        val stopped =
            listOf(
                FeatureHealth.DEGRADED,
                FeatureHealth.FALLBACK,
                FeatureHealth.DISABLED,
                FeatureHealth.INCOMPATIBLE,
                FeatureHealth.FAILED,
            )
        for (state in stopped) {
            assertTrue("$state must be surfaced", state.isNotable)
        }
    }

    @Test
    fun aDisplayLineNamesTheFeatureHealthAndReason() {
        val line =
            FeatureOutcome
                .failed("AntiRevoke", FailureCode.RESOLVER_AMBIGUOUS, "two matches")
                .toDisplayLine()
        assertTrue(line.contains("AntiRevoke"))
        assertTrue(line.contains("FAILED"))
        assertTrue(line.contains("two matches"))
    }

    @Test
    fun aDisplayLineNamesTheFallbackWhenOneWasUsed() {
        val line = FeatureOutcome.fallback("X", "compat", "primary missed").toDisplayLine()
        assertTrue(line.contains("via compat"))
    }
}
