package com.wax.module.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scope allowlist is the module's last line of defence: LSPosed's own scope is
 * user editable, so the hook entry point has to reject anything it does not support
 * before it installs a single hook.
 */
class SupportedPackagesTest {
    // --- the supported set ----------------------------------------------------------

    @Test
    fun `the supported set is WhatsApp and WhatsApp Business and nothing else`() {
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), SupportedPackages.ALL)
    }

    @Test
    fun `the module's own packages are never a target`() {
        assertFalse(SupportedPackages.isTarget("com.wax.module"))
        assertFalse(SupportedPackages.isTarget("com.wax.module.w4b"))
    }

    @Test
    fun `the settings provider and the system framework are not targets`() {
        assertFalse(SupportedPackages.isTarget(SupportedPackages.SYSTEM_FRAMEWORK))
        assertFalse(SupportedPackages.isTarget(SupportedPackages.SETTINGS_PROVIDER))
    }

    // --- target detection -----------------------------------------------------------

    @Test
    fun `both WhatsApp variants are targets`() {
        assertTrue(SupportedPackages.isTarget("com.whatsapp"))
        assertTrue(SupportedPackages.isTarget("com.whatsapp.w4b"))
    }

    @Test
    fun `the unrelated packages from the bug report are rejected`() {
        val unrelated =
            listOf(
                "com.openai.chatgpt",
                "com.aliexpress.mobile",
                "com.onedownload.manager",
                "com.authenticator.authenticator2",
                "com.android.chrome",
                "com.google.android.gms",
                "com.facebook.katana",
            )
        for (packageName in unrelated) {
            assertFalse(packageName, SupportedPackages.isTarget(packageName))
            assertFalse(packageName, SupportedPackages.isInHookScope(packageName))
        }
    }

    @Test
    fun `an unresolved package name is rejected rather than guessed`() {
        assertFalse(SupportedPackages.isTarget(null))
        assertFalse(SupportedPackages.isTarget(""))
        assertFalse(SupportedPackages.isTarget("   "))
        assertFalse(SupportedPackages.isInHookScope(null))
        assertFalse(SupportedPackages.isInHookScope(""))
    }

    @Test
    fun `package names are matched exactly, not by prefix`() {
        assertFalse(SupportedPackages.isTarget("com.whatsapp.w4b.extra"))
        assertFalse(SupportedPackages.isTarget("com.whatsappclone"))
        assertFalse(SupportedPackages.isTarget("com.whatsappx"))
        assertFalse(SupportedPackages.isTarget("com.WHATSAPP"))
    }

    // --- hook scope -----------------------------------------------------------------

    @Test
    fun `the hook scope adds the infrastructure processes to the targets`() {
        assertEquals(4, SupportedPackages.HOOK_SCOPE.size)
        assertTrue(SupportedPackages.HOOK_SCOPE.containsAll(SupportedPackages.ALL))
        assertTrue(SupportedPackages.HOOK_SCOPE.contains(SupportedPackages.SYSTEM_FRAMEWORK))
        assertTrue(SupportedPackages.HOOK_SCOPE.contains(SupportedPackages.SETTINGS_PROVIDER))
    }

    @Test
    fun `every hookable package is either a target or declared infrastructure`() {
        for (packageName in SupportedPackages.ALL) {
            assertTrue(packageName, SupportedPackages.isInHookScope(packageName))
        }
        assertTrue(SupportedPackages.isInHookScope(SupportedPackages.SYSTEM_FRAMEWORK))
        assertTrue(SupportedPackages.isInHookScope(SupportedPackages.SETTINGS_PROVIDER))
    }

    // --- system framework -----------------------------------------------------------

    @Test
    fun `the framework package alone is not the framework process`() {
        assertTrue(SupportedPackages.isSystemFramework("android", "android"))
        assertFalse(SupportedPackages.isSystemFramework("android", "android.systemui"))
        assertFalse(SupportedPackages.isSystemFramework("system", "system"))
        assertFalse(SupportedPackages.isSystemFramework("android", null))
        assertFalse(SupportedPackages.isSystemFramework(null, "android"))
    }

    // --- display names --------------------------------------------------------------

    @Test
    fun `display names are known for the targets and fall back to the package name`() {
        assertEquals("WhatsApp", SupportedPackages.displayName("com.whatsapp"))
        assertEquals("WhatsApp Business", SupportedPackages.displayName("com.whatsapp.w4b"))
        assertEquals("com.example.other", SupportedPackages.displayName("com.example.other"))
    }
}
