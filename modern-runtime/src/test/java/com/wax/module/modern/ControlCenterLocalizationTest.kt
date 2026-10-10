package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Localization and favourites must be real behaviour, not decoration. */
class ControlCenterLocalizationTest {
    @Test fun arabicLocaleSelectsArabicTable() {
        assertEquals("ابحث في مزايا WA X",
            ControlCenterStrings.forLanguage("ar").searchHint)
        assertEquals("إعادة تشغيل واتساب",
            ControlCenterStrings.forLanguage("ar").restart)
    }

    @Test fun unknownOrNullLocaleFallsBackToEnglish() {
        assertEquals("Search WA X features",
            ControlCenterStrings.forLanguage("zz").searchHint)
        assertEquals("Search WA X features",
            ControlCenterStrings.forLanguage(null).searchHint)
    }

    @Test fun shippedFrenchAndGermanLocalesReuseManagerTranslations() {
        assertNotEquals("Search WA X features",
            ControlCenterStrings.forLanguage("fr").searchHint)
        assertNotEquals("Freeze Last Seen",
            ControlCenterLabels.forLanguage("de").title("freeze_last_seen", "Freeze Last Seen"))
    }

    @Test fun legacyLanguageCodesResolveToCurrentLocales() {
        assertNotEquals("Freeze Last Seen",
            ControlCenterLabels.forLanguage("iw").title("freeze_last_seen", "Freeze Last Seen"))
        assertNotEquals("Freeze Last Seen",
            ControlCenterLabels.forLanguage("in").title("freeze_last_seen", "Freeze Last Seen"))
    }

    @Test fun everyTableHasCompleteStrings() {
        for (table in listOf(
            ControlCenterStrings.forLanguage("en"),
            ControlCenterStrings.forLanguage("ar"),
        )) {
            assertTrue(table.title.isNotEmpty())
            assertTrue(table.searchHint.isNotEmpty())
            assertTrue(table.noResults.isNotEmpty())
            assertTrue(table.restart.isNotEmpty())
            assertTrue(table.openManager.isNotEmpty())
            assertTrue(table.favorites.isNotEmpty())
            assertTrue(table.favoritesOnly.isNotEmpty())
        }
    }

    @Test fun arabicIsTranslatedNotCopied() {
        val english = ControlCenterStrings.forLanguage("en")
        val arabic = ControlCenterStrings.forLanguage("ar")
        assertNotEquals(english.searchHint, arabic.searchHint)
        assertNotEquals(english.restart, arabic.restart)
        assertEquals("WA X", arabic.title)
    }

    @Test fun favouritesRoundTripThroughTheStoredFormat() {
        val ids = setOf("custom_time", "dnd_mode")
        val stored = ModernControlCenterCatalog.formatFavorites(ids)
        assertEquals(ids, ModernControlCenterCatalog.parseFavorites(stored))
    }

    @Test fun emptyFavouritesAreHandled() {
        assertTrue(ModernControlCenterCatalog.parseFavorites(null).isEmpty())
        assertTrue(ModernControlCenterCatalog.parseFavorites("").isEmpty())
        assertTrue(ModernControlCenterCatalog.parseFavorites("  ").isEmpty())
        assertEquals("", ModernControlCenterCatalog.formatFavorites(emptySet()))
    }

    @Test fun malformedFavouritesEntriesAreDropped() {
        val parsed = ModernControlCenterCatalog.parseFavorites("custom_time, ,dnd_mode,")
        assertEquals(setOf("custom_time", "dnd_mode"), parsed)
    }

    @Test fun favouritesKeyIsStable() {
        assertEquals("wax.control_center.favorites", ModernControlCenterCatalog.FAVORITES_KEY)
    }
}