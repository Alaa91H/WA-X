package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks for the message accessor chain; resolution needs WhatsApp. */
class ModernMessageAccessTest {
    @Test fun anchorsMatchTheLegacyUnobfuscatorEvidence() {
        assertEquals("FMessage/getSenderUserJid/key.id", ModernMessageAccess.ANCHOR_MESSAGE_CLASS)
        assertEquals("Key", ModernMessageAccess.ANCHOR_KEY_TOSTRING)
        assertEquals(3, ModernMessageAccess.KEY_FIELD_COUNT)
        assertEquals("jid.Jid", ModernMessageAccess.JID_SUFFIX)
    }

    @Test fun outcomeSetReportsWhatIsMissingInsteadOfThrowing() {
        val outcomes = ModernMessageAccess.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "AVAILABLE", "MESSAGE_CLASS_MISSING", "KEY_CLASS_MISSING",
            "KEY_FIELD_MISSING", "ERROR",
        )))
    }
}
