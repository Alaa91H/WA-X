package com.wmods.wppenhacer.storage

/** The categories the storage dashboard reports (T144). */
enum class StorageCategory(
    val displayName: String,
) {
    TRANSCRIPTS("Transcriptions"),
    STATUS_ARCHIVE("Status archive"),
    RECORDINGS("Call recordings"),
    CACHE("Cache"),
    BACKUPS("Backups"),
    DIAGNOSTICS("Diagnostics"),
    DOWNLOADS("Downloads"),
}

/** One category's usage. */
data class StorageUsage(
    val category: StorageCategory,
    val bytes: Long,
    val fileCount: Int,
) {
    init {
        require(bytes >= 0L) { "bytes must not be negative" }
        require(fileCount >= 0) { "fileCount must not be negative" }
    }

    /** A human-readable size. */
    fun sizeText(): String = formatSize(bytes)

    /** One dashboard row. */
    fun toDisplayLine(): String = "${category.displayName}: ${sizeText()} ($fileCount files)"
}

/**
 * The dashboard data, assembled from whatever sources the app has.
 *
 * Merging happens here rather than in the UI: two sources for the same category (for example
 * two cache directories) sum into one row, so the totals are always the sum of the rows and
 * the screen cannot show a category twice.
 */
data class StorageDashboard(
    val entries: List<StorageUsage>,
) {
    /** Total bytes across categories. */
    val totalBytes: Long get() = entries.sumOf { it.bytes }

    /** The category using the most space, or null when empty. */
    val largest: StorageUsage? get() = entries.maxByOrNull { it.bytes }

    /** Usage for one category, zero when absent. */
    fun of(category: StorageCategory): StorageUsage = entries.firstOrNull { it.category == category } ?: StorageUsage(category, 0L, 0)

    /** One line per category, largest first. */
    fun render(): String =
        buildString {
            appendLine("WaEnhancer storage: ${formatSize(totalBytes)}")
            entries.sortedByDescending { it.bytes }.forEach { appendLine("* ${it.toDisplayLine()}") }
        }.trimEnd()

    companion object {
        /** Builds a dashboard from possibly duplicated category entries. */
        fun from(usage: List<StorageUsage>): StorageDashboard {
            val merged =
                usage
                    .groupBy { it.category }
                    .map { (category, entries) ->
                        StorageUsage(category, entries.sumOf { it.bytes }, entries.sumOf { it.fileCount })
                    }
                    // Categories with no data still appear, at zero, so the screen has a stable
                    // layout and "nothing here" is distinguishable from "not measured".
                    .let { measured ->
                        val byCategory = measured.associateBy { it.category }
                        StorageCategory.entries.map { byCategory[it] ?: StorageUsage(it, 0L, 0) }
                    }
            return StorageDashboard(merged)
        }
    }
}

/** One file the cleanup engine may consider. */
data class StorageFile(
    val id: String,
    val displayName: String,
    val category: StorageCategory,
    val sizeBytes: Long,
    val ageMillis: Long,
    val state: StorageFileState = StorageFileState.READY,
)

/** The state of a stored file that cleanup cares about. */
enum class StorageFileState {
    READY,

    /** A partially written or failed temporary file, safe to remove. */
    FAILED_TEMPORARY,
}

/** A cleanup rule (T145). */
sealed interface CleanupRule {
    /** Files in [category] older than [ageMillis]. */
    data class OlderThan(
        val category: StorageCategory,
        val ageMillis: Long,
    ) : CleanupRule

    /** Files in [category] when the category exceeds [bytes]. */
    data class LargerThan(
        val category: StorageCategory,
        val bytes: Long,
    ) : CleanupRule

    /** Failed temporary downloads in any category. */
    data object FailedTemporaryDownloads : CleanupRule
}

/** What one rule selected. */
data class CleanupSelection(
    val rule: CleanupRule,
    val files: List<StorageFile>,
    val bytes: Long,
) {
    /** One line for the preview. */
    fun toDisplayLine(): String = "${describeRule(rule)}: ${files.size} file(s), ${bytes / 1024} KiB"
}

/** The full plan, always shown before anything is deleted. */
data class SmartCleanupPlan(
    val selections: List<CleanupSelection>,
) {
    /** Every file that would be deleted, deduplicated. */
    val files: List<StorageFile> get() = selections.flatMap { it.files }.distinctBy { it.id }

    /** How many files would be deleted. */
    val fileCount: Int get() = files.size

    /** Total bytes that would be freed. */
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** Whether there is anything to delete. */
    val isEmpty: Boolean get() = files.isEmpty()

    /** The T123-style preview rendering. */
    fun render(): String =
        buildString {
            appendLine("Selected files: $fileCount")
            appendLine("Total size: ${totalBytes / 1024} KiB")
            selections.filter { it.files.isNotEmpty() }.forEach { appendLine("* ${it.toDisplayLine()}") }
        }.trimEnd()
}

/** Formats a byte count for humans. */
fun formatSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
        else -> "${bytes / (1024 * 1024)} MiB"
    }

/** One day in milliseconds, used by the default cleanup policies. */
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

/** Formats a rule for humans. */
fun describeRule(rule: CleanupRule): String =
    when (rule) {
        is CleanupRule.OlderThan -> "${rule.category.displayName} older than ${rule.ageMillis / DAY_MILLIS} day(s)"
        is CleanupRule.LargerThan -> "${rule.category.displayName} over ${rule.bytes / (1024 * 1024)} MiB"
        CleanupRule.FailedTemporaryDownloads -> "failed temporary downloads"
    }

/**
 * Plans smart cleanup without performing it.
 *
 * The engine's contract is that it only ever *selects*. Deletion is a separate, explicit
 * step the UI must confirm after showing the plan, which is how "no silent deletion" is
 * guaranteed structurally rather than by convention.
 */
class SmartCleanupEngine(
    private val rules: List<CleanupRule> = defaults(),
) {
    /** The selection for every rule, in rule order. */
    fun plan(files: List<StorageFile>): SmartCleanupPlan =
        SmartCleanupPlan(
            rules.map { rule ->
                val selected = files.filter { rule.matches(it) }
                CleanupSelection(rule, selected, selected.sumOf { it.sizeBytes })
            },
        )

    /** Evaluates one rule against one file. */
    fun CleanupRule.matches(file: StorageFile): Boolean =
        when (this) {
            is CleanupRule.OlderThan -> file.category == category && file.ageMillis >= ageMillis
            is CleanupRule.LargerThan -> file.category == category && file.sizeBytes >= bytes
            CleanupRule.FailedTemporaryDownloads -> file.state == StorageFileState.FAILED_TEMPORARY
        }

    /** Deletes the planned files after the user confirmed. */
    fun execute(
        plan: SmartCleanupPlan,
        executor: StorageDeleteExecutor,
    ): SmartCleanupResult {
        var deleted = 0
        var freed = 0L
        val failed = ArrayList<String>()
        plan.files.forEach { file ->
            if (executor.delete(file.id)) {
                deleted++
                freed += file.sizeBytes
            } else {
                failed.add(file.displayName)
            }
        }
        return SmartCleanupResult(deleted, failed, freed)
    }

    companion object {
        /** The policies from the roadmap's example. */
        fun defaults(): List<CleanupRule> =
            listOf(
                CleanupRule.OlderThan(StorageCategory.CACHE, 30 * DAY_MILLIS),
                CleanupRule.OlderThan(StorageCategory.DIAGNOSTICS, 14 * DAY_MILLIS),
                CleanupRule.FailedTemporaryDownloads,
            )
    }
}

/** Deletes one file; false when it could not be deleted. */
fun interface StorageDeleteExecutor {
    fun delete(fileId: String): Boolean
}

/** The result of executing a cleanup plan. */
data class SmartCleanupResult(
    val deleted: Int,
    val failed: List<String>,
    val freedBytes: Long,
) {
    /** One line for the completion message. */
    fun toDisplayLine(): String =
        "deleted $deleted file(s), freed ${freedBytes / 1024} KiB" +
            if (failed.isEmpty()) "" else ", ${failed.size} failed"
}
