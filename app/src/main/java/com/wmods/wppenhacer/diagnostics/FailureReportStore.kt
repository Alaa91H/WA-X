package com.wmods.wppenhacer.diagnostics

import android.content.Context
import java.io.File

/**
 * Persists [FeatureFailureReport]s to a single local JSON file.
 *
 * Reports are kept in the module's own storage and never leave the device unless the
 * user explicitly shares them, which is the same posture the module takes for
 * preferences and caches. The file is rewritten in full on every append so a crash
 * during a write cannot leave a half-written document that fails to parse next launch.
 */
class FailureReportStore(
    context: Context,
) {
    private val appContext = context.applicationContext

    private val file: File
        get() = File(appContext.filesDir, FILE_NAME)

    /** Reports currently on disk, newest last. Returns empty when unreadable. */
    fun readAll(): List<FeatureFailureReport> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return FailureReportParser.parseAll(text)
    }

    /**
     * Appends [report], trimming the history to [MAX_REPORTS].
     *
     * The oldest entries are dropped first: a report about a build the user has long
     * since left is noise, and an unbounded file in app storage is its own problem.
     */
    fun append(report: FeatureFailureReport) {
        val retained = (readAll() + report).takeLast(MAX_REPORTS)
        writeAll(retained)
    }

    /** Replaces the stored history. */
    fun writeAll(reports: List<FeatureFailureReport>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(FailureReportCodec.encodeAll(reports))
        }
    }

    /** Deletes all stored reports. */
    fun clear() {
        runCatching { file.delete() }
    }

    /** Number of bytes the history occupies, for the storage dashboard. */
    fun sizeBytes(): Long = runCatching { file.length() }.getOrDefault(0L)

    companion object {
        const val FILE_NAME: String = "feature-failures.json"

        /** History cap. Enough to cover a bad update without growing without bound. */
        const val MAX_REPORTS: Int = 50
    }
}
