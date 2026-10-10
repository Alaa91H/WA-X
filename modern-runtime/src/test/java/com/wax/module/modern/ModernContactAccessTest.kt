package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks for the contact accessor chain; resolution needs WhatsApp. */
class ModernContactAccessTest {
    @Test fun candidateSelectionDistinguishesMissingUniqueAndAmbiguousResults() {
        assertEquals(CandidateSelection.Missing, CandidateSelection.from(emptyList<String>()))
        assertEquals(CandidateSelection.Unique("only"), CandidateSelection.from(listOf("only")))
        assertEquals(
            CandidateSelection.Ambiguous,
            CandidateSelection.from(listOf("first", "second")),
        )
    }

    @Test fun anchorsMatchTheLegacyUnobfuscatorEvidence() {
        assertEquals("problematic contact:", ModernContactAccess.ANCHOR_CONTACT)
        assertEquals("WaContactData", ModernContactAccess.CONTACT_DATA_SUFFIX)
        assertEquals("jid.Jid", ModernContactAccess.JID_SUFFIX)
        assertEquals(
            "WaJidMapRepository/getPhoneJidByAccountUserJid",
            ModernContactAccess.ANCHOR_PHONE_JID,
        )
    }

    @Test fun outcomeSetReportsWhatIsMissingInsteadOfThrowing() {
        val outcomes = ModernContactAccess.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "AVAILABLE", "CONTACT_CLASS_MISSING", "CONTACT_CLASS_AMBIGUOUS",
            "CONTACT_DATA_CLASS_MISSING", "CONTACT_DATA_CLASS_AMBIGUOUS",
            "JID_CLASS_MISSING", "JID_CLASS_AMBIGUOUS", "PHONE_JID_METHOD_AMBIGUOUS",
            "PHONE_JID_FIELD_AMBIGUOUS",
            "USER_JID_FIELD_MISSING", "USER_JID_FIELD_AMBIGUOUS", "ERROR",
        )))
    }
}
