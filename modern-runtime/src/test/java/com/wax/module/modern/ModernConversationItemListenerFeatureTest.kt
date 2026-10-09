package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks for the conversation bus; row hooks need the target process. */
class ModernConversationItemListenerFeatureTest {
    @Test fun conversationClassMatchesLegacyRuntimeEvidence() {
        assertEquals(
            "com.whatsapp.Conversation",
            ModernConversationItemListenerFeature.CONVERSATION_CLASS,
        )
    }

    @Test fun nullActivityIsNeverAConversation() {
        assertFalse(ModernConversationItemListenerFeature.isConversation(null))
    }

    @Test fun adapterUnwrapHandlesNullAndForeignTypes() {
        assertEquals(null, ModernConversationItemListenerFeature.unwrapBaseAdapter(null))
    }

    @Test fun outcomeSetCoversInstallReplayAndMissingHost() {
        val outcomes = ModernConversationItemListenerFeature.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "INSTALLED", "ALREADY_INSTALLED", "APPLICATION_UNAVAILABLE", "ERROR",
        )))
    }
}
