package com.wax.module.settings

import com.wax.module.platform.TargetApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The read path inside a hooked WhatsApp process.
 *
 * This is the class every feature's preference read passes through, so a mistake here is
 * not a bug in one setting: it is every setting, in both builds, at once. The tests are
 * written around the two properties that make that safe: a target only ever sees its own
 * overrides, and a key that is not ours is never touched.
 */
class TargetScopedPreferencesTest {
    private lateinit var prefs: FakePreferences

    private val wa = TargetApp.WHATSAPP
    private val business = TargetApp.WHATSAPP_BUSINESS

    @Before
    fun setUp() {
        // A file as an earlier release would have left it: globals at their raw keys.
        prefs = FakePreferences()
        val store = SharedPreferencesSettingsStore(prefs)
        val global = SettingsScope.Global
        store.writeBoolean(global, "showonline", true)
        store.writeString(global, "thememode", "dark")
        store.writeString(global, "whatsapp_only_unrelated", "value")
    }

    private fun scoped(target: TargetApp): TargetScopedPreferences {
        val store = SharedPreferencesSettingsStore(prefs)
        val wrapped = TargetScopedPreferences.wrap(prefs, target) as TargetScopedPreferences
        wrapped.refresh(store)
        return wrapped
    }

    // --- inheritance ------------------------------------------------------------

    @Test
    fun `with no override the global value is returned`() {
        assertTrue(scoped(wa).getBoolean("showonline", false))
    }

    @Test
    fun `an override wins over global`() {
        SharedPreferencesSettingsStore(prefs).writeBoolean(SettingsScope.Target(wa), "showonline", false)
        assertFalse(scoped(wa).getBoolean("showonline", true))
    }

    @Test
    fun `a list preference override stays text`() {
        val store = SharedPreferencesSettingsStore(prefs)
        store.writeString(SettingsScope.Global, "antirevoke", "0")
        store.writeString(SettingsScope.Target(wa), "antirevoke", "2")

        assertEquals("2", scoped(wa).getString("antirevoke", "0"))
        assertEquals("0", scoped(business).getString("antirevoke", "0"))
    }

    @Test
    fun `a text override wins over global`() {
        SharedPreferencesSettingsStore(prefs).writeString(SettingsScope.Target(wa), "thememode", "light")
        assertEquals("light", scoped(wa).getString("thememode", "dark"))
    }

    @Test
    fun `removing the override makes the target follow global again`() {
        val store = SharedPreferencesSettingsStore(prefs)
        store.writeBoolean(SettingsScope.Target(wa), "showonline", false)
        assertFalse(scoped(wa).getBoolean("showonline", true))

        store.writeBoolean(SettingsScope.Target(wa), "showonline", null)
        assertTrue(scoped(wa).getBoolean("showonline", false))
    }

    // --- isolation between the two processes -------------------------------------

    @Test
    fun `a whatsapp override never reaches the business process`() {
        SharedPreferencesSettingsStore(prefs).writeBoolean(SettingsScope.Target(wa), "showonline", false)
        assertTrue(scoped(business).getBoolean("showonline", false))
    }

    @Test
    fun `a business override never reaches the whatsapp process`() {
        SharedPreferencesSettingsStore(prefs).writeBoolean(SettingsScope.Target(business), "showonline", false)
        assertTrue(scoped(wa).getBoolean("showonline", false))
    }

    @Test
    fun `each process sees its own override`() {
        val store = SharedPreferencesSettingsStore(prefs)
        store.writeBoolean(SettingsScope.Target(wa), "showonline", false)
        store.writeBoolean(SettingsScope.Target(business), "showonline", true)

        assertFalse(scoped(wa).getBoolean("showonline", true))
        assertTrue(scoped(business).getBoolean("showonline", false))
    }

    // --- keys that are not ours --------------------------------------------------

    @Test
    fun `an unknown key reads the caller default`() {
        assertNull(scoped(wa).getString("no_such_key", null))
        assertFalse(scoped(wa).getBoolean("no_such_key", false))
    }

    @Test
    fun `an unknown key does not become present`() {
        assertFalse(scoped(wa).contains("no_such_key"))
    }

    @Test
    fun `a whatsapp key that looks like ours is still WhatsApp's to read`() {
        // Same physical prefix, different target: the whatsapp process must not resolve it.
        prefs.put("waxtarget.business.something", "business-value")
        assertNull(scoped(wa).getString("something", null))
    }

    @Test
    fun `a global key is never treated as an override`() {
        assertNull(scoped(wa).overrideForHook("showonline", "original").let { if (it == "original") null else it })
    }

    // --- types -------------------------------------------------------------------

    @Test
    fun `an int override is returned as an int`() {
        val store = SharedPreferencesSettingsStore(prefs)
        prefs.put("floating_bottom_bar_radius", 5)
        store.reload()
        store.writeInt(SettingsScope.Target(wa), "floating_bottom_bar_radius", 9)
        assertEquals(9, scoped(wa).getInt("floating_bottom_bar_radius", 5))
    }

    @Test
    fun `a set override is returned as a set`() {
        val store = SharedPreferencesSettingsStore(prefs)
        store.writeStringSet(SettingsScope.Target(wa), "hidetabs", setOf("1", "2"))
        assertEquals(setOf("1", "2"), scoped(wa).getStringSet("hidetabs", null)?.toSet())
    }

    @Test
    fun `a set is not handed to a boolean read`() {
        val store = SharedPreferencesSettingsStore(prefs)
        store.writeStringSet(SettingsScope.Target(wa), "hidetabs", setOf("1"))
        // Falls through to the caller's own default rather than coercing.
        assertFalse(scoped(wa).getBoolean("hidetabs", false))
    }

    @Test
    fun `getAll reports the merged view`() {
        SharedPreferencesSettingsStore(prefs).writeBoolean(SettingsScope.Target(wa), "showonline", false)
        val all = scoped(wa).getAll()!!
        assertEquals(false, all["showonline"])
        assertEquals("dark", all["thememode"])
    }

    @Test
    fun `getAll leaves the other target's override out`() {
        SharedPreferencesSettingsStore(prefs).writeBoolean(SettingsScope.Target(business), "showonline", false)
        val all = scoped(wa).getAll()!!
        assertEquals(true, all["showonline"])
        assertFalse(all.containsKey("waxtarget.business.showonline"))
    }

    @Test
    fun `unknown target keys are never exposed as WA X overrides`() {
        SharedPreferencesSettingsStore(prefs).writeBoolean(SettingsScope.Target(wa), "only_here", true)

        val target = scoped(wa)

        assertFalse(target.contains("only_here"))
        assertFalse(target.getBoolean("only_here", false))
        assertEquals("original", target.overrideForHook("only_here", "original"))
    }

    @Test
    fun `legacy string boolean override is recovered as a boolean`() {
        prefs.put("waxtarget.whatsapp.showonline", "true")

        val target = scoped(wa)

        assertTrue(target.getBoolean("showonline", false))
        assertEquals(true, target.overrideForHook("showonline", false))
    }

    @Test
    fun `legacy string set override is recovered as a set`() {
        prefs.put("waxtarget.whatsapp.hidetabs", "1\u00012")

        val target = scoped(wa)

        assertEquals(setOf("1", "2"), target.getStringSet("hidetabs", null)?.toSet())
    }

    @Test
    fun `excluded module-only keys cannot become target runtime overrides`() {
        prefs.put("waxtarget.whatsapp.thememode", "light")

        val target = scoped(wa)

        assertEquals("dark", target.getString("thememode", "dark"))
        assertEquals("current", target.overrideForHook("thememode", "current"))
    }

    // --- writes ------------------------------------------------------------------

    @Test
    fun `a write from a hooked process lands in global and not in the target`() {
        scoped(wa).edit().putBoolean("runtime_flag", true).apply()
        assertEquals(true, prefs.snapshot()["runtime_flag"])
        assertNull(prefs.snapshot()["waxtarget.whatsapp.runtime_flag"])
    }

    @Test
    fun `a hooked process cannot write a target key directly`() {
        scoped(wa).edit().putString("waxtarget.business.sneaky", "x").apply()
        // Refused outright rather than forwarded: a feature inside the WhatsApp process
        // must not be able to reconfigure the other build.
        assertNull(prefs.snapshot()["waxtarget.business.sneaky"])
        assertNull(scoped(business).getString("sneaky", null))
    }

    // --- scope copies ---------------------------------------------------------------

    @Test
    fun `copying global into a target preserves stored value types`() {
        val store = SharedPreferencesSettingsStore(prefs)
        val global = SettingsScope.Global
        val target = SettingsScope.Target(wa)

        store.writeBoolean(global, "showonline", true)
        store.writeInt(global, "floating_bottom_bar_radius", 7)
        store.writeFloat(global, "voicenote_speed", 1.5f)
        store.writeString(global, "status_style", "2")
        store.writeStringSet(global, "hidetabs", setOf("1", "2"))

        store.copyScope(global, target)

        val scoped = scoped(wa)
        assertTrue(scoped.getBoolean("showonline", false))
        assertEquals(7, scoped.getInt("floating_bottom_bar_radius", 0))
        assertEquals(1.5f, scoped.getFloat("voicenote_speed", 0f))
        assertEquals("2", scoped.getString("status_style", null))
        assertEquals(setOf("1", "2"), scoped.getStringSet("hidetabs", null)?.toSet())
    }

    @Test
    fun `copying global never nests another target namespace`() {
        val store = SharedPreferencesSettingsStore(prefs)
        store.writeBoolean(SettingsScope.Global, "global_flag", true)
        store.writeBoolean(SettingsScope.Target(business), "business_only", true)

        store.copyScope(SettingsScope.Global, SettingsScope.Target(wa))

        val snapshot = prefs.snapshot()
        assertEquals(true, snapshot["waxtarget.whatsapp.global_flag"])
        assertNull(snapshot["waxtarget.whatsapp.waxtarget.business.business_only"])
        assertNull(scoped(wa).getBoolean("business_only", false).takeIf { it })
    }

    @Test
    fun `clearing global leaves target overrides intact`() {
        val store = SharedPreferencesSettingsStore(prefs)
        store.writeBoolean(SettingsScope.Global, "filterseen", true)
        store.writeBoolean(SettingsScope.Target(wa), "showonline", true)

        store.clearScope(SettingsScope.Global)

        assertNull(store.readBoolean(SettingsScope.Global, "filterseen"))
        assertEquals(true, store.readBoolean(SettingsScope.Target(wa), "showonline"))
        assertTrue(scoped(wa).getBoolean("showonline", false))
    }

    // --- no target ----------------------------------------------------------------

    @Test
    fun `outside a target the delegate is returned untouched`() {
        assertTrue(TargetScopedPreferences.wrap(prefs, null) === prefs)
    }

    // --- reload --------------------------------------------------------------------

    @Test
    fun `refresh resets the override count after the last override is removed`() {
        val store = SharedPreferencesSettingsStore(prefs)
        store.writeBoolean(SettingsScope.Target(wa), "showonline", true)
        val target = scoped(wa)
        assertEquals(1, target.count)

        store.writeBoolean(SettingsScope.Target(wa), "showonline", null)
        target.refresh(SharedPreferencesSettingsStore(prefs))

        assertEquals(0, target.count)
    }

    @Test
    fun `a refresh picks up a change made by the interface`() {
        val target = scoped(wa)
        assertTrue(target.getBoolean("showonline", false))
        SharedPreferencesSettingsStore(prefs).writeBoolean(SettingsScope.Target(wa), "showonline", false)
        target.refresh(SharedPreferencesSettingsStore(prefs))
        assertFalse(target.getBoolean("showonline", true))
    }
}
