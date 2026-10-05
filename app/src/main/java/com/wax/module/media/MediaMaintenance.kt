package com.wax.module.media

/** A set of items believed to be byte-identical. */
data class DuplicateGroup(
    val hash: String,
    val items: List<MediaItem>,
) {
    /** How many copies beyond the first exist. */
    val duplicateCount: Int get() = (items.size - 1).coerceAtLeast(0)

    /** Bytes that would be freed by keeping one copy. */
    val reclaimableBytes: Long get() = items.drop(1).sumOf { it.sizeBytes }
}

/**
 * Finds exact duplicates by content hash.
 *
 * Only exact matches are reported, and only when the caller can supply a hash: near-
 * duplicate detection is explicitly a later extension in T121, and a false duplicate in a
 * cleanup tool is destructive. Items whose hash cannot be computed are left out rather than
 * guessed at.
 */
object DuplicateDetector {
    /** Groups items by [hashOf]; only groups with two or more members are returned. */
    fun findDuplicates(
        items: List<MediaItem>,
        hashOf: (MediaItem) -> String?,
    ): List<DuplicateGroup> =
        items
            .mapNotNull { item -> hashOf(item)?.let { hash -> hash to item } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .map { (hash, groupItems) -> DuplicateGroup(hash, groupItems.sortedBy { it.createdAtMillis }) }
            .sortedByDescending { it.reclaimableBytes }

    /** Total bytes recoverable across every group. */
    fun reclaimableBytes(groups: List<DuplicateGroup>): Long = groups.sumOf { it.reclaimableBytes }
}

/** How the status archive decides what to keep (T122). */
enum class StatusArchiveMode {
    /** Only when the user saves a status by hand. */
    MANUAL_ONLY,

    /** Statuses from favourite contacts. */
    FAVORITES,

    /** Statuses from the explicitly selected contacts. */
    SELECTED_CONTACTS,

    /** Everything, automatically. */
    AUTOMATIC,
}

/** The archive configuration. */
data class StatusArchiveSettings(
    val mode: StatusArchiveMode,
    val selectedChatIds: Set<String> = emptySet(),
) {
    /**
     * Whether a status from [chatId] should be archived.
     *
     * @param isFavorite whether the contact is a favourite
     */
    fun shouldArchive(
        chatId: String,
        isFavorite: Boolean,
    ): Boolean =
        when (mode) {
            StatusArchiveMode.MANUAL_ONLY -> false
            StatusArchiveMode.FAVORITES -> isFavorite
            StatusArchiveMode.SELECTED_CONTACTS -> chatId in selectedChatIds
            StatusArchiveMode.AUTOMATIC -> true
        }

    /** One line for the archive settings screen. */
    fun toDisplayLine(): String =
        when (mode) {
            StatusArchiveMode.MANUAL_ONLY -> "manual saves only"
            StatusArchiveMode.FAVORITES -> "favourite contacts"
            StatusArchiveMode.SELECTED_CONTACTS -> "${selectedChatIds.size} selected contact(s)"
            StatusArchiveMode.AUTOMATIC -> "everything"
        }
}

/** One file a cleanup run may delete. */
data class CleanupCandidate(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val category: String,
    val createdAtMillis: Long,
    val isProtected: Boolean = false,
)

/** What a cleanup run would do, shown before anything is deleted (T123). */
data class CleanupPreview(
    val files: List<CleanupCandidate>,
    val totalSizeBytes: Long,
    val categories: Map<String, Int>,
    val oldestMillis: Long?,
    val newestMillis: Long?,
) {
    /** How many files would be deleted. */
    val fileCount: Int get() = files.size

    /** Whether there is anything to delete. */
    val isEmpty: Boolean get() = files.isEmpty()

    /** The date range as a human-readable line. */
    fun dateRangeLine(): String =
        if (files.isEmpty()) {
            "no files"
        } else {
            "$oldestMillis to $newestMillis"
        }

    /** The T123 preview rendering, exactly the shape the roadmap shows. */
    fun render(): String =
        buildString {
            appendLine("Selected files: $fileCount")
            appendLine("Total size: ${totalSizeBytes / 1024} KiB")
            appendLine("Categories: " + categories.entries.joinToString(", ") { "${it.key}=${it.value}" })
            appendLine("Date range: ${dateRangeLine()}")
        }.trimEnd()
}

/**
 * Builds the cleanup preview and guards the deletion.
 *
 * "No silent deletion" is enforced structurally: the only way to delete is
 * [CleanupExecutor], and it takes a [CleanupPreview] that a UI must have rendered. Protected
 * files are filtered out at preview time so the user never sees a file offered for deletion
 * that would be refused — and the executor re-checks the same list, so a preview cannot be
 * reused after its inputs changed.
 */
object CleanupPlanner {
    /** Builds a preview from candidate files, dropping protected ones. */
    fun preview(candidates: List<CleanupCandidate>): CleanupPreview {
        val deletable = candidates.filterNot { it.isProtected }
        return CleanupPreview(
            files = deletable,
            totalSizeBytes = deletable.sumOf { it.sizeBytes },
            categories = deletable.groupingBy { it.category }.eachCount(),
            oldestMillis = deletable.minOfOrNull { it.createdAtMillis },
            newestMillis = deletable.maxOfOrNull { it.createdAtMillis },
        )
    }
}

/** Deletes files after a preview was shown. */
fun interface CleanupExecutor {
    /** Deletes one file; must return false when it could not be deleted. */
    fun delete(fileId: String): Boolean
}

/** The outcome of executing a preview. */
data class CleanupResult(
    val deleted: Int,
    val failed: List<String>,
    val freedBytes: Long,
) {
    /** One line for the completion message. */
    fun toDisplayLine(): String =
        "deleted $deleted file(s), freed ${freedBytes / 1024} KiB" +
            if (failed.isEmpty()) "" else ", ${failed.size} failed"
}

/** Executes an already-shown preview. */
object CleanupRunner {
    /** Deletes the files in [preview], one by one, reporting failures instead of ignoring them. */
    fun execute(
        preview: CleanupPreview,
        executor: CleanupExecutor,
    ): CleanupResult {
        var deleted = 0
        var freed = 0L
        val failed = ArrayList<String>()
        preview.files.forEach { file ->
            if (executor.delete(file.id)) {
                deleted++
                freed += file.sizeBytes
            } else {
                failed.add(file.displayName)
            }
        }
        return CleanupResult(deleted, failed, freed)
    }
}
