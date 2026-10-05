package com.wax.module.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is the answer to "which packages does WA X hook". If it disagrees
 * with itself, the hook entry point and the settings model can each be right about
 * a different set, which is how a hook ends up in the wrong application.
 */
class TargetPackageRegistryTest {
    @Test
    fun `the registry knows exactly the two WhatsApp targets`() {
        assertEquals(
            listOf("com.whatsapp", "com.whatsapp.w4b"),
            TargetPackageRegistry.targets.map { it.packageName },
        )
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), TargetPackageRegistry.packageNames)
    }

    @Test
    fun `a package name resolves to its target`() {
        assertSame(TargetApp.WHATSAPP, TargetPackageRegistry.targetOf("com.whatsapp"))
        assertSame(TargetApp.WHATSAPP_BUSINESS, TargetPackageRegistry.targetOf("com.whatsapp.w4b"))
    }

    @Test
    fun `a persisted code resolves to its target`() {
        assertSame(TargetApp.WHATSAPP, TargetApp.fromCode("whatsapp"))
        assertSame(TargetApp.WHATSAPP_BUSINESS, TargetApp.fromCode("business"))
        assertNull(TargetApp.fromCode("nonsense"))
        assertNull(TargetApp.fromCode(null))
    }

    @Test
    fun `the module's own packages are never a target`() {
        assertFalse(TargetPackageRegistry.isTarget("com.wax.module"))
        assertFalse(TargetPackageRegistry.isTarget("com.wax.module.w4b"))
    }

    @Test
    fun `unrelated applications are neither targets nor in hook scope`() {
        val unrelated =
            listOf(
                "com.openai.chatgpt",
                "com.aliexpress.mobile",
                "com.onedownload.manager",
                "com.authenticator.authenticator2",
                "com.google.android.gms",
                "com.android.chrome",
            )
        for (name in unrelated) {
            assertFalse(name, TargetPackageRegistry.isTarget(name))
            assertFalse(name, TargetPackageRegistry.isInHookScope(name))
            assertNull(name, TargetPackageRegistry.targetOf(name))
        }
    }

    @Test
    fun `an unresolved package name is rejected rather than guessed`() {
        assertFalse(TargetPackageRegistry.isTarget(null))
        assertFalse(TargetPackageRegistry.isInHookScope(null))
        assertNull(TargetPackageRegistry.targetOf(null))
    }

    @Test
    fun `package names match exactly, not by prefix`() {
        assertFalse(TargetPackageRegistry.isTarget("com.whatsapp.w4b.extra"))
        assertFalse(TargetPackageRegistry.isTarget("com.whatsappclone"))
        assertFalse(TargetPackageRegistry.isTarget("com.WHATSAPP"))
    }

    @Test
    fun `the hook scope adds the infrastructure processes to the targets`() {
        assertEquals(4, SupportedPackages.HOOK_SCOPE.size)
        assertTrue(SupportedPackages.HOOK_SCOPE.containsAll(TargetPackageRegistry.packageNames))
        assertTrue(TargetPackageRegistry.isInHookScope(SupportedPackages.SYSTEM_FRAMEWORK))
        assertTrue(TargetPackageRegistry.isInHookScope(SupportedPackages.SETTINGS_PROVIDER))
    }

    @Test
    fun `SupportedPackages and TargetPackageRegistry never disagree`() {
        // Two objects answering the same question is how a hook ends up in the wrong
        // app, so the older facade is asserted to be a pure delegate.
        assertEquals(TargetPackageRegistry.packageNames, SupportedPackages.ALL)
        for (name in SupportedPackages.ALL) {
            assertEquals(name, TargetPackageRegistry.isTarget(name), SupportedPackages.isTarget(name))
        }
        for (name in listOf("android", "com.android.providers.settings", "com.example")) {
            assertEquals(name, TargetPackageRegistry.isInHookScope(name), SupportedPackages.isInHookScope(name))
        }
    }

    @Test
    fun `display names are known for the targets and fall back for anything else`() {
        assertEquals("WhatsApp", TargetPackageRegistry.displayName("com.whatsapp"))
        assertEquals("WhatsApp Business", TargetPackageRegistry.displayName("com.whatsapp.w4b"))
        assertEquals("com.example.other", TargetPackageRegistry.displayName("com.example.other"))
        assertEquals("unknown", TargetPackageRegistry.displayName(null))
    }

    @Test
    fun `every target has a distinct package, code and display name`() {
        assertEquals(2, TargetApp.entries.size)
        assertEquals(
            TargetApp.entries.size,
            TargetApp.entries
                .map { it.packageName }
                .distinct()
                .size,
        )
        assertEquals(
            TargetApp.entries.size,
            TargetApp.entries
                .map { it.code }
                .distinct()
                .size,
        )
        assertEquals(
            TargetApp.entries.size,
            TargetApp.entries
                .map { it.displayName }
                .distinct()
                .size,
        )
    }
}
