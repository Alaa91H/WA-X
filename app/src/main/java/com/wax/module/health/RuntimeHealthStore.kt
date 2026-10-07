package com.wax.module.health

import java.io.File

/**
 * Persists one target's health document to one file.
 *
 * Four properties are load-bearing, and each exists because of a specific way a stored
 * diagnostics file makes things worse than storing nothing:
 *
 * * **Atomic.** The document is written to a temporary file and renamed over the real one.
 *   A crash mid-write therefore leaves either the old document or the new one, never a
 *   half-written one — and a health file is read after a crash by definition.
 * * **Per target.** The file name is derived from the package and process, so WhatsApp
 *   never reads WhatsApp Business's state. A single shared file is how the two builds'
 *   diagnostics become each other's, which is the failure this project exists to avoid.
 * * **Corruption tolerant.** A file that exists but cannot be understood is moved aside
 *   once and reported as empty. It is not deleted silently — the quarantined copy is what
 *   explains the incident — and it is not retried on every read, which would turn a corrupt
 *   file into repeated IO on the startup path.
 * * **Bounded.** History is truncated to the newest [MAX_HISTORY] events on write, so the
 *   file cannot grow with uptime.
 *
 * A null [directory] makes the store volatile: it keeps the document in memory for the
 * life of the process. That is the state the module is in before it has a usable data
 * directory, and it is deliberately the same API rather than a second code path.
 */
class RuntimeHealthStore(
    private val directory: File?,
) {
    private val lock = Any()

    /** Volatile documents, used when there is no directory to write to. */
    private val inMemory = LinkedHashMap<String, RuntimeHealthCodec.StoredHealth>()

    /** Reads the document for [targetKey], or an empty one. */
    fun read(targetKey: String): RuntimeHealthCodec.StoredHealth =
        synchronized(lock) {
            val file = fileFor(targetKey) ?: return inMemory[targetKey] ?: RuntimeHealthCodec.StoredHealth.EMPTY
            if (!file.isFile) return RuntimeHealthCodec.StoredHealth.EMPTY

            val text = runCatching { file.readText() }.getOrNull()
            if (text.isNullOrBlank()) return RuntimeHealthCodec.StoredHealth.EMPTY

            val stored = RuntimeHealthCodec.decode(text)
            if (stored.current == null && stored.lastKnownGood == null && stored.history.isEmpty()) {
                quarantine(file)
            }
            stored
        }

    /** Writes the document for the snapshot's own target. */
    fun write(
        current: RuntimeHealthSnapshot,
        lastKnownGood: RuntimeHealthSnapshot?,
        history: List<HealthEvent>,
    ) {
        val bounded = history.takeLast(MAX_HISTORY)
        val targetKey = current.targetKey
        val text = RuntimeHealthCodec.encode(current, lastKnownGood, bounded)
        synchronized(lock) {
            val file = fileFor(targetKey)
            if (file == null) {
                inMemory[targetKey] = RuntimeHealthCodec.StoredHealth(current, lastKnownGood, bounded)
                return
            }
            runCatching {
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, file.name + TEMPORARY_SUFFIX)
                temporary.writeText(text)
                if (temporary.renameTo(file)) return
                // A filesystem that refuses the rename (Windows over an existing file, for
                // one) still gets a whole document, just not an atomic one.
                file.writeText(text)
                temporary.delete()
            }
        }
    }

    /** Removes the stored document for [targetKey]. */
    fun clear(targetKey: String) {
        synchronized(lock) {
            val file = fileFor(targetKey)
            if (file == null) {
                inMemory.remove(targetKey)
            } else {
                runCatching { file.delete() }
                runCatching { File(file.parentFile, file.name + QUARANTINE_SUFFIX).delete() }
            }
        }
    }

    /** The file this store would use for [targetKey], or null when it is volatile. */
    fun fileFor(targetKey: String): File? {
        val dir = directory ?: return null
        return File(dir, FILE_PREFIX + sanitize(targetKey) + FILE_SUFFIX)
    }

    private fun quarantine(file: File) {
        runCatching {
            val target = File(file.parentFile, file.name + QUARANTINE_SUFFIX)
            target.delete()
            file.renameTo(target)
        }
    }

    private fun sanitize(targetKey: String): String =
        targetKey.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("")

    companion object {
        /** File name prefix, so the directory's contents are self-describing. */
        const val FILE_PREFIX: String = "runtime-health-"

        /** File name suffix. */
        const val FILE_SUFFIX: String = ".json"

        /** Suffix of the in-progress write. */
        const val TEMPORARY_SUFFIX: String = ".tmp"

        /** Suffix of a document that could not be read and was moved aside. */
        const val QUARANTINE_SUFFIX: String = ".corrupt"

        /** How many events are kept. Enough to explain one bootstrap, bounded by design. */
        const val MAX_HISTORY: Int = 64
    }
}
