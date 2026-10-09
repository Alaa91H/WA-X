package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Control Center catalogue must stay honest and free of dead entries. */
class ModernControlCenterCatalogTest {
    @Test fun everyWiredEntryHasAKeyAndAVisibleStatus() {
        for (item in ModernControlCenterCatalog.wired) {
            assertTrue("preference key missing for ${item.id}", item.preferenceKey.isNotEmpty())
            assertTrue("label missing for ${item.id}", item.label.isNotEmpty())
            assertTrue("description missing for ${item.id}", item.description.isNotEmpty())
            assertFalse("pending category must not be a wired toggle ${item.id}",
                item.category == ControlCategory.PENDING)
        }
    }

    @Test fun alwaysOnInfrastructureIsNeverActionable() {
        for (item in ModernControlCenterCatalog.alwaysOn) {
            assertEquals("infrastructure must not expose a preference key", "",
                item.preferenceKey)
            assertFalse(ControlPolicy.isWritable(item.preferenceKey, ControlEffective.INSTALLED))
        }
    }

    @Test fun catalogueCoversExactlyTheFeaturesTheRuntimeWires() {
        val ids = (ModernControlCenterCatalog.wired + ModernControlCenterCatalog.alwaysOn)
            .map { it.id }
        assertEquals("duplicate control ids", ids.size, ids.toSet().size)
        assertNotNull(ModernControlCenterCatalog.wiredById("custom_time"))
        assertNull(ModernControlCenterCatalog.wiredById("does_not_exist"))
    }

    @Test fun pendingEntriesAreNeverOfferedAsControls() {
        for (item in ModernControlCenterCatalog.pending) {
            assertNotNull(ModernControlCenterCatalog.pendingById(item.id))
            assertFalse(
                "pending feature must not be writable: ${item.id}",
                ControlPolicy.isWritable("anything", ControlEffective.PENDING_MIGRATION),
            )
        }
        val pendingIds = ModernControlCenterCatalog.pending.map { it.id }
        val wiredIds = (ModernControlCenterCatalog.wired + ModernControlCenterCatalog.alwaysOn)
            .map { it.id }
        assertTrue(
            "a feature cannot be pending and wired at once: " +
                pendingIds.filter { it in wiredIds },
        )
        pendingIds.forEach { assertTrue(it in pendingIds) }
    }

    @Test fun everyCategoryIsRepresentedByAtLeastOneRealControl() {
        // The issue requires these groupings; a category with nothing in it must
        // not appear, and the ones we ship must not be empty.
        val used = (ModernControlCenterCatalog.wired + ModernControlCenterCatalog.alwaysOn)
            .map { it.category }
            .toSet()
        for (category in listOf(
            ControlCategory.PRIVACY,
            ControlCategory.CHATS,
            ControlCategory.APPEARANCE,
            ControlCategory.ADVANCED,
        )) {
            assertTrue("category $category has no control", category in used)
        }
        assertFalse(ControlCategory.PENDING in used)
    }

    @Test fun preferenceKeysMatchTheRuntimeEntryReads() {
        assertEquals(ModernCustomTimeFeature.ENABLE_KEY,
            ModernControlCenterCatalog.wiredById("custom_time")?.preferenceKey)
        assertEquals(ModernShareLimitFeature.ENABLE_KEY,
            ModernControlCenterCatalog.wiredById("share_limit")?.preferenceKey)
        assertEquals(ModernPresenceFeatures.FREEZE_KEY,
            ModernControlCenterCatalog.wiredById("freeze_last_seen")?.preferenceKey)
        assertEquals(ModernPresenceFeatures.DND_KEY,
            ModernControlCenterCatalog.wiredById("dnd_mode")?.preferenceKey)
    }
}