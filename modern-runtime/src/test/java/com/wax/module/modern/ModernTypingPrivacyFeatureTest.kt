package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks for TypingPrivacy; suppression needs the real hook. */
class ModernTypingPrivacyFeatureTest {
    @Test fun anchorMatchesTheLegacyUnobfuscatorEvidence() {
        assertEquals(
            "HandleMeComposing/sendComposing",
            ModernTypingPrivacyFeature.ANCHOR_COMPOSING,
        )
    }

    @Test fun stateTypesMatchTheLegacySemantics() {
        assertEquals(0, ModernTypingPrivacyFeature.STATE_TYPING)
        assertEquals(1, ModernTypingPrivacyFeature.STATE_RECORDING)
    }

    @Test fun typingIsSuppressedOnlyByTheTypingRule() {
        assertTrue(
            ModernTypingPrivacyFeature.shouldSuppress(
                ModernTypingPrivacyFeature.STATE_TYPING, hideTyping = true, hideRecording = false,
            ),
        )
        assertFalse(
            ModernTypingPrivacyFeature.shouldSuppress(
                ModernTypingPrivacyFeature.STATE_TYPING, hideTyping = false, hideRecording = true,
            ),
        )
    }

    @Test fun recordingIsSuppressedOnlyByTheRecordingRule() {
        assertTrue(
            ModernTypingPrivacyFeature.shouldSuppress(
                ModernTypingPrivacyFeature.STATE_RECORDING, hideTyping = false, hideRecording = true,
            ),
        )
        assertFalse(
            ModernTypingPrivacyFeature.shouldSuppress(
                ModernTypingPrivacyFeature.STATE_RECORDING, hideTyping = true, hideRecording = false,
            ),
        )
    }

    @Test fun nothingIsSuppressedWhenNoRuleApplies() {
        for (state in listOf(
            ModernTypingPrivacyFeature.STATE_TYPING,
            ModernTypingPrivacyFeature.STATE_RECORDING,
            7,
        )) {
            assertFalse(
                ModernTypingPrivacyFeature.shouldSuppress(
                    state, hideTyping = false, hideRecording = false,
                ),
            )
        }
    }

    @Test fun preferenceKeysMatchTheLegacyFeature() {
        assertEquals("ghostmode", ModernTypingPrivacyFeature.PREF_GHOSTMODE)
        assertEquals("ghostmode_t", ModernTypingPrivacyFeature.PREF_GHOSTMODE_TYPING)
        assertEquals("ghostmode_r", ModernTypingPrivacyFeature.PREF_GHOSTMODE_RECORDING)
    }

    @Test fun outcomeSetCoversResolverAndSignatureFailures() {
        val outcomes = ModernTypingPrivacyFeature.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "DISABLED", "INSTALLED", "RESOLVER_MISSING", "RESOLVER_AMBIGUOUS",
            "UNSAFE_SIGNATURE", "ERROR",
        )))
    }

    @Test fun ruleCacheCanBeClearedAfterASettingsChange() {
        ModernTypingPrivacyFeature.clearRuleCache()
        assertTrue(ModernTypingPrivacyFeature.shouldSuppress(
            ModernTypingPrivacyFeature.STATE_TYPING, hideTyping = true, hideRecording = false,
        ))
    }
}