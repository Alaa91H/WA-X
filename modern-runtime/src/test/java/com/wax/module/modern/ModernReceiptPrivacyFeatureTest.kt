package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receipt privacy decision rules (#449).
 *
 * The behaviours are separate claims, so the rules that decide them are tested
 * separately. What matters most here is the negative space: what must *not*
 * release a withheld read receipt.
 */
class ModernReceiptPrivacyFeatureTest {
    @Test fun theAnchorsMatchTheLegacyResolverEvidence() {
        // Taken from Unobfuscator in this repository, not guessed: the receipt
        // send is the method using the sendReceiptForIncomingMessage literal.
        assertEquals(
            "ReadReceipts/sendReceiptForIncomingMessage",
            ModernReceiptPrivacyFeature.ANCHOR_READ_RECEIPT,
        )
        assertEquals("SendReadReceiptJob", ModernReceiptPrivacyFeature.RECEIPT_JOB_SUFFIX)
    }

    @Test fun aCompletedReplyArmsExactlyOneConversation() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        assertTrue(
            ModernReceiptPrivacyFeature.onSendCompleted(
                "chat-a",
                ModernReceiptPrivacyFeature.SendResult.SUCCEEDED,
                1_000L,
            ),
        )
        assertTrue(ModernReceiptPrivacyFeature.isArmed("chat-a", 1_000L))
        assertFalse("a reply in one chat must not arm another", ModernReceiptPrivacyFeature.isArmed("chat-b", 1_000L))
        ModernReceiptPrivacyFeature.clearArmedConversations()
    }

    @Test fun nothingButACompletedSendArmsTheRelease() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        for (result in listOf(ModernReceiptPrivacyFeature.SendResult.NOT_COMPLETED)) {
            assertFalse(
                "an unfinished send must never release a receipt",
                ModernReceiptPrivacyFeature.onSendCompleted("chat", result, 1L),
            )
        }
        assertFalse(ModernReceiptPrivacyFeature.isArmed("chat", 1L))
        ModernReceiptPrivacyFeature.clearArmedConversations()
    }

    @Test fun aSendWithNoConversationIsNeverEnough() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        assertFalse(
            ModernReceiptPrivacyFeature.onSendCompleted(
                null,
                ModernReceiptPrivacyFeature.SendResult.SUCCEEDED,
                1L,
            ),
        )
        assertFalse(
            ModernReceiptPrivacyFeature.onSendCompleted(
                "",
                ModernReceiptPrivacyFeature.SendResult.SUCCEEDED,
                1L,
            ),
        )
        ModernReceiptPrivacyFeature.clearArmedConversations()
    }

    @Test fun theReleaseHappensOnceAndNotAgain() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        ModernReceiptPrivacyFeature.onSendCompleted(
            "chat-a",
            ModernReceiptPrivacyFeature.SendResult.SUCCEEDED,
            1_000L,
        )
        assertTrue(
            "the first receipt after the reply releases",
            ModernReceiptPrivacyFeature.consumeArming("chat-a", 1_001L),
        )
        assertFalse(
            "a queued second receipt must not release again",
            ModernReceiptPrivacyFeature.consumeArming("chat-a", 1_002L),
        )
        ModernReceiptPrivacyFeature.clearArmedConversations()
    }

    @Test fun anArmingExpiresRatherThanStandingForever() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        ModernReceiptPrivacyFeature.onSendCompleted(
            "chat-a",
            ModernReceiptPrivacyFeature.SendResult.SUCCEEDED,
            0L,
        )
        val after =
            ModernReceiptPrivacyFeature.RELEASE_WINDOW_MILLIS + 1
        assertFalse(
            "one reply must not become a standing permission to read later messages",
            ModernReceiptPrivacyFeature.isArmed("chat-a", after),
        )
        assertFalse(ModernReceiptPrivacyFeature.consumeArming("chat-a", after))
        ModernReceiptPrivacyFeature.clearArmedConversations()
    }

    @Test fun theOutcomesSeparateWhatWorksFromWhatCannot() {
        val outcomes = ModernReceiptPrivacyFeature.Outcome.entries.map { it.name }
        assertTrue(
            "delivery suppression must be reportable as unsupported",
            outcomes.contains("UNSUPPORTED"),
        )
        assertTrue(outcomes.contains("INSTALLED"))
        assertTrue(outcomes.contains("RESOLVER_AMBIGUOUS"))
        assertTrue(outcomes.contains("DISABLED"))
    }

    @Test fun everyBehavioursHasItsOwnFeatureId() {
        val ids =
            listOf(
                ModernReceiptPrivacyFeature.FEATURE_ID_READ,
                ModernReceiptPrivacyFeature.FEATURE_ID_AFTER_REPLY,
                ModernReceiptPrivacyFeature.FEATURE_ID_DELIVERY,
            )
        assertEquals(
            "the three claims must not collapse into one id",
            ids.size,
            ids.distinct().size,
        )
        assertNotNull(ModernReceiptPrivacyFeature.PREF_HIDE_READ)
    }

    @Test fun thePreferenceKeysAreTheLegacyOnes() {
        // An existing user's switch must keep its meaning after the port.
        assertEquals("hideread", ModernReceiptPrivacyFeature.PREF_HIDE_READ)
        assertEquals("hideread_group", ModernReceiptPrivacyFeature.PREF_HIDE_READ_GROUP)
        assertEquals("hidereceipt", ModernReceiptPrivacyFeature.PREF_HIDE_RECEIPT)
    }
}
