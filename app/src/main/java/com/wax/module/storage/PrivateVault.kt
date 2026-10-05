package com.wax.module.storage

import com.wax.module.platform.KeyValueStore
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography behind the private vault.
 *
 * Choices, and why:
 * - **PBKDF2-HMAC-SHA256** with 210 000 iterations derives the key. It is available on every
 *   Android API level the module supports without bundling a native library, and it is a
 *   deliberate, slow KDF rather than a bare hash.
 * - **AES-256-GCM** encrypts and authenticates. GCM is what makes "wrong password" and
 *   "tampered data" both detectable: there is no mode in which a modified blob decrypts
 *   into plausible plaintext.
 * - Every blob carries its own random salt and nonce, so two encryptions of the same secret
 *   produce different ciphertexts and a nonce is never reused under one key.
 *
 * The derived key is never stored anywhere; it exists only as long as the operation. The
 * [decrypt] contract is "null means the password was wrong or the data was altered" — the
 * two are indistinguishable by design, because telling an attacker which one applies leaks
 * information.
 */
object VaultCrypto {
    /** Length of the random salt. */
    const val SALT_BYTES: Int = 16

    /** Length of the GCM nonce. */
    const val NONCE_BYTES: Int = 12

    /** AES key size. */
    const val KEY_BITS: Int = 256

    /** GCM authentication tag size. */
    const val TAG_BITS: Int = 128

    /** Iterations used in production. */
    const val DEFAULT_ITERATIONS: Int = 210_000

    /** The smallest iterations accepted, so a caller cannot weaken the KDF by accident. */
    const val MIN_ITERATIONS: Int = 10_000

    private val random = SecureRandom()
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

    /** Derives the AES key for [password] and [salt]. */
    fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): SecretKey {
        require(iterations >= MIN_ITERATIONS) { "iterations must be at least $MIN_ITERATIONS" }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Encrypts [plaintext], returning a self-describing blob. */
    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(password, salt, iterations),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        val ciphertext = cipher.doFinal(plaintext)
        val output = ByteArrayOutputStream(MAGIC.size + 4 + salt.size + nonce.size + ciphertext.size)
        DataOutputStream(output).use { stream ->
            stream.write(MAGIC)
            stream.writeInt(iterations)
            stream.write(salt)
            stream.write(nonce)
            stream.write(ciphertext)
        }
        return output.toByteArray()
    }

    /** Decrypts a blob, or returns null when the password is wrong or the data was altered. */
    fun decrypt(
        blob: ByteArray,
        password: CharArray,
    ): ByteArray? {
        if (!isVaultBlob(blob)) return null
        return try {
            val headerSize = MAGIC.size + 4 + SALT_BYTES + NONCE_BYTES
            val iterations = readInt(blob, MAGIC.size)
            if (iterations < MIN_ITERATIONS) return null
            val salt = blob.copyOfRange(MAGIC.size + 4, MAGIC.size + 4 + SALT_BYTES)
            val nonce = blob.copyOfRange(MAGIC.size + 4 + SALT_BYTES, headerSize)
            val ciphertext = blob.copyOfRange(headerSize, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(password, salt, iterations),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.doFinal(ciphertext)
        } catch (_: Throwable) {
            null
        }
    }

    /** Whether [blob] starts with the vault header. */
    fun isVaultBlob(blob: ByteArray): Boolean =
        blob.size > MAGIC.size + 4 + SALT_BYTES + NONCE_BYTES &&
            blob[0] == MAGIC[0] &&
            blob[1] == MAGIC[1] &&
            blob[2] == MAGIC[2]

    private fun readInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}

/**
 * A password-protected store of arbitrary local secrets.
 *
 * Only ciphertext is written to the [KeyValueStore]; the password and the derived key never
 * touch storage, which is what "no plaintext temporary leakage" means in practice — there is
 * no intermediate representation on disk at all. Access always requires the password
 * (T147's "authenticated access"), and removing an entry removes its only copy.
 */
class PrivateVault(
    private val store: KeyValueStore,
) {
    /** Encrypts and stores [plaintext] under [id]. */
    fun store(
        id: String,
        plaintext: ByteArray,
        password: CharArray,
        iterations: Int = VaultCrypto.DEFAULT_ITERATIONS,
    ): Boolean {
        if (!isValidId(id)) return false
        val blob = VaultCrypto.encrypt(plaintext, password, iterations)
        store.putString(keyFor(id), Base64.getEncoder().encodeToString(blob))
        return true
    }

    /** Retrieves and decrypts the entry, or null when absent or the password is wrong. */
    fun retrieve(
        id: String,
        password: CharArray,
    ): ByteArray? {
        if (!isValidId(id)) return null
        val encoded = store.getString(keyFor(id)) ?: return null
        val blob = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        return VaultCrypto.decrypt(blob, password)
    }

    /** Whether an entry exists. */
    fun contains(id: String): Boolean = isValidId(id) && store.getString(keyFor(id)) != null

    /** Removes an entry. */
    fun remove(id: String): Boolean {
        val key = keyFor(id)
        if (store.getString(key) == null) return false
        store.remove(key)
        return true
    }

    /** Every stored entry id. */
    fun ids(): List<String> = store.keys(KEY_PREFIX).mapNotNull { decodeId(it) }

    /** How many entries are stored. */
    fun size(): Int = ids().size

    /** Empties the vault. */
    fun clear() {
        store.keys(KEY_PREFIX).forEach { store.remove(it) }
    }

    private fun isValidId(id: String): Boolean = id.isNotBlank() && id.length <= MAX_ID_LENGTH && id.none { it.isISOControl() }

    /** Keys encode the id in URL-safe base64 so arbitrary ids cannot collide with separators. */
    private fun keyFor(id: String): String =
        KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))

    private fun decodeId(key: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(key.removePrefix(KEY_PREFIX)), Charsets.UTF_8) }
            .getOrNull()

    companion object {
        /** Storage key prefix for vault entries. */
        const val KEY_PREFIX: String = "wae.vault."

        /** Longest accepted entry id. */
        const val MAX_ID_LENGTH: Int = 64
    }
}
