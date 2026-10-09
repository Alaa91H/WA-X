package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure checks for the Tasker bridge; broadcasts need a real Tasker install. */
class ModernTaskerFeatureTest {
    @Test fun preferenceKeysMatchTheManagerSettingsScreen() {
        // Must equal the keys in res/xml/preference_general_home.xml.
        assertEquals("tasker", ModernTaskerFeature.PREF_ENABLED)
        assertEquals("tasker_auth_token", ModernTaskerFeature.PREF_TOKEN)
    }

    @Test fun resolverAnchorsMatchTheLegacyUnobfuscatorEvidence() {
        assertEquals("receipt", ModernTaskerFeature.ANCHOR_RECEIPT)
        assertEquals("ProtocolTreeNode/getAttributeJid", ModernTaskerFeature.ANCHOR_PROTOCOL_TREE_NODE)
        assertEquals("jid.DeviceJid", ModernTaskerFeature.DEVICE_JID_SUFFIX)
    }

    @Test fun broadcastContractIsPackageTargetedWithAToken() {
        assertEquals("net.dinglisch.android.taskerm", ModernTaskerFeature.TASKER_PACKAGE)
        assertEquals("com.wax.module.MESSAGE_RECEIVED", ModernTaskerFeature.ACTION_MESSAGE_RECEIVED)
        assertEquals("token", ModernTaskerFeature.EXTRA_AUTH_TOKEN)
    }

    @Test fun tokenMustMatchAndMustNotBeBlank() {
        assertTrue(ModernTaskerFeature.isAuthorized("secret", "secret"))
        assertFalse(ModernTaskerFeature.isAuthorized("secret", "wrong"))
        assertFalse(ModernTaskerFeature.isAuthorized("secret", null))
        assertFalse(ModernTaskerFeature.isAuthorized("", ""))
        assertFalse(ModernTaskerFeature.isAuthorized(null, null))
    }

    @Test fun numbersAreNormalizedFromBothBroadcastShapes() {
        assertEquals("15551234567", ModernTaskerFeature.normalizeNumber("+1 555 123 4567"))
        assertEquals("15551234567", ModernTaskerFeature.normalizeNumber(15551234567L))
        assertEquals("15551234567", ModernTaskerFeature.normalizeNumber(15551234567))
        assertNull(ModernTaskerFeature.normalizeNumber(null))
        assertNull(ModernTaskerFeature.normalizeNumber("0"))
        assertNull(ModernTaskerFeature.normalizeNumber(""))
        assertNull(ModernTaskerFeature.normalizeNumber("abc"))
    }

    @Test fun outcomeSetSaysTheSendDirectionIsPending() {
        val outcomes = ModernTaskerFeature.Outcome.values().map { it.name }
        assertTrue(outcomes.containsAll(listOf(
            "DISABLED", "TOKEN_MISSING", "RESOLVER_MISSING",
            "RESOLVER_AMBIGUOUS", "SEND_DIRECTION_PENDING", "INSTALLED",
        )))
    }
}