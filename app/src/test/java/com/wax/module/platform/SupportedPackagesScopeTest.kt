package com.wax.module.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedPackagesScopeTest {
    @Test
    fun hookScopeContainsOnlyTargetsAndRequiredInfrastructure() {
        assertEquals(
            linkedSetOf(
                SupportedPackages.WHATSAPP,
                SupportedPackages.WHATSAPP_BUSINESS,
                SupportedPackages.SYSTEM_FRAMEWORK,
                SupportedPackages.SETTINGS_PROVIDER,
            ),
            SupportedPackages.HOOK_SCOPE,
        )
    }

    @Test
    fun unrelatedApplicationsAreRejectedByRuntimeScope() {
        assertFalse(TargetPackageRegistry.isInHookScope("com.example.unrelated"))
        assertFalse(TargetPackageRegistry.isInHookScope("com.google.android.apps.authenticator2"))
        assertTrue(TargetPackageRegistry.isInHookScope(SupportedPackages.WHATSAPP))
        assertTrue(TargetPackageRegistry.isInHookScope(SupportedPackages.WHATSAPP_BUSINESS))
        assertTrue(TargetPackageRegistry.isInHookScope(SupportedPackages.SYSTEM_FRAMEWORK))
        assertTrue(TargetPackageRegistry.isInHookScope(SupportedPackages.SETTINGS_PROVIDER))
    }
}
