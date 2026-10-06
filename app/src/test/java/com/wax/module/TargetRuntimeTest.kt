package com.wax.module

import com.wax.module.platform.TargetApp
import com.wax.module.platform.TargetPackageRegistry
import com.wax.module.settings.InMemorySettingsStore
import com.wax.module.settings.SettingsScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Routing tests for the single-APK model.
 *
 * The failure this guards against is specific: one APK hooks two WhatsApp builds, so
 * the only thing keeping their settings apart is deciding, per process, which target
 * the code is running inside. Every assertion below is about that decision.
 */
class TargetRuntimeTest {
    private lateinit var store: InMemorySettingsStore

    @Before
    fun setUp() {
        store = InMemorySettingsStore()
        TargetRuntime.logger = {}
        TargetRuntime.detach()
    }

    @After
    fun tearDown() {
        TargetRuntime.detach()
        TargetRuntime.logger = {}
    }

    private fun attachTo(packageName: String) {
        val target = TargetPackageRegistry.targetOf(packageName)
        if (target != null) {
            TargetRuntime.attach(target, store, defaults = mapOf("showonline" to false))
        }
    }

    // --- the process decides the target -------------------------------------------

    @Test
    fun `the whatsapp process resolves to the whatsapp target`() {
        attachTo("com.whatsapp")
        assertEquals(TargetApp.WHATSAPP, TargetRuntime.target)
    }

    @Test
    fun `the business process resolves to the business target`() {
        attachTo("com.whatsapp.w4b")
        assertEquals(TargetApp.WHATSAPP_BUSINESS, TargetRuntime.target)
    }

    @Test
    fun `one apk serves both target packages`() {
        // The whole point of dropping the flavors: the same code hooks both.
        assertEquals(
            setOf("com.whatsapp", "com.whatsapp.w4b"),
            TargetPackageRegistry.packageNames,
        )
    }

    @Test
    fun `an unrelated package is not a target`() {
        assertNull(TargetPackageRegistry.targetOf("com.android.systemui"))
        assertFalse(TargetPackageRegistry.isTarget("com.android.systemui"))
    }

    @Test
    fun `an unknown package does not resolve to a target`() {
        assertNull(TargetPackageRegistry.targetOf(null))
        assertNull(TargetPackageRegistry.targetOf(""))
    }

    // --- the runtime stays silent before it is attached ---------------------------

    @Test
    fun `no target means no snapshot rather than global defaults`() {
        assertFalse(TargetRuntime.isTargetProcess())
        assertNull(TargetRuntime.scope)
        assertNull(TargetRuntime.snapshot())
    }

    @Test
    fun `attaching exposes the target scope and a snapshot`() {
        attachTo("com.whatsapp")
        assertTrue(TargetRuntime.isTargetProcess())
        assertEquals(SettingsScope.Target(TargetApp.WHATSAPP), TargetRuntime.scope)
        assertNotNull(TargetRuntime.snapshot())
    }

    // --- one process never reads the other target's overrides ---------------------

    @Test
    fun `the whatsapp process reads only the whatsapp override`() {
        store.writeBoolean(SettingsScope.Global, "showonline", true)
        store.writeBoolean(SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS), "showonline", false)

        attachTo("com.whatsapp")

        assertTrue(TargetRuntime.snapshot()!!.boolean("showonline"))
    }

    @Test
    fun `the business process reads only the business override`() {
        store.writeBoolean(SettingsScope.Global, "showonline", true)
        store.writeBoolean(SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS), "showonline", false)

        attachTo("com.whatsapp.w4b")

        assertFalse(TargetRuntime.snapshot()!!.boolean("showonline"))
    }

    @Test
    fun `re-attaching to the other target switches the whole view`() {
        store.writeBoolean(SettingsScope.Global, "showonline", true)
        store.writeBoolean(SettingsScope.Target(TargetApp.WHATSAPP), "showonline", false)
        store.writeBoolean(SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS), "showonline", true)

        attachTo("com.whatsapp")
        assertFalse(TargetRuntime.snapshot()!!.boolean("showonline"))

        // Stands in for the second process starting; the object is per process, so the
        // second attach must not be able to reuse the first one's cached snapshot.
        TargetRuntime.detach()
        attachTo("com.whatsapp.w4b")
        assertTrue(TargetRuntime.snapshot()!!.boolean("showonline"))
    }

    @Test
    fun `an unset override falls back to global in the hooked process`() {
        store.writeBoolean(SettingsScope.Global, "showonline", true)

        attachTo("com.whatsapp.w4b")

        assertTrue(TargetRuntime.snapshot()!!.boolean("showonline"))
    }

    @Test
    fun `defaults apply when neither global nor override is set`() {
        attachTo("com.whatsapp")

        assertFalse(TargetRuntime.snapshot()!!.boolean("showonline"))
    }

    @Test
    fun `reload drops the cached snapshot`() {
        store.writeBoolean(SettingsScope.Global, "showonline", true)
        attachTo("com.whatsapp")
        assertTrue(TargetRuntime.snapshot()!!.boolean("showonline"))

        store.writeBoolean(SettingsScope.Global, "showonline", false)
        TargetRuntime.reload()

        assertFalse(TargetRuntime.snapshot()!!.boolean("showonline"))
    }

    // --- hook scope ----------------------------------------------------------------

    @Test
    fun `the bridge infrastructure stays inside the hook scope`() {
        // android is required for the package visibility bypass and the settings
        // bridge; it is infrastructure, not a target.
        assertTrue(TargetPackageRegistry.isInHookScope("android"))
        assertTrue(TargetPackageRegistry.isInHookScope("com.android.providers.settings"))
        assertFalse(TargetPackageRegistry.isInHookScope("com.android.systemui"))
        assertFalse(TargetPackageRegistry.isInHookScope(null))
    }
}
