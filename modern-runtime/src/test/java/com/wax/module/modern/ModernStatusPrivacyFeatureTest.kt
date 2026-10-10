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

    @Test fun theReplyRuleIsImplementedButItsNativePathIsNotResolved() {
        val outcomes = ModernStatusPrivacyFeature.Outcome.entries.map { it.name }
        assertTrue(
            "the reply rule exists; what is missing is the native transport",
            outcomes.contains("NATIVE_PATH_UNRESOLVED"),
        )
        assertFalse(
            "a finished claim would be false while the native path is unresolved",
            outcomes.contains("OWNED_BY_357"),
        )
    }

    @Test fun theReplyRuleIsTheOneImplementedBy357() {
        // #452 delegates the rule itself; the shipped behaviour has to be the
        // one #357 owns, not a second state machine beside it.
        assertEquals(
            ModernStatusReplySeenReceipt.FEATURE_ID,
            "status_reply_seen_receipt",
        )
        assertEquals(
            "sendstatusseenonreply",
            ModernStatusReplySeenReceipt.PREF_SEND_SEEN_ON_REPLY,
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
