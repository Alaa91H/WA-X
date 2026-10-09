package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks for the context-menu bus; the popup itself needs WhatsApp. */
class ModernContextMenuActionProviderFeatureTest {
    @Test fun popupAnchorMatchesTheLegacyUnobfuscatorEvidence() {
        assertEquals(
            "MessageSelectionDropDownRecyclerView",
            ModernContextMenuActionProviderFeature.ANCHOR_POPUP,
        )
    }

    @Test fun trayResourceMatchesTheLegacyLookup() {
        assertEquals("reactions_tray_layout", ModernContextMenuActionProviderFeature.TRAY_RESOURCE_ID)
    }

    @Test fun outcomeSetCoversResolverAmbiguity() {
        val outcomes = ModernContextMenuActionProviderFeature.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "INSTALLED", "RESOLVER_MISSING", "RESOLVER_AMBIGUOUS", "ERROR",
        )))
    }

    @Test fun providersCanDeclineToContribute() {
        // A provider returning null must simply add nothing; the data class
        // defaults keep the common "auto-dismiss on click" case short.
        val action = ModernContextMenuActionProviderFeature.ContextMenuAction(title = "Forward")
        assertEquals("Forward", action.title)
        assertTrue(action.autoDismiss)
    }
}