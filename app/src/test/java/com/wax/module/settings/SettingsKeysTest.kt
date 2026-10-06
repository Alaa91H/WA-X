package com.wax.module.settings

import com.wax.module.platform.TargetApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The store contract that the hooked process and the interface both depend on.
 *
 * Run against a fake preference file rather than Android's, because the two properties
 * that matter here are namespace layout and the Global value staying readable at its
 * original key. Both are observable without a device, and both are the kind of thing that
 * breaks silently on a real one.
 */
class SettingsKeysTest {
    private val wa = SettingsScope.Target(TargetApp.WHATSAPP)
    private val business = SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS)

    @Test
    fun `global keeps its original key`() {
        assertEquals("showonline", SettingsKeys.physicalKey(SettingsScope.Global, "showonline"))
    }

    @Test
    fun `a target override is namespaced by target`() {
        assertEquals("waxtarget.whatsapp.showonline", SettingsKeys.physicalKey(wa, "showonline"))
        assertEquals("waxtarget.business.showonline", SettingsKeys.physicalKey(business, "showonline"))
    }

    @Test
    fun `two targets never produce the same physical key`() {
        assertTrue(SettingsKeys.physicalKey(wa, "k") != SettingsKeys.physicalKey(business, "k"))
    }

    @Test
    fun `an override key round-trips back to its setting key`() {
        assertEquals("showonline", SettingsKeys.overrideKey("waxtarget.whatsapp.showonline"))
    }

    @Test
    fun `an override key resolves to its target`() {
        assertEquals(TargetApp.WHATSAPP, SettingsKeys.overrideTarget("waxtarget.whatsapp.showonline"))
        assertEquals(TargetApp.WHATSAPP_BUSINESS, SettingsKeys.overrideTarget("waxtarget.business.showonline"))
    }

    @Test
    fun `a global key is not an override`() {
        assertNull(SettingsKeys.overrideKey("showonline"))
        assertNull(SettingsKeys.overrideTarget("showonline"))
        assertFalse(SettingsKeys.isOverrideKey("showonline"))
        assertTrue(SettingsKeys.isOverrideKey("waxtarget.whatsapp.showonline"))
    }

    @Test
    fun `a prefix without a setting key is rejected`() {
        assertNull(SettingsKeys.overrideKey("waxtarget.whatsapp"))
        assertNull(SettingsKeys.overrideTarget("waxtarget"))
    }

    @Test
    fun `an unknown target code resolves to no target`() {
        assertNull(SettingsKeys.overrideTarget("waxtarget.nosuchtarget.k"))
    }

    @Test
    fun `a dotted setting key keeps everything after the target`() {
        val physical = SettingsKeys.physicalKey(wa, "com.example.key")
        assertEquals("com.example.key", SettingsKeys.overrideKey(physical))
    }
}

/** A minimal in-memory stand-in for the platform's preference file. */
internal class FakePreferences(
    initial: Map<String, Any> = emptyMap(),
) : android.content.SharedPreferences {
    private val values = LinkedHashMap<String, Any?>(initial)

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String?>?,
    ): MutableSet<String?>? = (values[key] as? Set<String>)?.toMutableSet() as? MutableSet<String?> ?: defValues

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = (values[key] as? Number)?.toLong() ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = (values[key] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): android.content.SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) =
        Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    /** Applies a batch, mirroring the editor's all-or-nothing commit. */
    fun put(
        key: String,
        value: Any?,
    ) {
        values[key] = value
    }

    fun snapshot(): Map<String, Any?> = LinkedHashMap(values)

    private inner class FakeEditor : android.content.SharedPreferences.Editor {
        private val staged = LinkedHashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var cleared = false

        override fun putString(
            key: String?,
            value: String?,
        ): android.content.SharedPreferences.Editor = apply { staged[key!!] = value }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String?>?,
        ): android.content.SharedPreferences.Editor = apply { staged[key!!] = values?.toSet() }

        override fun putInt(
            key: String?,
            value: Int,
        ): android.content.SharedPreferences.Editor = apply { staged[key!!] = value }

        override fun putLong(
            key: String?,
            value: Long,
        ): android.content.SharedPreferences.Editor = apply { staged[key!!] = value }

        override fun putFloat(
            key: String?,
            value: Float,
        ): android.content.SharedPreferences.Editor = apply { staged[key!!] = value }

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): android.content.SharedPreferences.Editor = apply { staged[key!!] = value }

        override fun remove(key: String?): android.content.SharedPreferences.Editor = apply { removed.add(key!!) }

        override fun clear(): android.content.SharedPreferences.Editor = apply { cleared = true }

        override fun commit(): Boolean {
            flush()
            return true
        }

        override fun apply() = flush()

        private fun flush() {
            if (cleared) values.clear()
            removed.forEach { values.remove(it) }
            staged.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }
}

/** Store-level behaviour against the fake file. */
class SharedPreferencesSettingsStoreTest {
    private lateinit var prefs: FakePreferences
    private lateinit var store: SharedPreferencesSettingsStore

    private val global = SettingsScope.Global
    private val wa = SettingsScope.Target(TargetApp.WHATSAPP)
    private val business = SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS)

    @Before
    fun setUp() {
        prefs = FakePreferences()
        store = SharedPreferencesSettingsStore(prefs)
    }

    @Test
    fun `a global value is written to its original key`() {
        store.writeBoolean(global, "antirevoke", true)
        assertEquals(true, prefs.snapshot()["antirevoke"])
    }

    @Test
    fun `a global value written by an older build is read back`() {
        prefs.put("legacykey", true)
        store.reload()
        assertEquals(true, store.readBoolean(global, "legacykey"))
    }

    @Test
    fun `an override is written under the target namespace`() {
        store.writeBoolean(wa, "antirevoke", false)
        assertEquals(false, prefs.snapshot()["waxtarget.whatsapp.antirevoke"])
        assertNull(prefs.snapshot()["antirevoke"])
    }

    @Test
    fun `an override on one target is invisible to the other`() {
        store.writeBoolean(global, "k", true)
        store.writeBoolean(wa, "k", false)
        assertFalse(store.readBoolean(wa, "k")!!)
        assertNull(store.readBoolean(business, "k"))
        assertTrue(store.readBoolean(global, "k")!!)
    }

    @Test
    fun `clearing an override removes only that key`() {
        store.writeBoolean(wa, "a", true)
        store.writeBoolean(wa, "b", true)
        store.writeBoolean(global, "a", true)
        store.writeBoolean(wa, "a", null)
        assertFalse(prefs.snapshot().containsKey("waxtarget.whatsapp.a"))
        assertTrue(prefs.snapshot().containsKey("waxtarget.whatsapp.b"))
        assertTrue(prefs.snapshot().containsKey("a"))
    }

    @Test
    fun `clearing a whole scope leaves the other scope alone`() {
        store.writeBoolean(wa, "k", true)
        store.writeBoolean(business, "k", true)
        store.writeBoolean(global, "k", true)
        store.clearScope(wa)
        assertTrue(store.keysWithOverrides(wa).isEmpty())
        assertEquals(setOf("k"), store.keysWithOverrides(business))
        assertEquals(setOf("k"), store.keysWithOverrides(global))
    }

    @Test
    fun `copying a scope replaces the destination entirely`() {
        store.writeBoolean(wa, "old", true)
        store.copyScope(global, wa)
        assertNull(store.readBoolean(wa, "old"))
    }

    @Test
    fun `a string set round-trips`() {
        store.writeStringSet(wa, "set", setOf("b", "a"))
        assertEquals(setOf("a", "b"), store.readStringSet(wa, "set"))
    }

    @Test
    fun `a value set as a string is not read as a set`() {
        prefs.put("k", "notaset")
        store.reload()
        assertNull(store.readStringSet(global, "k"))
    }

    @Test
    fun `replaceAll is all or nothing and reloads`() {
        store.writeBoolean(global, "old", true)
        store.replaceAll(
            global = mapOf("a" to "1"),
            targets = mapOf(TargetApp.WHATSAPP_BUSINESS to mapOf("b" to "2")),
        )
        assertNull(store.readBoolean(global, "old"))
        assertEquals("1", store.readString(global, "a"))
        assertEquals("2", store.readString(SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS), "b"))
    }

    @Test
    fun `a corrupt integer reads as absent rather than zero`() {
        prefs.put("k", "notanumber")
        store.reload()
        assertNull(store.readInt(global, "k"))
    }
}
