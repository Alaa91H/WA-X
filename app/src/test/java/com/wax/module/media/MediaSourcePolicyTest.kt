package com.wax.module.media

import com.wax.module.platform.InMemoryKeyValueStore
import com.wax.module.platform.TargetApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Android photo picker direct mode.
 *
 * The feature is about permission scope, so the cases check both halves of the contract: the
 * default is the narrow path, and the choice is held per hooked application because a permission
 * granted to one package says nothing about the other.
 */
class MediaSourcePolicyTest {
    private val wa = TargetApp.WHATSAPP
    private val business = TargetApp.WHATSAPP_BUSINESS

    private lateinit var keyValue: InMemoryKeyValueStore
    private lateinit var store: MediaSourcePolicyStore

    @Before
    fun setUp() {
        keyValue = InMemoryKeyValueStore()
        store = MediaSourcePolicyStore(keyValue)
    }

    @Test
    fun thePickerIsTheDefault() {
        assertEquals(MediaSourceMode.ANDROID_PHOTO_PICKER, store.modeFor(wa))
        val decision = store.decide(wa)
        assertTrue(decision.launchPickerDirectly)
        assertFalse(decision.mode.needsBroadMediaAccess)
    }

    @Test
    fun theGalleryIsAnExplicitChoiceAndKeepsItsPermissionCost() {
        store.setMode(wa, MediaSourceMode.WHATSAPP_GALLERY)
        val decision = store.decide(wa)
        assertEquals(MediaSourceMode.WHATSAPP_GALLERY, decision.mode)
        assertFalse(decision.launchPickerDirectly)
        assertTrue(decision.mode.needsBroadMediaAccess)
        assertTrue(decision.explanation.contains("media library"))
    }

    @Test
    fun askingEveryTimeDoesNotLaunchThePickerDirectly() {
        store.setMode(business, MediaSourceMode.ASK_EVERY_TIME)
        assertFalse(store.decide(business).launchPickerDirectly)
        assertEquals(MediaSourceMode.ASK_EVERY_TIME, store.modeFor(business))
    }

    @Test
    fun theTwoTargetsAreIndependent() {
        store.setMode(wa, MediaSourceMode.WHATSAPP_GALLERY)
        assertEquals(MediaSourceMode.WHATSAPP_GALLERY, store.modeFor(wa))
        assertEquals(MediaSourceMode.ANDROID_PHOTO_PICKER, store.modeFor(business))
        assertEquals(listOf(wa), store.configuredTargets())
    }

    @Test
    fun theDefaultIsNotStored() {
        store.setMode(wa, MediaSourceMode.WHATSAPP_GALLERY)
        store.setMode(wa, MediaSourcePolicyStore.DEFAULT)
        assertTrue(store.configuredTargets().isEmpty())
        assertNull(keyValue.getString(MediaSourcePolicyStore.KEY_PREFIX + wa.code))
    }

    @Test
    fun anUnreadableValueFallsBackToTheDefault() {
        keyValue.putString(MediaSourcePolicyStore.KEY_PREFIX + wa.code, "MEMBER_THAT_DOES_NOT_EXIST")
        assertEquals(MediaSourcePolicyStore.DEFAULT, store.modeFor(wa))
    }

    @Test
    fun clearingRestoresEveryDefault() {
        store.setMode(wa, MediaSourceMode.WHATSAPP_GALLERY)
        store.setMode(business, MediaSourceMode.ASK_EVERY_TIME)
        store.clear()
        assertEquals(MediaSourceMode.ANDROID_PHOTO_PICKER, store.modeFor(wa))
        assertEquals(MediaSourceMode.ANDROID_PHOTO_PICKER, store.modeFor(business))
        assertTrue(store.configuredTargets().isEmpty())
    }

    @Test
    fun everyModeExplainsItsPermissionConsequence() {
        MediaSourceMode.entries.forEach { mode ->
            store.setMode(wa, mode)
            val decision = store.decide(wa)
            assertEquals(mode, decision.mode)
            assertTrue("${mode.name} needs an explanation", decision.explanation.isNotBlank())
            assertTrue("${mode.name} needs a diagnostics line", decision.toDisplayLine().isNotBlank())
        }
    }
}
