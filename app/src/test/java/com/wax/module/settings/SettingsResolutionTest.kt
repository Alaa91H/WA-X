package com.wax.module.settings

import com.wax.module.platform.TargetApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The isolation contract for one APK with two independently configured targets.
 *
 * Every case named in the migration plan is asserted literally, plus the ones that
 * are easy to get subtly wrong: a Global change must reach inheriting targets and
 * must not reach targets that diverged, and resetting one scope must leave the other
 * and Global untouched.
 */
class SettingsResolutionTest {
    private lateinit var store: InMemorySettingsStore
    private lateinit var resolver: EffectiveSettingsResolver

    private val global = SettingsScope.Global
    private val wa = SettingsScope.Target(TargetApp.WHATSAPP)
    private val business = SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS)

    @Before
    fun setUp() {
        store = InMemorySettingsStore()
        resolver = EffectiveSettingsResolver(store, defaults = mapOf("showonline" to false))
    }

    // --- the four required cases ----------------------------------------------------

    @Test
    fun `global on and whatsapp inherits resolves to on`() {
        store.writeBoolean(global, "showonline", true)
        assertTrue(resolver.effectiveBoolean("showonline", wa))
    }

    @Test
    fun `global on and whatsapp disabled resolves to off`() {
        store.writeBoolean(global, "showonline", true)
        store.writeBoolean(wa, "showonline", false)
        assertFalse(resolver.effectiveBoolean("showonline", wa))
    }

    @Test
    fun `global off and business inherits resolves to off`() {
        store.writeBoolean(global, "showonline", false)
        assertFalse(resolver.effectiveBoolean("showonline", business))
    }

    @Test
    fun `global off and business enabled resolves to on`() {
        store.writeBoolean(global, "showonline", false)
        store.writeBoolean(business, "showonline", true)
        assertTrue(resolver.effectiveBoolean("showonline", business))
    }

    // --- one target never touches the other -----------------------------------------

    @Test
    fun `a whatsapp override never changes business`() {
        store.writeBoolean(global, "showonline", true)
        store.writeBoolean(wa, "showonline", false)

        assertFalse(resolver.effectiveBoolean("showonline", wa))
        assertTrue(resolver.effectiveBoolean("showonline", business))
        assertNull(store.readBoolean(business, "showonline"))
    }

    @Test
    fun `a business override never changes whatsapp`() {
        store.writeBoolean(global, "showonline", true)
        store.writeBoolean(business, "showonline", false)

        assertFalse(resolver.effectiveBoolean("showonline", business))
        assertTrue(resolver.effectiveBoolean("showonline", wa))
        assertNull(store.readBoolean(wa, "showonline"))
    }

    // --- Global propagation ---------------------------------------------------------

    @Test
    fun `a global change reaches targets that inherit`() {
        assertFalse(resolver.effectiveBoolean("showonline", wa))
        store.writeBoolean(global, "showonline", true)
        assertTrue(resolver.effectiveBoolean("showonline", wa))
        assertTrue(resolver.effectiveBoolean("showonline", business))
    }

    @Test
    fun `a global change does not overwrite an explicit target override`() {
        store.writeBoolean(wa, "showonline", true)
        store.writeBoolean(global, "showonline", true)
        // WhatsApp overrode to true before Global was true; it must stay true rather
        // than being flipped to false by a Global change it is meant to ignore.
        assertTrue(resolver.effectiveBoolean("showonline", wa))

        store.writeBoolean(wa, "showonline", false)
        store.writeBoolean(global, "showonline", false)
        assertFalse(resolver.effectiveBoolean("showonline", wa))
        assertFalse(resolver.effectiveBoolean("showonline", business))
    }

    // --- resets ---------------------------------------------------------------------

    @Test
    fun `resetting whatsapp leaves business untouched`() {
        store.writeBoolean(wa, "showonline", false)
        store.writeBoolean(business, "showonline", true)
        store.writeBoolean(global, "showonline", true)

        resolver.resetScope(wa)

        assertEquals(TriState.INHERIT, resolver.triState("showonline", wa))
        assertEquals(TriState.ENABLED, resolver.triState("showonline", business))
        assertTrue(resolver.effectiveBoolean("showonline", wa))
    }

    @Test
    fun `resetting business leaves whatsapp untouched`() {
        store.writeBoolean(wa, "showonline", false)
        store.writeBoolean(business, "showonline", false)
        store.writeBoolean(global, "showonline", true)

        resolver.resetScope(business)

        assertEquals(TriState.DISABLED, resolver.triState("showonline", wa))
        assertEquals(TriState.INHERIT, resolver.triState("showonline", business))
    }

    @Test
    fun `resetting a target leaves global untouched`() {
        store.writeString(global, "status_style", "3")
        store.writeString(wa, "status_style", "1")

        resolver.resetScope(wa)

        assertEquals("3", resolver.effectiveString("status_style", global))
        assertEquals("3", resolver.effectiveString("status_style", wa))
    }

    @Test
    fun `resetting one key leaves the target's other overrides alone`() {
        store.writeBoolean(wa, "showonline", false)
        store.writeBoolean(wa, "typerecording", false)

        resolver.resetKey("showonline", wa)

        assertEquals(TriState.INHERIT, resolver.triState("showonline", wa))
        assertEquals(TriState.DISABLED, resolver.triState("typerecording", wa))
    }

    // --- tri-state -------------------------------------------------------------------

    @Test
    fun `an override equal to global stays an explicit override`() {
        store.writeBoolean(global, "showonline", true)
        store.writeBoolean(wa, "showonline", true)

        assertEquals(TriState.ENABLED, resolver.triState("showonline", wa))
        assertTrue(resolver.isOverridden("showonline", wa))

        // The user chose Enabled for WhatsApp. Turning Global off must not silently
        // turn WhatsApp off with it.
        store.writeBoolean(global, "showonline", false)
        assertTrue(resolver.effectiveBoolean("showonline", wa))
        assertFalse(resolver.effectiveBoolean("showonline", business))
    }

    @Test
    fun `writing a tri-state clears the override when inheriting`() {
        store.writeBoolean(global, "showonline", true)
        resolver.setTriState("showonline", wa, TriState.DISABLED)
        assertFalse(resolver.effectiveBoolean("showonline", wa))

        resolver.setTriState("showonline", wa, TriState.INHERIT)
        assertTrue(resolver.effectiveBoolean("showonline", wa))
        assertNull(store.readBoolean(wa, "showonline"))
    }

    @Test
    fun `global has no third state and always reports its own value`() {
        store.writeBoolean(global, "showonline", true)
        assertEquals(TriState.ENABLED, resolver.triState("showonline", global))

        store.writeBoolean(global, "showonline", false)
        assertEquals(TriState.DISABLED, resolver.triState("showonline", global))
    }

    // --- generic values --------------------------------------------------------------

    @Test
    fun `non-boolean values resolve and isolate the same way`() {
        store.writeString(global, "status_style", "2")
        store.writeString(wa, "status_style", "5")
        store.writeInt(business, "voicenote_speed", 3)

        assertEquals("2", resolver.effectiveString("status_style", business))
        assertEquals("5", resolver.effectiveString("status_style", wa))
        assertEquals(3, resolver.effectiveInt("voicenote_speed", business))
        assertEquals(0, resolver.effectiveInt("voicenote_speed", wa))
    }

    @Test
    fun `a generic override resolves against the global value`() {
        val globalValue = "2"
        assertEquals("2", OverrideValue.resolve<String>(OverrideValue.Inherit, globalValue))
        assertEquals("9", OverrideValue.resolve(OverrideValue.Value("9"), globalValue))
        assertEquals("7", OverrideValue.resolve(OverrideValue.Value("7"), globalValue))
    }

    // --- defaults and corrupt values -------------------------------------------------

    @Test
    fun `the default fallback is deterministic and safe`() {
        assertFalse(resolver.effectiveBoolean("never_configured", wa))
        assertFalse(resolver.effectiveBoolean("never_configured", global))
        assertNull(resolver.effectiveString("never_configured", wa))
        assertEquals(5, resolver.effectiveInt("never_configured", wa, 5))
    }

    @Test
    fun `a corrupt value falls back instead of throwing`() {
        store.writeString(global, "status_style", "not-a-number")
        assertEquals(0, resolver.effectiveInt("status_style", wa))
        assertEquals("not-a-number", resolver.effectiveString("status_style", wa))
    }

    @Test
    fun `a corrupt boolean falls back to global rather than to false`() {
        store.writeString(global, "showonline", "perhaps")
        store.writeBoolean(global, "showonline", true)
        assertTrue(resolver.effectiveBoolean("showonline", wa))
    }

    // --- copy and freeze -------------------------------------------------------------

    @Test
    fun `copying whatsapp overrides onto business replaces what business had`() {
        store.writeBoolean(wa, "showonline", false)
        store.writeString(wa, "status_style", "1")
        store.writeString(business, "status_style", "7")

        resolver.copyOverrides(wa, business)

        assertFalse(resolver.effectiveBoolean("showonline", business))
        assertEquals("1", resolver.effectiveString("status_style", business))
        // WhatsApp must be unchanged by the copy.
        assertFalse(resolver.effectiveBoolean("showonline", wa))
        assertEquals("1", resolver.effectiveString("status_style", wa))
    }

    @Test
    fun `freezing global into a target stops it following later global changes`() {
        store.writeBoolean(global, "showonline", true)
        resolver.freezeGlobalInto(wa)

        assertEquals(TriState.ENABLED, resolver.triState("showonline", wa))

        store.writeBoolean(global, "showonline", false)

        // WhatsApp was pinned, so it keeps the value it was frozen with.
        assertTrue(resolver.effectiveBoolean("showonline", wa))
        // Business was never frozen, so it follows.
        assertFalse(resolver.effectiveBoolean("showonline", business))
    }

    // --- snapshot caching ------------------------------------------------------------

    @Test
    fun `snapshots are cached per target and never shared`() {
        val cache = SettingsSnapshotCache(resolver)
        store.writeBoolean(wa, "showonline", true)
        store.writeBoolean(business, "showonline", false)

        val waSnapshot = cache.snapshotForPackage("com.whatsapp")!!
        val businessSnapshot = cache.snapshotForPackage("com.whatsapp.w4b")!!

        assertTrue(waSnapshot.boolean("showonline"))
        assertFalse(businessSnapshot.boolean("showonline"))
        assertSame(TargetApp.WHATSAPP, waSnapshot.target)
        assertSame(TargetApp.WHATSAPP_BUSINESS, businessSnapshot.target)
        assertSame(waSnapshot, cache.snapshotForPackage("com.whatsapp"))
    }

    @Test
    fun `a non-target process gets no snapshot at all`() {
        val cache = SettingsSnapshotCache(resolver)
        assertNull(cache.snapshotForPackage("com.openai.chatgpt"))
        assertNull(cache.snapshotForPackage("com.wax.module"))
        assertNull(cache.snapshotForPackage(null))
    }

    @Test
    fun `invalidating one target leaves the other snapshot intact`() {
        val cache = SettingsSnapshotCache(resolver)
        val waSnapshot = cache.snapshotFor(wa)
        val businessSnapshot = cache.snapshotFor(business)

        cache.invalidate(wa)

        assertFalse(waSnapshot === cache.snapshotFor(wa))
        assertSame(businessSnapshot, cache.snapshotFor(business))
    }

    private fun assertSame(
        expected: Any?,
        actual: Any?,
    ) = org.junit.Assert.assertSame(expected, actual)
}
