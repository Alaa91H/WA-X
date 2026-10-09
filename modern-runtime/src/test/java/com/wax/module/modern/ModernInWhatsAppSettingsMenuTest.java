package com.wax.module.modern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Pure checks for the in-WhatsApp settings shell; no Android runtime needed. */
public final class ModernInWhatsAppSettingsMenuTest {
    @Test public void exactlyFourMigratedTogglesAreExposed() {
        assertEquals(4, ModernInWhatsAppSettingsMenu.Toggle.values().length);
    }

    @Test public void toggleKeysMatchTheEntryRemotePreferenceReads() {
        // These strings must equal the ENABLE_KEY / pilot preference keys the
        // modern entry reads; a rename on either side must break this test.
        assertEquals("modern.feature.custom_time.enabled",
                ModernInWhatsAppSettingsMenu.Toggle.CUSTOM_TIME.key);
        assertEquals("removeforwardlimit",
                ModernInWhatsAppSettingsMenu.Toggle.SHARE_LIMIT.key);
        assertEquals("freezelastseen",
                ModernInWhatsAppSettingsMenu.Toggle.FREEZE_LAST_SEEN.key);
        assertEquals("dndmode",
                ModernInWhatsAppSettingsMenu.Toggle.DND_MODE.key);
        assertEquals(ModernCustomTimeFeature.ENABLE_KEY,
                ModernInWhatsAppSettingsMenu.Toggle.CUSTOM_TIME.key);
        assertEquals(ModernShareLimitFeature.ENABLE_KEY,
                ModernInWhatsAppSettingsMenu.Toggle.SHARE_LIMIT.key);
        assertEquals(ModernPresenceFeatures.FREEZE_KEY,
                ModernInWhatsAppSettingsMenu.Toggle.FREEZE_LAST_SEEN.key);
        assertEquals(ModernPresenceFeatures.DND_KEY,
                ModernInWhatsAppSettingsMenu.Toggle.DND_MODE.key);
    }

    @Test public void menuItemIdsAreUniqueAndDoNotClashWithMenuHome() {
        Set<Integer> ids = new HashSet<>();
        for (ModernInWhatsAppSettingsMenu.Toggle toggle
                : ModernInWhatsAppSettingsMenu.Toggle.values()) {
            assertTrue(ids.add(ModernInWhatsAppSettingsMenu.MENU_BASE_ID + toggle.ordinal()));
        }
        assertTrue(ids.add(ModernInWhatsAppSettingsMenu.RESTART_ITEM_ID));
        assertFalse(ids.contains(ModernMenuHomeFeature.MENU_ITEM_ID));
    }

    @Test public void titlesStateTheToggleHonestly() {
        assertEquals("WA X \u00B7 Custom Time: ON",
                ModernInWhatsAppSettingsMenu.titleFor(
                        ModernInWhatsAppSettingsMenu.Toggle.CUSTOM_TIME, true));
        assertEquals("WA X \u00B7 DND Mode: OFF",
                ModernInWhatsAppSettingsMenu.titleFor(
                        ModernInWhatsAppSettingsMenu.Toggle.DND_MODE, false));
    }
}
