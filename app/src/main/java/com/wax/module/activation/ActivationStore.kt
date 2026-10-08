package com.wax.module.activation

import java.io.File

/**
 * The Manager's own record of the last heartbeat each target sent.
 *
 * The runtime cannot write here. Its health document lives in the *target's* data directory -
 * the target's `Application` is the context the runtime attaches storage with - so the one
 * process that could describe that runtime has never been able to read what it wrote. That is
 * not a bug in this store; it is the reason this store exists, and it is why the heartbeat
 * travels by broadcast and is filed here by the Manager instead.
 *
 * Filing it here buys the thing a broadcast cannot: an age that survives the target dying. A
 * fresh broadcast says WA X is running now. A record written 40 seconds ago says WA X was
 * running, and the difference between those two is what [com.wax.module.health.HealthFreshness]
 * is for.
 *
 * Storage is one file per target key, written atomically, with a corrupt document quarantined
 * rather than deleted or trusted. The rules are the same ones the runtime health store follows,
 * for the same reason: a diagnostics file that throws on read turns a reporting problem into a
 * crash.
 */
class ActivationStore(
    private val directory: File,
) {
    private val lock = Any()

    /**
     * The last heartbeat filed for [targetKey], or null when there is none.
     *
     * A document that cannot be read is quarantined and reported as absent. Reporting it as
     * "injection was never observed" would be inventing a failure out of a parse error.
     */
    fun read(targetKey: String): TargetHeartbeat? =
        synchronized(lock) {
            val file = fileFor(targetKey)
            if (!file.isFile) return null
            val text = runCatching { file.readText() }.getOrNull() ?: return null
            val decoded = TargetHeartbeatCodec.decode(text)
            if (decoded == null) {
                quarantine(file)
                null
            } else {
                decoded
            }
        }

    /**
     * Files [heartbeat] under its own target key, replacing any earlier one.
     *
     * Returns whether the record reached storage. A diagnostics store that failed to write is
     * not worth crashing the interface over, so the failure is contained - but it is *reported*
     * rather than swallowed, because a store that silently stops recording produces exactly the
     * silent-degradation failure this package exists to remove, one layer up.
     */
    fun write(heartbeat: TargetHeartbeat): Boolean =
        synchronized(lock) {
            val encoded = TargetHeartbeatCodec.encode(heartbeat)
            runCatching {
                directory.mkdirs()
                val target = fileFor(heartbeat.targetKey)
                val temporary = File(directory, target.name + TEMPORARY_SUFFIX)
                temporary.writeText(encoded)
                // renameTo is not atomic across filesystems but both paths are in the same
                // directory, and a failed delete would leave the previous record readable,
                // which is a stale record rather than a missing one - the failure mode this
                // file format is designed around.
                if (!temporary.renameTo(target)) {
                    target.writeText(encoded)
                    temporary.delete()
                }
            }.isSuccess
        }

    /** The target keys currently filed, in a stable order. */
    fun keys(): List<String> =
        synchronized(lock) {
            if (!directory.isDirectory) {
                emptyList()
            } else {
                directory
                    .listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
                    .orEmpty()
                    .sortedBy { it.name }
                    .mapNotNull { TargetHeartbeatCodec.decode(runCatching { it.readText() }.getOrNull())?.targetKey }
            }
        }

    /** Forgets every filed heartbeat. */
    fun clear() {
        synchronized(lock) {
            runCatching {
                directory.listFiles()?.forEach { it.delete() }
            }
        }
    }

    /**
     * The file [targetKey] is stored in.
     *
     * The key is `package|process`, and the `|` is not a legal character in a file name on
     * every platform this code runs on - Windows rejects it outright - so the name is built
     * from a readable prefix plus a digest of the whole key. The digest is what makes the
     * mapping unambiguous: a readable prefix alone would let two keys whose sanitised forms
     * collide share one record, and a WhatsApp record read as a WhatsApp Business record is
     * precisely the multi-account confusion this package is required to prevent.
     */
    private fun fileFor(targetKey: String): File =
        File(directory, FILE_PREFIX + readable(targetKey) + "-" + digest(targetKey) + FILE_SUFFIX)

    private fun quarantine(file: File) {
        runCatching {
            val destination = File(file.parentFile, file.name + QUARANTINE_SUFFIX)
            file.renameTo(destination)
        }
    }

    /** A readable, filesystem-safe rendering of [targetKey], truncated so names stay short. */
    private fun readable(targetKey: String): String =
        buildString {
            for (char in targetKey.take(MAX_READABLE)) {
                append(if (char.isLetterOrDigit() || char == '.' || char == '-' || char == '_') char else '_')
            }
        }

    private fun digest(targetKey: String): String {
        val bytes =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(targetKey.toByteArray())
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FILE_PREFIX: String = "activation-"
        const val FILE_SUFFIX: String = ".json"
        const val TEMPORARY_SUFFIX: String = ".tmp"
        const val QUARANTINE_SUFFIX: String = ".corrupt"

        private const val MAX_READABLE = 48
    }
}
