package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anti-revoke decision rules (#451).
 *
 * Most of these tests are negative, and that is the point: the feature exists
 * to preserve what the user already had, and every test below is about what it
 * must refuse to do.
 */
class ModernAntiRevokeFeatureTest {
    @Test fun onlyStandardContentIsPreserved() {
        assertTrue(ModernAntiRevokeFeature.eligible(ModernAntiRevokeFeature.ContentClass.STANDARD))
        for (class_ in listOf(
            ModernAntiRevokeFeature.ContentClass.EPHEMERAL,
            ModernAntiRevokeFeature.ContentClass.VIEW_ONCE,
            ModernAntiRevokeFeature.ContentClass.STATUS,
        )) {
            assertFalse(
                "$class_ must not be preserved automatically",
                ModernAntiRevokeFeature.eligible(class_),
            )
            assertTrue(
                "an exclusion has to say why, not just refuse",
                ModernAntiRevokeFeature.exclusionReason(class_).isNotBlank(),
            )
        }
        assertEquals("", ModernAntiRevokeFeature.exclusionReason(ModernAntiRevokeFeature.ContentClass.STANDARD))
    }

    @Test fun aMessageThatNeverArrivedIsNeverPreserved() {
        ModernAntiRevokeFeature.clearPreserved()
        assertFalse(
            "an id that was never recorded stands for nothing",
            ModernAntiRevokeFeature.mayWithholdRevocation("never-seen", 0L),
        )
        assertEquals(0, ModernAntiRevokeFeature.preservedCount())
        ModernAntiRevokeFeature.clearPreserved()
    }

    @Test fun anEmptyIdIsNeverPreserved() {
        ModernAntiRevokeFeature.clearPreserved()
        assertFalse(
            ModernAntiRevokeFeature.recordArrival("", ModernAntiRevokeFeature.ContentClass.STANDARD, 0L, 7L),
        )
        assertFalse(
            ModernAntiRevokeFeature.recordArrival(null, ModernAntiRevokeFeature.ContentClass.STANDARD, 0L, 7L),
        )
        assertFalse(ModernAntiRevokeFeature.mayWithholdRevocation(null, 0L))
        assertEquals(0, ModernAntiRevokeFeature.preservedCount())
        ModernAntiRevokeFeature.clearPreserved()
    }

    @Test fun anArrivedMessageMayHaveItsRevocationWithheld() {
        ModernAntiRevokeFeature.clearPreserved()
        assertTrue(
            ModernAntiRevokeFeature.recordArrival(
                "m1",
                ModernAntiRevokeFeature.ContentClass.STANDARD,
                0L,
                7L,
            ),
        )
        assertTrue(ModernAntiRevokeFeature.mayWithholdRevocation("m1", 1L))
        ModernAntiRevokeFeature.clearPreserved()
    }

    @Test fun preservedMessagesExpire() {
        ModernAntiRevokeFeature.clearPreserved()
        ModernAntiRevokeFeature.recordArrival(
            "m1",
            ModernAntiRevokeFeature.ContentClass.STANDARD,
            0L,
            7L,
        )
        val after = 8L * 24L * 60L * 60L * 1000L
        assertFalse(
            "retention must be bounded, not archival",
            ModernAntiRevokeFeature.mayWithholdRevocation("m1", after),
        )
        assertEquals(0, ModernAntiRevokeFeature.preservedCount())
        ModernAntiRevokeFeature.clearPreserved()
    }

    @Test fun anUnrecognisedModeMeansTheShortestWindow() {
        assertEquals(0L, ModernAntiRevokeFeature.retentionDays("0"))
        assertEquals(0L, ModernAntiRevokeFeature.retentionDays(null))
        assertEquals(0L, ModernAntiRevokeFeature.retentionDays("something-else"))
        assertEquals(7L, ModernAntiRevokeFeature.retentionDays(ModernAntiRevokeFeature.MODE_PRESERVE))
        assertEquals(
            ModernAntiRevokeFeature.RETENTION_DAYS_MAX,
            ModernAntiRevokeFeature.retentionDays("2"),
        )
    }

    @Test fun theRevocationAnchorsAreTheOnesTheLegacyResolverUsed() {
        // From Unobfuscator.loadAntiRevokeMessageMethod in this repository.
        assertEquals(
            listOf("msgstore/edit/revoke", "msgstore/revoking/"),
            ModernAntiRevokeFeature.REVOKE_ANCHORS,
        )
    }

    @Test fun anAmbiguousResolverIsItsOwnOutcome() {
        val outcomes = ModernAntiRevokeFeature.Outcome.entries.map { it.name }
        assertTrue(outcomes.contains("RESOLVER_AMBIGUOUS"))
        assertTrue(outcomes.contains("UNSAFE_SIGNATURE"))
        assertTrue(outcomes.contains("DISABLED"))
        assertEquals(
            "two states with the same name would collapse in the report",
            outcomes.size,
            outcomes.distinct().size,
        )
    }

    @Test fun nothingIsPreservedWhenNothingIsSwitchedOn() {
        ModernAntiRevokeFeature.clearPreserved()
        assertFalse(
            ModernAntiRevokeFeature.recordArrival(
                "m1",
                ModernAntiRevokeFeature.ContentClass.STANDARD,
                0L,
                0L,
            ),
        )
        assertEquals(0, ModernAntiRevokeFeature.preservedCount())
        ModernAntiRevokeFeature.clearPreserved()
    }

    @Test fun purgingDropsOnlyWhatHasExpired() {
        ModernAntiRevokeFeature.clearPreserved()
        ModernAntiRevokeFeature.recordArrival("old", ModernAntiRevokeFeature.ContentClass.STANDARD, 0L, 1L)
        ModernAntiRevokeFeature.recordArrival("new", ModernAntiRevokeFeature.ContentClass.STANDARD, 0L, 30L)
        val twoDays = 2L * 24L * 60L * 60L * 1000L
        assertEquals(1, ModernAntiRevokeFeature.purgeExpired(twoDays))
        assertTrue(ModernAntiRevokeFeature.mayWithholdRevocation("new", twoDays))
        ModernAntiRevokeFeature.clearPreserved()
    }
}
