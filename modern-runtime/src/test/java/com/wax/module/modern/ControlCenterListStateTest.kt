package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterListStateTest {
    private fun row(id: String, category: ControlCategory) = ControlEntry(
        id = id,
        title = id,
        description = "Sample feature",
        category = category,
        preferenceKey = "pref.$id",
        requested = ControlRequested.DISABLED,
        effective = ControlEffective.DISABLED,
        writable = true,
        restartRequired = false,
    )

    private val entries = listOf(
        row("freeze_last_seen", ControlCategory.PRIVACY),
        row("share_limit", ControlCategory.CHATS),
        row("view_once", ControlCategory.PRIVACY),
    )

    @Test fun allCategoryShowsEveryEntryOnce() {
        assertEquals(entries, ControlCenterListState.visible(
            entries, "", null, false, emptySet(),
        ))
    }

    @Test fun onlySelectedCategoryIsShown() {
        assertEquals(listOf("freeze_last_seen", "view_once"),
            ControlCenterListState.visible(entries, "", ControlCategory.PRIVACY,
                false, emptySet()).map { it.id })
    }

    @Test fun favoritesFilterDoesNotDuplicateEntries() {
        assertEquals(listOf("share_limit"),
            ControlCenterListState.visible(entries, "", null, true,
                setOf("share_limit")).map { it.id })
    }

    @Test fun searchWorksAcrossIdsAndTitles() {
        assertEquals(listOf("view_once"),
            ControlCenterListState.visible(entries, "VIEW_ONCE", null, false,
                emptySet()).map { it.id })
    }

    @Test fun arabicSearchMatchesLocalizedTitle() {
        val items = listOf(row("typing_privacy", ControlCategory.PRIVACY))
        assertEquals(1, ControlCenterListState.visible(
            items, "اخفاء", null, false, emptySet(),
            ControlCenterLabels.forLanguage("ar"),
        ).size)
    }

    @Test fun emptyMatchesDoNotLeakFromOtherCategories() {
        assertTrue(ControlCenterListState.visible(entries, "share", ControlCategory.PRIVACY,
            false, emptySet()).isEmpty())
    }
}
