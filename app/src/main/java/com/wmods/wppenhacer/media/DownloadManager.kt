package com.wmods.wppenhacer.media

import com.wmods.wppenhacer.platform.JsonValue
import com.wmods.wppenhacer.platform.KeyValueStore
import com.wmods.wppenhacer.platform.MiniJson
import com.wmods.wppenhacer.platform.jsonArray
import com.wmods.wppenhacer.platform.jsonNumber
import com.wmods.wppenhacer.platform.jsonObject
import com.wmods.wppenhacer.platform.jsonString
import com.wmods.wppenhacer.platform.long
import com.wmods.wppenhacer.platform.string

/** The states T117 defines. */
enum class DownloadState {
    /** Waiting to start. */
    QUEUED,

    /** In progress. */
    DOWNLOADING,

    /** Finished successfully. */
    COMPLETED,

    /** Given up after the retry limit. */
    FAILED,

    /** Cancelled by the user. */
    CANCELLED,

    /** Waiting to be retried after a transient failure. */
    RETRYING,
    ;

    /** Whether the task still occupies the queue. */
    val isActive: Boolean get() = this == QUEUED || this == DOWNLOADING || this == RETRYING
}

/** One download. */
data class DownloadTask(
    val id: String,
    val mediaId: String,
    val source: String,
    val destination: String,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val attempts: Int,
    val error: String? = null,
    val updatedAtMillis: Long = 0L,
) {
    /** Progress in the range 0..1, or 0 when the total is unknown. */
    val progress: Double
        get() = if (bytesTotal <= 0L) 0.0 else (bytesDownloaded.toDouble() / bytesTotal).coerceIn(0.0, 1.0)

    /** One line for the download list. */
    fun toDisplayLine(): String = "$state ${(progress * 100).toInt()}% ($bytesDownloaded/$bytesTotal bytes, attempts=$attempts)"
}

/**
 * Owns the download queue and its state transitions.
 *
 * The state machine is explicit and validated: only the transitions the roadmap lists exist,
 * and an invalid transition returns false rather than silently corrupting state. Resume data
 * ([DownloadTask.bytesDownloaded]) survives a failure or a retry, so a downloader can do a
 * range request instead of starting over — "safe resume where technically possible" is the
 * caller's decision, but the manager must preserve the information that makes it possible.
 *
 * The queue is persisted: a process death must not silently lose a queued download.
 */
class DownloadManager(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    val maxAttempts: Int = 3,
) {
    /** Adds a task to the queue. Returns null for unusable input. */
    fun enqueue(
        mediaId: String,
        source: String,
        destination: String,
    ): DownloadTask? {
        if (mediaId.isBlank() || source.isBlank() || destination.isBlank()) return null
        if (all().any { it.mediaId == mediaId && it.state.isActive }) return null
        val task =
            DownloadTask(
                id = nextId(),
                mediaId = mediaId,
                source = source,
                destination = destination,
                state = DownloadState.QUEUED,
                bytesDownloaded = 0L,
                bytesTotal = 0L,
                attempts = 0,
                updatedAtMillis = now(),
            )
        write(all() + task)
        return task
    }

    /** Moves a queued or retrying task to downloading. */
    fun start(id: String): Boolean = transition(id, DownloadState.DOWNLOADING, setOf(DownloadState.QUEUED, DownloadState.RETRYING))

    /** Records progress; the total may grow as the server reports it. */
    fun updateProgress(
        id: String,
        bytesDownloaded: Long,
        bytesTotal: Long,
    ): Boolean {
        val task = task(id) ?: return false
        if (task.state != DownloadState.DOWNLOADING) return false
        if (bytesDownloaded < 0L || bytesTotal < 0L) return false
        write(
            all().map {
                if (it.id == id) {
                    it.copy(
                        bytesDownloaded = bytesDownloaded.coerceAtMost(if (bytesTotal > 0) bytesTotal else bytesDownloaded),
                        bytesTotal = maxOf(bytesTotal, it.bytesTotal),
                        updatedAtMillis = now(),
                    )
                } else {
                    it
                }
            },
        )
        return true
    }

    /** Marks a downloading task completed. A task that never started has nothing to complete. */
    fun complete(id: String): Boolean = transition(id, DownloadState.COMPLETED, setOf(DownloadState.DOWNLOADING))

    /**
     * Records a failure.
     *
     * A task with attempts left moves to [DownloadState.RETRYING] and keeps its progress;
     * one that exhausted [maxAttempts] moves to [DownloadState.FAILED].
     */
    fun fail(
        id: String,
        error: String,
    ): DownloadState? {
        val task = task(id) ?: return null
        if (task.state != DownloadState.DOWNLOADING && task.state != DownloadState.RETRYING) return null
        val attempts = task.attempts + 1
        val next = if (attempts >= maxAttempts) DownloadState.FAILED else DownloadState.RETRYING
        write(
            all().map {
                if (it.id == id) {
                    it.copy(state = next, attempts = attempts, error = error, updatedAtMillis = now())
                } else {
                    it
                }
            },
        )
        return next
    }

    /** Cancels a task. Only active tasks can be cancelled. */
    fun cancel(id: String): Boolean {
        val task = task(id) ?: return false
        if (!task.state.isActive) return false
        write(all().map { if (it.id == id) it.copy(state = DownloadState.CANCELLED, updatedAtMillis = now()) else it })
        return true
    }

    /** Retries a failed or cancelled task, keeping its progress for a safe resume. */
    fun retry(id: String): Boolean {
        val task = task(id) ?: return false
        if (task.state != DownloadState.FAILED && task.state != DownloadState.CANCELLED) return false
        write(
            all().map {
                if (it.id == id) {
                    it.copy(state = DownloadState.RETRYING, error = null, updatedAtMillis = now())
                } else {
                    it
                }
            },
        )
        return true
    }

    /** The resume offset for a task, or 0 when it has no usable progress. */
    fun resumePoint(id: String): Long {
        val task = task(id) ?: return 0L
        // A completed download has nothing to resume, and cancelled tasks restart clean.
        return when (task.state) {
            DownloadState.RETRYING, DownloadState.FAILED -> task.bytesDownloaded
            else -> 0L
        }
    }

    /** Active tasks, oldest first. */
    fun active(): List<DownloadTask> = all().filter { it.state.isActive }.sortedBy { it.updatedAtMillis }

    /** Every task. */
    fun all(): List<DownloadTask> {
        val text = store.getString(KEY_TASKS) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decode(it) }
    }

    /** One task by id. */
    fun task(id: String): DownloadTask? = all().firstOrNull { it.id == id }

    /** Drops finished tasks to keep the queue bounded. */
    fun clearFinished(): Int {
        val current = all()
        val remaining = current.filter { it.state.isActive }
        if (remaining.size == current.size) return 0
        write(remaining)
        return current.size - remaining.size
    }

    /** Drops everything. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_TASKS)
    }

    private fun transition(
        id: String,
        target: DownloadState,
        allowedFrom: Set<DownloadState>,
    ): Boolean {
        val task = task(id) ?: return false
        if (task.state !in allowedFrom) return false
        write(all().map { if (it.id == id) it.copy(state = target, updatedAtMillis = now()) else it })
        return true
    }

    private fun nextId(): String {
        var candidate = "download.${now()}"
        var counter = 1
        while (all().any { it.id == candidate }) {
            candidate = "download.${now()}.$counter"
            counter++
        }
        return candidate
    }

    private fun write(tasks: List<DownloadTask>) {
        store.putString(KEY_TASKS, MiniJson.write(jsonArray(tasks.map { encode(it) })))
    }

    private fun encode(task: DownloadTask): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(task.id),
            "mediaId" to jsonString(task.mediaId),
            "source" to jsonString(task.source),
            "destination" to jsonString(task.destination),
            "state" to jsonString(task.state.name),
            "downloaded" to jsonNumber(task.bytesDownloaded),
            "total" to jsonNumber(task.bytesTotal),
            "attempts" to jsonNumber(task.attempts.toLong()),
            "error" to task.error?.let { jsonString(it) },
            "updatedAt" to jsonNumber(task.updatedAtMillis),
        )

    private fun decode(value: JsonValue): DownloadTask? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return DownloadTask(
            id = fields.string("id") ?: return null,
            mediaId = fields.string("mediaId") ?: return null,
            source = fields.string("source") ?: return null,
            destination = fields.string("destination") ?: return null,
            state =
                DownloadState.entries.firstOrNull { it.name == fields.string("state") }
                    ?: DownloadState.QUEUED,
            bytesDownloaded = fields.long("downloaded") ?: 0L,
            bytesTotal = fields.long("total") ?: 0L,
            attempts = (fields.long("attempts") ?: 0L).toInt(),
            error = fields.string("error"),
            updatedAtMillis = fields.long("updatedAt") ?: 0L,
        )
    }

    companion object {
        /** Storage key for the task list. */
        const val KEY_TASKS: String = "wae.media.downloads"
    }
}
