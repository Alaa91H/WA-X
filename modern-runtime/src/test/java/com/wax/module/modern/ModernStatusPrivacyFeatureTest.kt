package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Status privacy rules (#452).
 *
 * The separation tests are the important ones: a Status acknowledgement and a
 * chat read receipt are different claims, and the report must never let one
 * stand in for the other.
 */
class ModernStatusPrivacyFeatureTest {
    @Test fun statusAndChatReceiptsAreDifferentFeatures() {
        val statusIds =
            listOf(
                ModernStatusPrivacyFeature.FEATURE_ID_SEEN,
                ModernStatusPrivacyFeature.FEATURE_ID_AFTER_REPLY,
            )
        val chatIds =
            listOf(
                ModernReceiptPrivacyFeature.FEATURE_ID_READ,
                ModernReceiptPrivacyFeature.FEATURE_ID_AFTER_REPLY,
                ModernReceiptPrivacyFeature.FEATURE_ID_DELIVERY,
            )
        val overlap = statusIds.filter { chatIds.contains(it) }
        assertTrue(
            "a Status switch must never report under a chat receipt id: $overlap",
            overlap.isEmpty(),
        )
    }

    @Test fun theReplyRuleIsOwnedBy357AndNotReimplemented() {
        val outcomes = ModernStatusPrivacyFeature.Outcome.entries.map { it.name }
        assertTrue(
            "#357 owns the reply rule, so this feature must be able to say so",
            outcomes.contains("OWNED_BY_357"),
        )
    }

    @Test fun anAmbiguousResolverIsReportedRatherThanResolvedByOrder() {
        val outcomes = ModernStatusPrivacyFeature.Outcome.entries.map { it.name }
        assertTrue(outcomes.contains("RESOLVER_AMBIGUOUS"))
        assertTrue(outcomes.contains("UNSAFE_SIGNATURE"))
        assertEquals(
            "two states with the same name would collapse in the report",
            outcomes.size,
            outcomes.distinct().size,
        )
    }

    @Test fun theAnchorsAreTheOnesTheLegacyResolverUsed() {
        // From Unobfuscator.loadBlueOnReplayViewButtonMethod and its sibling in
        // this repository, not invented here.
        assertEquals(
            listOf("PLAYBACK_PAGE_ITEM_ON_CREATE_VIEW_END", "StatusPlaybackPage/onViewCreated"),
            ModernStatusPrivacyFeature.VIEW_ANCHORS,
        )
    }

    @Test fun theLegacyPreferenceKeyIsKept() {
        assertEquals("hidestatusview", ModernStatusPrivacyFeature.PREF_HIDE_STATUS_VIEW)
    }

    @Test fun nothingIsClaimedBeforeAReplyIsObserved() {
        ModernStatusPrivacyFeature.clearRepliedAuthors()
        assertEquals(
            "no reply means no status author to release a receipt for",
            0,
            ModernStatusPrivacyFeature.repliedAuthorCount(),
        )
        assertFalse(ModernStatusPrivacyFeature.repliedAuthorCount() > 0)
        ModernStatusPrivacyFeature.clearRepliedAuthors()
    }
}
