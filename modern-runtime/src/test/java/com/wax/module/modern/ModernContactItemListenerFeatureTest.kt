package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure resolver-policy checks; hook execution needs the target process. */
class ModernContactItemListenerFeatureTest {
    @Test fun anchorsMatchTheLegacyUnobfuscatorEvidence() {
        // These strings must equal the anchors in Unobfuscator.loadOnChangeStatus
        // and loadAbsViewHolder; a change on either side must break this test.
        assertEquals(
            "ConversationViewFiller/setParentGroupProfilePhoto",
            ModernContactItemListenerFeature.ANCHOR_ON_CHANGE_STATUS,
        )
        assertEquals("not recyclable", ModernContactItemListenerFeature.ANCHOR_ABS_VIEW_HOLDER)
    }

    @Test fun parameterRangeMatchesTheLegacyFallbackRefinement() {
        assertTrue(ModernContactItemListenerFeature.isPlausibleOnChangeStatus(6))
        assertTrue(ModernContactItemListenerFeature.isPlausibleOnChangeStatus(8))
        assertFalse(ModernContactItemListenerFeature.isPlausibleOnChangeStatus(5))
        assertFalse(ModernContactItemListenerFeature.isPlausibleOnChangeStatus(9))
    }

    @Test fun outcomeSetCoversMissingAmbiguousAndUnsafe() {
        val outcomes = ModernContactItemListenerFeature.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "INSTALLED", "RESOLVER_MISSING", "RESOLVER_AMBIGUOUS", "UNSAFE_SIGNATURE", "ERROR",
        )))
    }
}
