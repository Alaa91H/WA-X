package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks for HideChat; the archive view itself needs WhatsApp. */
class ModernHideChatFeatureTest {
    @Test fun anchorsMatchTheLegacyUnobfuscatorEvidence() {
        assertEquals(
            "archive/set-content-indicator-to-empty",
            ModernHideChatFeature.ANCHOR_PRIMARY,
        )
        assertEquals(
            "archive/Unsupported mode in ArchivePreviewView:",
            ModernHideChatFeature.ANCHOR_FALLBACK,
        )
    }

    @Test fun preferenceKeyMatchesTheManagerListPreference() {
        // Must equal the key in res/xml/fragment_privacy.xml.
        assertEquals("typearchive", ModernHideChatFeature.PREF_ARCHIVE_MODE)
    }

    @Test fun onlyTheDisabledModeTurnsTheFeatureOff() {
        assertFalse(ModernHideChatFeature.isEnabled("0"))
        assertFalse(ModernHideChatFeature.isEnabled(null))
        assertTrue(ModernHideChatFeature.isEnabled("1"))
        assertTrue(ModernHideChatFeature.isEnabled("2"))
    }

    @Test fun outcomeSetCoversResolverAmbiguityAndMissingField() {
        val outcomes = ModernHideChatFeature.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "DISABLED", "RESOLVER_MISSING", "RESOLVER_AMBIGUOUS", "VIEW_FIELD_MISSING", "ERROR",
        )))
    }
}