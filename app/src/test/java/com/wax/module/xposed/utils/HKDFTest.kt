package com.wax.module.xposed.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HKDFTest {
    private val ikm = ByteArray(32) { it.toByte() }
    private val salt = ByteArray(32) { (it * 3).toByte() }
    private val info = "context".toByteArray()

    private fun hkdf3() = HKDF.createFor(3)

    @Test
    fun versionThreeIsSupported() {
        assertTrue(hkdf3() is HKDFv3)
    }

    @Test
    fun anUnknownVersionIsRejected() {
        assertThrows(AssertionError::class.java) { HKDF.createFor(2) }
    }

    @Test
    fun derivationIsDeterministic() {
        val first = hkdf3().deriveSecrets(ikm, salt, info, 32)
        val second = hkdf3().deriveSecrets(ikm, salt, info, 32)
        assertArrayEquals(first, second)
    }

    @Test
    fun theRequestedLengthIsProduced() {
        for (length in listOf(1, 16, 32, 33, 64, 100)) {
            assertEquals(
                "length $length",
                length,
                hkdf3().deriveSecrets(ikm, salt, info, length).size,
            )
        }
    }

    @Test
    fun aDifferentSaltProducesDifferentSecrets() {
        val other = ByteArray(32) { 9 }
        assertFalse(
            hkdf3()
                .deriveSecrets(ikm, salt, info, 32)
                .contentEquals(hkdf3().deriveSecrets(ikm, other, info, 32)),
        )
    }

    @Test
    fun differentInfoProducesDifferentSecrets() {
        assertFalse(
            hkdf3()
                .deriveSecrets(ikm, salt, "a".toByteArray(), 32)
                .contentEquals(hkdf3().deriveSecrets(ikm, salt, "b".toByteArray(), 32)),
        )
    }

    @Test
    fun differentKeyMaterialProducesDifferentSecrets() {
        val otherIkm = ByteArray(32) { (it + 1).toByte() }
        assertFalse(
            hkdf3()
                .deriveSecrets(ikm, salt, info, 32)
                .contentEquals(hkdf3().deriveSecrets(otherIkm, salt, info, 32)),
        )
    }

    @Test
    fun aLongerRequestExtendsTheShorterOne() {
        val hkdf = hkdf3()
        val short = hkdf.deriveSecrets(ikm, salt, info, 32)
        val long = hkdf.deriveSecrets(ikm, salt, info, 64)
        // HKDF is an expand-by-construction construction, so the first block is shared.
        assertArrayEquals(short.copyOf(32), long.copyOf(32))
    }

    @Test
    fun aNullInfoIsAccepted() {
        assertEquals(32, hkdf3().deriveSecrets(ikm, salt, null, 32).size)
    }

    @Test
    fun aNullInfoDiffersFromAnyConcreteInfo() {
        val withNull = hkdf3().deriveSecrets(ikm, salt, null, 32)
        val withInfo = hkdf3().deriveSecrets(ikm, salt, info, 32)
        assertFalse(withNull.contentEquals(withInfo))
    }

    @Test
    fun theConvenienceOverloadUsesAThirtyTwoByteSalt() {
        val viaOverload = hkdf3().deriveSecrets(ikm, info, 32)
        val explicit = hkdf3().deriveSecrets(ikm, ByteArray(0x20), info, 32)
        assertArrayEquals(explicit, viaOverload)
    }

    @Test
    fun anEmptySaltIsRejectedBecauseJavaHmacRefusesEmptyKeys() {
        // RFC 5869 allows an empty salt (it is extracted as HashLen zero bytes), but
        // javax.crypto.Mac throws on an empty HMAC key. Recorded so the limitation is
        // visible rather than discovered at runtime; every caller passes a real salt.
        assertThrows(IllegalArgumentException::class.java) {
            hkdf3().deriveSecrets(ikm, ByteArray(0), info, 32)
        }
    }

    @Test
    fun distinctInputsDoNotCollide() {
        val a = hkdf3().deriveSecrets(ikm, salt, info, 32)
        val b = hkdf3().deriveSecrets(ikm, salt, info, 32)
        val c = hkdf3().deriveSecrets(ikm, salt, "z".toByteArray(), 32)
        assertArrayEquals(a, b)
        assertNotEquals(a.toList(), c.toList())
    }
}
