package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JID rules are the part that silently attributes a contact to the wrong
 * number, so every branch the legacy implementation has is pinned here.
 */
class ModernJidAccessTest {
    @Test fun plainPhoneJidYieldsTheNumber() {
        assertEquals("15551234567", JidRules.phoneNumber("15551234567@s.whatsapp.net"))
    }

    @Test fun deviceSuffixIsStrippedBeforeParsing() {
        assertEquals(
            "15551234567@s.whatsapp.net",
            JidRules.stripDeviceSuffix("15551234567.12:34@s.whatsapp.net"),
        )
        assertEquals(
            "15551234567",
            JidRules.phoneNumber(JidRules.stripDeviceSuffix("15551234567.1:2@s.whatsapp.net")),
        )
    }

    @Test fun dotBeforeAtWinsOverTheKnownSuffix() {
        // The legacy order: a dot before the '@' is taken first.
        assertEquals("15551234567", JidRules.phoneNumber("15551234567.5@s.whatsapp.net"))
    }

    @Test fun groupAndBroadcastJidsCarryNoNumber() {
        assertEquals("12345", JidRules.phoneNumber("12345@g.us"))
        assertEquals("status", JidRules.phoneNumber("status@broadcast"))
    }

    @Test fun lidKeepsItsLocalPart() {
        assertEquals("99887766", JidRules.phoneNumber("99887766@lid"))
    }

    @Test fun unknownDomainFallsBackToTheRawString() {
        assertEquals("12345.example.com", JidRules.phoneNumber("12345.example.com"))
    }

    @Test fun emptyAndNullAreNeverTurnedIntoNumbers() {
        assertNull(JidRules.phoneNumber(null))
        assertNull(JidRules.phoneNumber(""))
    }

    @Test fun jidWithoutALocalPartIsFlaggedRatherThanSilentlyNumbered() {
        // The legacy derivation returns the raw string here (the '@' index is 0,
        // so the known-domain branch does not apply). Keeping that exact
        // behaviour avoids changing feature decisions during a migration, and
        // isInvalid() is what callers must use before trusting a "number".
        assertEquals("@s.whatsapp.net", JidRules.phoneNumber("@s.whatsapp.net"))
        assertTrue(JidRules.isInvalid("@s.whatsapp.net"))
    }

    @Test fun invalidJidsAreDetected() {
        assertTrue(JidRules.isInvalid(null))
        assertTrue(JidRules.isInvalid(""))
        assertTrue(JidRules.isInvalid("@s.whatsapp.net"))
        assertTrue(JidRules.isInvalid("15551234567@"))
        assertFalse(JidRules.isInvalid("15551234567@s.whatsapp.net"))
    }

    @Test fun outcomeSetReportsAmbiguityInsteadOfGuessing() {
        val outcomes = ModernJidAccess.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "AVAILABLE", "RAW_STRING_METHOD_MISSING", "RAW_STRING_METHOD_AMBIGUOUS", "ERROR",
        )))
    }
}