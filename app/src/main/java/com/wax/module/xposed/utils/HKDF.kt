package com.wax.module.xposed.utils

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

abstract class HKDF {
    companion object {
        @JvmStatic
        fun createFor(version: Int): HKDF {
            if (version == 3) {
                return HKDFv3()
            }
            throw AssertionError("Unknown version: $version")
        }
    }

    fun deriveSecrets(
        arr_b: ByteArray,
        arr_b1: ByteArray,
        v: Int,
    ): ByteArray = deriveSecrets(arr_b, ByteArray(0x20), arr_b1, v)

    fun deriveSecrets(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray?,
        outputLength: Int,
    ): ByteArray {
        val extractMac = createHmac(salt)
        val derivedKey = extractMac.doFinal(inputKeyMaterial)

        val iterations = Math.ceil(outputLength.toDouble() / 32.0).toInt()
        var outputKey = ByteArray(0)
        val outputStream = ByteArrayOutputStream()
        var remainingLength = outputLength
        var i = iterationStartOffset
        while (i < iterationStartOffset + iterations) {
            val macIteration = createHmac(derivedKey)
            macIteration.update(outputKey)
            if (info != null) {
                macIteration.update(info)
            }
            macIteration.update(i.toByte())
            outputKey = macIteration.doFinal()
            val len = minOf(remainingLength, outputKey.size)
            outputStream.write(outputKey, 0, len)
            remainingLength -= len
            ++i
        }
        return outputStream.toByteArray()
    }

    private fun createHmac(key: ByteArray): Mac =
        try {
            Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(key, "HmacSHA256"))
            }
        } catch (exception: GeneralSecurityException) {
            throw AssertionError(exception)
        }

    protected abstract val iterationStartOffset: Int
}
