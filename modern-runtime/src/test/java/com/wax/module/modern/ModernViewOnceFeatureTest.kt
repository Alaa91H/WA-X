package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks for ViewOnce; rewriting a real message state needs WhatsApp. */
class ModernViewOnceFeatureTest {
    class Candidate(val value: Int) {
        fun voidTarget(value: Int) = Unit
        fun wrongReturn(value: Int): String = ""
        fun wrongParameter(value: String) = Unit
        fun noArguments() = Unit
    }

    @Test fun anchorsMatchTheLegacyUnobfuscatorEvidence() {
        assertEquals("INSERT_VIEW_ONCE_SQL", ModernViewOnceFeature.ANCHOR_VIEW_ONCE_SQL)
        assertEquals(2, ModernViewOnceFeature.INTERFACE_METHOD_COUNT)
        assertEquals("viewonce", ModernViewOnceFeature.PREF_ENABLE)
    }

    @Test fun onlyTheRewritableSignatureIsAccepted() {
        assertTrue(
            ModernViewOnceFeature.isRewritableStateMethod(
                Candidate::class.java.getDeclaredMethod("voidTarget", Int::class.javaPrimitiveType),
            ),
        )
        assertFalse(
            ModernViewOnceFeature.isRewritableStateMethod(
                Candidate::class.java.getDeclaredMethod("wrongReturn", Int::class.javaPrimitiveType),
            ),
        )
        assertFalse(
            ModernViewOnceFeature.isRewritableStateMethod(
                Candidate::class.java.getDeclaredMethod("wrongParameter", String::class.java),
            ),
        )
        assertFalse(
            ModernViewOnceFeature.isRewritableStateMethod(
                Candidate::class.java.getDeclaredMethod("noArguments"),
            ),
        )
    }

    @Test fun aViewedIncomingMessageIsRewritten() {
        assertTrue(ModernViewOnceFeature.shouldRewrite(1, fromMe = false))
    }

    @Test fun ownMessagesAndUnviewedStatesAreLeftAlone() {
        assertFalse("own message", ModernViewOnceFeature.shouldRewrite(1, fromMe = true))
        assertFalse("not viewed", ModernViewOnceFeature.shouldRewrite(0, fromMe = false))
    }

    @Test fun outcomeSetCoversEveryResolverStage() {
        val outcomes = ModernViewOnceFeature.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "DISABLED", "INSTALLED", "RESOLVER_MISSING", "RESOLVER_AMBIGUOUS",
            "NO_TARGETS", "ERROR",
        )))
    }
}