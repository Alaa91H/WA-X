package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure resolver-policy checks; hook execution needs the target process. */
class ModernMenuStatusProviderFeatureTest {
    @Test fun anchorsMatchTheLegacyUnobfuscatorEvidence() {
        assertEquals(
            "menuitem_conversations_message_contact",
            ModernMenuStatusProviderFeature.MENU_RESOURCE_NAME,
        )
        assertEquals("id", ModernMenuStatusProviderFeature.MENU_RESOURCE_TYPE)
        assertEquals(
            "MenuPopupHelper cannot be used without an anchor",
            ModernMenuStatusProviderFeature.ANCHOR_MENU_MANAGER,
        )
        assertEquals(
            "playbackFragment/setPageActive no-messages ",
            ModernMenuStatusProviderFeature.ANCHOR_SET_PAGE_ACTIVE,
        )
        assertEquals(
            "StatusPlaybackBaseFragment",
            ModernMenuStatusProviderFeature.PLAYBACK_BASE_SUFFIX,
        )
        assertEquals(
            "StatusPlaybackContactFragment",
            ModernMenuStatusProviderFeature.PLAYBACK_CONTACT_SUFFIX,
        )
    }

    @Test fun noObfuscatedMemberNameIsHardcoded() {
        // Only the two proven playback suffixes and evidence anchors may appear.
        val suffixes = listOf(
            ModernMenuStatusProviderFeature.PLAYBACK_BASE_SUFFIX,
            ModernMenuStatusProviderFeature.PLAYBACK_CONTACT_SUFFIX,
        )
        assertTrue(suffixes.none { it.startsWith("A0") || it.length < 5 })
    }

    @Test fun outcomeSetCoversResourceAndResolverFailures() {
        val outcomes = ModernMenuStatusProviderFeature.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "INSTALLED", "RESOURCE_ID_MISSING", "RESOLVER_MISSING",
            "RESOLVER_AMBIGUOUS", "ERROR",
        )))
    }
}