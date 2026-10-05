package com.wax.module.storage

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.string

/** One file known to the duplicate finder. */
data class FileRecord(
    val path: String,
    val sizeBytes: Long,
    /** The content hash, or null when it has not been computed yet. */
    val hash: String?,
)

/** A set of file paths with identical content. */
data class FileDuplicateGroup(
    val hash: String,
    val files: List<FileRecord>,
) {
    /** How many files beyond the first. */
    val duplicateCount: Int get() = (files.size - 1).coerceAtLeast(0)

    /** Bytes freed by keeping one copy. */
    val reclaimableBytes: Long get() = files.drop(1).sumOf { it.sizeBytes }
}

/**
 * Caches file hashes so a second scan does not re-read every byte.
 *
 * The database stores paths and hashes only, and only locally. Entries for files that no
 * longer exist are dropped by [prune], keeping the database from growing forever as files
 * come and go.
 */
class LocalHashDatabase(
    private val store: KeyValueStore,
) {
    /** Records a computed hash. */
    fun putHash(
        path: String,
        hash: String,
    ) {
        if (path.isBlank() || hash.isBlank()) return
        store.putString(keyFor(path), MiniJson.write(jsonObject("hash" to jsonString(hash))))
    }

    /** The cached hash for a path, or null. */
    fun hashOf(path: String): String? {
        val text = store.getString(keyFor(path)) ?: return null
        val fields = (MiniJson.parse(text) as? JsonValue.Obj)?.fields ?: return null
        return fields.string("hash")
    }

    /** Removes one path. */
    fun remove(path: String): Boolean {
        if (store.getString(keyFor(path)) == null) return false
        store.remove(keyFor(path))
        return true
    }

    /** The number of cached hashes. */
    fun size(): Int = store.keys(KEY_PREFIX).size

    /** All cached entries as path -> hash. */
    fun all(): Map<String, String> =
        store
            .keys(KEY_PREFIX)
            .mapNotNull { key ->
                val path = decodePath(key) ?: return@mapNotNull null
                val hash = hashOf(path) ?: return@mapNotNull null
                path to hash
            }.toMap()

    /** Drops entries for paths no longer present according to [exists]. */
    fun prune(exists: (String) -> Boolean): Int {
        var removed = 0
        store.keys(KEY_PREFIX).forEach { key ->
            val path = decodePath(key) ?: return@forEach
            if (!exists(path)) {
                store.remove(key)
                removed++
            }
        }
        return removed
    }

    /** Drops every cached hash. */
    fun clear() {
        store.keys(KEY_PREFIX).forEach { store.remove(it) }
    }

    private fun keyFor(path: String): String =
        KEY_PREFIX +
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(path.toByteArray(Charsets.UTF_8))

    private fun decodePath(key: String): String? =
        runCatching {
            String(
                java.util.Base64
                    .getUrlDecoder()
                    .decode(key.removePrefix(KEY_PREFIX)),
                Charsets.UTF_8,
            )
        }.getOrNull()

    companion object {
        /** Storage key prefix for cached hashes. */
        const val KEY_PREFIX: String = "wae.storage.hashes."
    }
}

/**
 * Finds exact duplicate files from known hashes.
 *
 * Files without a hash are skipped: an unknown file is not a duplicate of anything, and
 * reporting a guess here would offer the user a destructive action based on nothing.
 */
object DuplicateFileFinder {
    /** Groups files by hash; only groups of two or more are returned. */
    fun findDuplicates(files: List<FileRecord>): List<FileDuplicateGroup> =
        files
            .filter { !it.hash.isNullOrBlank() }
            .groupBy { it.hash!! }
            .filterValues { it.size > 1 }
            .map { (hash, group) -> FileDuplicateGroup(hash, group.sortedBy { it.path }) }
            .sortedByDescending { it.reclaimableBytes }

    /** Total bytes recoverable across groups. */
    fun reclaimableBytes(groups: List<FileDuplicateGroup>): Long = groups.sumOf { it.reclaimableBytes }
}
