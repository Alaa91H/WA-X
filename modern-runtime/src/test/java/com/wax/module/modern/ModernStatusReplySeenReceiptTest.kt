package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Status reply seen-receipt rules (#357).
 *
 * Most assertions are negative. A Status must never be marked seen without a
 * completed reply, an exact identity and a resolved native path, and every one
 * of those failures has to be visible in the reported state.
 */
class ModernStatusReplySeenReceiptTest {
    private fun identity(
        statusId: String = "status-1",
        account: String = "account-a",
        author: String = "author-1",
    ) = ModernStatusReplySeenReceipt.StatusIdentity(
        targetPackage = "com.whatsapp",
        accountId = account,
        statusId = statusId,
        authorKey = author,
        observedAtMillis = 1_000L,
    )

    @Test fun aCompletedReplyWithAResolvedPathSendsTheReceipt() {
        ModernStatusReplySeenReceipt.clearDecisions()
        val decision =
            ModernStatusReplySeenReceipt.decide(
                identity(),
                toggleOn = true,
                replyCompleted = true,
                identityState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                sendResultState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nativeReceiptState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nowMillis = 1_000L,
            )
        assertEquals(ModernStatusReplySeenReceipt.TerminalState.SENT, decision.state)
        ModernStatusReplySeenReceipt.clearDecisions()
    }

    @Test fun anIncompleteReplyNeverMarksTheStatusSeen() {
        ModernStatusReplySeenReceipt.clearDecisions()
        for (state in listOf(
            ModernStatusReplySeenReceipt.CapabilityState.NOT_OBSERVED,
            ModernStatusReplySeenReceipt.CapabilityState.AMBIGUOUS,
            ModernStatusReplySeenReceipt.CapabilityState.FAILED,
        )) {
            val decision =
                ModernStatusReplySeenReceipt.decide(
                    identity(),
                    toggleOn = true,
                    replyCompleted = true,
                    identityState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                    sendResultState = state,
                    nativeReceiptState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                    nowMillis = 1_000L,
                )
            assertEquals(
                "a reply that did not confirm must not mark anything",
                ModernStatusReplySeenReceipt.TerminalState.NOT_APPLICABLE_REPLY_INCOMPLETE,
                decision.state,
            )
        }
        ModernStatusReplySeenReceipt.clearDecisions()
    }

    @Test fun aReplyThatDidNotCompleteIsNotATrigger() {
        ModernStatusReplySeenReceipt.clearDecisions()
        val decision =
            ModernStatusReplySeenReceipt.decide(
                identity(),
                toggleOn = true,
                replyCompleted = false,
                identityState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                sendResultState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nativeReceiptState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nowMillis = 1_000L,
            )
        assertEquals(
            ModernStatusReplySeenReceipt.TerminalState.NOT_APPLICABLE_REPLY_INCOMPLETE,
            decision.state,
        )
        ModernStatusReplySeenReceipt.clearDecisions()
    }

    @Test fun nothingIsSentWhenTheNativePathDidNotResolve() {
        ModernStatusReplySeenReceipt.clearDecisions()
        val decision =
            ModernStatusReplySeenReceipt.decide(
                identity(),
                toggleOn = true,
                replyCompleted = true,
                identityState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                sendResultState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nativeReceiptState = ModernStatusReplySeenReceipt.CapabilityState.NOT_IMPLEMENTED,
                nowMillis = 1_000L,
            )
        assertEquals(
            "an unresolved native path must skip, never fabricate",
            ModernStatusReplySeenReceipt.TerminalState.SKIPPED_NO_NATIVE_PATH,
            decision.state,
        )
        assertTrue(decision.reason.contains("native Status receipt path"))
        ModernStatusReplySeenReceipt.clearDecisions()
    }

    @Test fun aContactAloneIsNotAStatusIdentity() {
        ModernStatusReplySeenReceipt.clearDecisions()
        for (blank in listOf("", "  ")) {
            val incomplete =
                ModernStatusReplySeenReceipt.StatusIdentity(
                    targetPackage = "com.whatsapp",
                    accountId = "account-a",
                    statusId = blank,
                    authorKey = "author-1",
                    observedAtMillis = 1L,
                )
            assertFalse("an empty status id is not an identity", incomplete.isValid())
        }
        val decision =
            ModernStatusReplySeenReceipt.decide(
                identity(statusId = ""),
                toggleOn = true,
                replyCompleted = true,
                identityState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                sendResultState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nativeReceiptState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nowMillis = 1L,
            )
        assertEquals(ModernStatusReplySeenReceipt.TerminalState.SKIPPED_NO_NATIVE_PATH, decision.state)
        ModernStatusReplySeenReceipt.clearDecisions()
    }

    @Test fun twoStatusesFromOneAuthorAreDifferentOperations() {
        val first = identity(statusId = "status-a")
        val second = identity(statusId = "status-b")
        assertFalse(
            "replying to B must never be able to mark A",
            first.dedupKey() == second.dedupKey(),
        )
        assertEquals(first, identity(statusId = "status-a"))
    }

    @Test fun accountsAndTargetsDoNotShareState() {
        val messenger = identity(account = "account-a")
        val business = identity(account = "account-b")
        assertFalse(messenger.dedupKey() == business.dedupKey())
    }

    @Test fun oneStatusIsMarkedAtMostOnceInsideTheWindow() {
        ModernStatusReplySeenReceipt.clearDecisions()
        val target = identity()
        val first =
            ModernStatusReplySeenReceipt.decide(
                target,
                toggleOn = true,
                replyCompleted = true,
                identityState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                sendResultState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nativeReceiptState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nowMillis = 1_000L,
            )
        val second =
            ModernStatusReplySeenReceipt.decide(
                target,
                toggleOn = true,
                replyCompleted = true,
                identityState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                sendResultState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nativeReceiptState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nowMillis = 2_000L,
            )
        assertEquals(ModernStatusReplySeenReceipt.TerminalState.SENT, first.state)
        assertEquals(
            ModernStatusReplySeenReceipt.TerminalState.SUPPRESSED_DUPLICATE,
            second.state,
        )
        ModernStatusReplySeenReceipt.clearDecisions()
    }

    @Test fun aDecisionDoesNotOutliveItsWindow() {
        ModernStatusReplySeenReceipt.clearDecisions()
        val target = identity()
        ModernStatusReplySeenReceipt.recordSent(target, 0L)
        val later = ModernStatusReplySeenReceipt.DEDUP_WINDOW_MILLIS + 1
        assertFalse(
            "suppression must not become permanent",
            ModernStatusReplySeenReceipt.wasAlreadyDecided(target, later),
        )
        ModernStatusReplySeenReceipt.clearDecisions()
    }

    @Test fun theToggleBeingOffDecidesNothing() {
        ModernStatusReplySeenReceipt.clearDecisions()
        val decision =
            ModernStatusReplySeenReceipt.decide(
                identity(),
                toggleOn = false,
                replyCompleted = true,
                identityState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                sendResultState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nativeReceiptState = ModernStatusReplySeenReceipt.CapabilityState.AVAILABLE,
                nowMillis = 1L,
            )
        assertEquals(ModernStatusReplySeenReceipt.TerminalState.DISABLED, decision.state)
        assertEquals("the switch is off", decision.reason)
        ModernStatusReplySeenReceipt.clearDecisions()
    }

    @Test fun purgingRemovesOnlyStaleDecisions() {
        ModernStatusReplySeenReceipt.clearDecisions()
        ModernStatusReplySeenReceipt.recordSent(identity(statusId = "old"), 0L)
        ModernStatusReplySeenReceipt.recordSent(identity(statusId = "new"), 100_000L)
        val after = ModernStatusReplySeenReceipt.DEDUP_WINDOW_MILLIS + 1
        assertEquals(1, ModernStatusReplySeenReceipt.purgeOlderThan(after))
        ModernStatusReplySeenReceipt.clearDecisions()
    }
}
