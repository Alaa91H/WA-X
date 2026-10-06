package com.wax.module.outgoing

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.TargetApp
import com.wax.module.platform.array
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.long
import com.wax.module.platform.objOrNull
import com.wax.module.platform.string

/**
 * Every state a pending revocation can be in.
 *
 * The list is long on purpose. A single boolean "done" would collapse "deleted for everyone",
 * "the message was already gone" and "the delete request failed" into one value, and the
 * interface would then either claim a success WA X cannot verify or report a failure that
 * never happened. [isSuccessful] is true for exactly the two states that represent a deletion
 * WA X actually observed, so a screen cannot invent a third way to say "deleted".
 */
enum class RevocationState(
    /** Whether the job is finished and will not be attempted again. */
    val isTerminal: Boolean,
    /** Whether the message is genuinely gone for everyone. */
    val isSuccessful: Boolean,
    /** The sentence to display. */
    val explanation: String,
) {
    /** Waiting for its deadline. */
    PENDING(false, false, "Waiting for its deadline."),

    /** The delete-for-everyone request succeeded. */
    DELETED_FOR_EVERYONE(true, true, "Deleted for everyone."),

    /** The user deleted the message by hand before the deadline. */
    CANCELLED(true, false, "Cancelled: the message was deleted by hand."),

    /** The message was already gone when the deadline arrived. */
    ALREADY_DELETED(true, true, "Already deleted."),

    /** The deadline is past the window this WhatsApp version still allows. */
    REVOKE_WINDOW_EXPIRED(true, false, "This WhatsApp version no longer allows deleting the message for everyone."),

    /** The app was not running, or not reachable, when the deadline arrived. */
    TARGET_UNAVAILABLE(true, false, "The app was not available, so the message was left as it is."),

    /** This WhatsApp version cannot revoke the message. */
    UNSUPPORTED_VERSION(true, false, "This WhatsApp version cannot delete the message for everyone."),

    /** The sent message could not be identified, so there was nothing to address. */
    IDENTITY_UNAVAILABLE(true, false, "The sent message could not be identified."),

    /** The request failed and the retry budget is spent. */
    REQUEST_FAILED(true, false, "The delete request failed repeatedly."),

    /** The request returned something WA X does not understand. */
    UNKNOWN_RESULT(true, false, "The delete request returned an unknown result."),
}

/**
 * The identity of a message that may need revoking.
 *
 * Only identifiers, never content: the queue exists to address a deletion, so the body, the
 * media bytes and anything that could reconstruct the message have no reason to be in it. The
 * chat identifier is the one the module already uses for this conversation, not a number
 * extracted from it.
 */
data class RevocationKey(
    val target: TargetApp,
    val accountId: String?,
    val chatId: String,
    val messageId: String,
) {
    /** The stable string used as the job id and as its storage key. */
    val id: String get() = "${target.code}|${accountId ?: "-"}|$chatId|$messageId"
}

/**
 * One scheduled Delete for Everyone.
 *
 * [sentAt] is the moment the send was *acknowledged*, not the moment the button was pressed:
 * starting the countdown before the message exists would revoke nothing, or revoke the wrong
 * message, on a slow or failed send.
 */
data class PendingMessageRevocation(
    val key: RevocationKey,
    val messageClass: OutgoingMessageClass,
    /** When the send was acknowledged. The countdown starts here. */
    val sentAt: Long,
    /** When the revocation is due. */
    val deleteAt: Long,
    /** Which scope's policy created this job, for the audit log. */
    val policySource: PolicyScope,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val state: RevocationState = RevocationState.PENDING,
    /** A short machine-readable reason for the last failure. Never message content. */
    val failureReason: String? = null,
) {
    /** The target this job belongs to. */
    val target: TargetApp get() = key.target

    /** Whether the job is finished. */
    val isTerminal: Boolean get() = state.isTerminal

    /** One line for diagnostics and the audit log; contains no user data. */
    fun toDisplayLine(): String = "${key.target.code} ${messageClass.name} ${state.name} (attempts=$attemptCount)"
}

/** The measured outcome of one attempt to delete a message for everyone. */
sealed interface RevocationAttemptResult {
    /** WhatsApp confirmed the message is gone for everyone. */
    data object DeletedForEveryone : RevocationAttemptResult

    /** The message was already deleted when the attempt ran. */
    data object AlreadyDeleted : RevocationAttemptResult

    /** The sent message's identity could not be resolved. */
    data object IdentityUnavailable : RevocationAttemptResult

    /** The app was not available to perform the request. */
    data object TargetUnavailable : RevocationAttemptResult

    /** This version cannot revoke the message. */
    data object UnsupportedVersion : RevocationAttemptResult

    /** The request failed and may be retried. [reason] is machine-readable, not content. */
    data class Failed(
        val reason: String,
    ) : RevocationAttemptResult

    /** The request returned something WA X cannot classify. */
    data object Unknown : RevocationAttemptResult
}

/** Safety limits applied to every user equally. Not an availability gate. */
data class RevocationLimits(
    /** The most revocations one chat may schedule inside the window. */
    val perChatPerWindow: Int = 20,
    /** The most revocations the whole target may schedule inside the window. */
    val perTargetPerWindow: Int = 300,
    /** The most attempts one job gets before it is failed for good. */
    val maxAttempts: Int = 5,
    /** The window the two rate limits are counted over. */
    val windowMillis: Long = 60 * 60 * 1000L,
)

/** The outcome of asking the queue to schedule a revocation. */
sealed interface EnqueueResult {
    /** A job was created. */
    data class Scheduled(
        val job: PendingMessageRevocation,
    ) : EnqueueResult

    /** The policy did not ask for a revocation, so none was created. */
    data class NotRequired(
        val block: AutoDeleteBlockReason? = null,
    ) : EnqueueResult

    /** A job for this message already exists, so nothing was replaced. */
    data object DuplicateId : EnqueueResult

    /** The per-chat safety limit was reached. */
    data object ChatRateLimited : EnqueueResult

    /** The per-target safety limit was reached. */
    data object TargetRateLimited : EnqueueResult

    /** The send was acknowledged without a stable message identity. */
    data object IdentityUnavailable : EnqueueResult

    /** Whether a job exists because of this call. */
    val isScheduled: Boolean get() = this is Scheduled
}

/** What happened to the send that would have created a job. */
sealed interface SendOutcome {
    /** The send was acknowledged and the message has a stable identity. */
    data class Sent(
        val messageId: String,
        val acknowledgedAt: Long,
    ) : SendOutcome

    /** The send failed. No revocation may be scheduled. */
    data object Failed : SendOutcome

    /** The send was cancelled before it happened. No revocation may be scheduled. */
    data object CancelledBeforeSend : SendOutcome

    /**
     * The send was acknowledged but no stable identity could be read on this version.
     *
     * Recorded rather than guessed at: scheduling a revocation against an identity the module
     * cannot name is how a delete request lands on the wrong message.
     */
    data class SentWithoutIdentity(
        val acknowledgedAt: Long,
    ) : SendOutcome
}

/**
 * The durable queue behind Timed Auto Delete for Everyone.
 *
 * Every rule the feature needs that is not a decision about policy lives here, because they
 * are all properties of a queue: only one job per message, a bounded retry budget with
 * backoff, safety limits so a mistake cannot become a flood, and per-target separation so
 * WhatsApp's queue and Business's queue cannot be walked by the same loop.
 *
 * The store is written through on every change instead of being flushed periodically, because
 * the failure it must survive is the process dying: a job that only existed in memory would
 * vanish exactly when it was needed. Nothing here holds message content, so a persisted job is
 * a few identifiers and a timestamp.
 */
class MessageRevocationQueue(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val limits: RevocationLimits = RevocationLimits(),
) {
    /**
     * Schedules a revocation for a send that already succeeded.
     *
     * Callers must route every outgoing path through here rather than scheduling from the
     * Send action, so the timer starts from an acknowledgement.
     */
    fun onSendCompleted(
        request: RevocationRequest,
        decision: AutoDeleteDecision,
        outcome: SendOutcome,
    ): EnqueueResult =
        when (outcome) {
            // No acknowledgement, no message, no deletion job: a revocation against a message
            // that was never sent would at best do nothing and at worst address a later one.
            is SendOutcome.Failed, SendOutcome.CancelledBeforeSend -> EnqueueResult.NotRequired(decision.block)
            is SendOutcome.SentWithoutIdentity -> EnqueueResult.IdentityUnavailable
            is SendOutcome.Sent -> enqueue(request, decision, outcome.messageId, outcome.acknowledgedAt)
        }

    /**
     * Creates a job for an acknowledged send.
     *
     * Idempotent per message: a second call for the same message returns [EnqueueResult.DuplicateId]
     * and leaves the existing job, and its original deadline, untouched. That is what makes an
     * edit or a retried send unable to create a second deletion.
     */
    fun enqueue(
        request: RevocationRequest,
        decision: AutoDeleteDecision,
        messageId: String,
        acknowledgedAt: Long,
    ): EnqueueResult {
        if (!decision.schedule) return EnqueueResult.NotRequired(decision.block)
        if (messageId.isBlank()) return EnqueueResult.IdentityUnavailable
        if (request.chatId.isBlank()) return EnqueueResult.IdentityUnavailable
        val key = RevocationKey(request.target, request.accountId, request.chatId, messageId)
        if (job(key.id) != null) return EnqueueResult.DuplicateId
        if (countInWindow(chatBucket(key)) >= limits.perChatPerWindow) return EnqueueResult.ChatRateLimited
        if (countInWindow(targetBucket(request.target)) >= limits.perTargetPerWindow) return EnqueueResult.TargetRateLimited
        val job =
            PendingMessageRevocation(
                key = key,
                messageClass = request.messageClass,
                sentAt = acknowledgedAt,
                deleteAt = acknowledgedAt + decision.delayMillis,
                policySource = decision.source,
            )
        store.putString(jobKey(key.id), RevocationCodec.encodeJob(job))
        bump(chatBucket(key))
        bump(targetBucket(request.target))
        return EnqueueResult.Scheduled(job)
    }

    /**
     * Cancels a pending job because the message was deleted by hand.
     *
     * @return true when a pending job was cancelled. A finished job is left as it is, so a
     *   later manual deletion cannot rewrite a recorded outcome
     */
    fun cancel(
        target: TargetApp,
        accountId: String?,
        chatId: String,
        messageId: String,
    ): Boolean {
        val key = RevocationKey(target, accountId, chatId, messageId)
        val job = job(key.id) ?: return false
        if (job.isTerminal) return false
        write(job.copy(state = RevocationState.CANCELLED))
        return true
    }

    /** The job due now or earlier for [target], in deadline order. */
    fun due(
        target: TargetApp,
        at: Long = now(),
    ): List<PendingMessageRevocation> =
        active(target)
            .filter { it.deleteAt <= at }
            .sortedWith(compareBy({ it.deleteAt }, { it.key.id }))

    /** The next job that will be due for [target], or null when there is none. */
    fun nextDue(target: TargetApp): PendingMessageRevocation? = active(target).minWithOrNull(compareBy({ it.deleteAt }, { it.key.id }))

    /** Every unfinished job for [target], oldest deadline first. */
    fun active(target: TargetApp): List<PendingMessageRevocation> =
        allJobs()
            .filter { it.target == target && !it.isTerminal }
            .sortedWith(compareBy({ it.deleteAt }, { it.key.id }))

    /** Finished jobs for [target], newest first. This is the audit log. */
    fun history(
        target: TargetApp,
        limit: Int = 100,
    ): List<PendingMessageRevocation> =
        allJobs()
            .filter { it.target == target && it.isTerminal }
            .sortedByDescending { it.lastAttemptAt ?: it.deleteAt }
            .take(limit)

    /** Every job, both targets, for diagnostics. Never used to perform a deletion. */
    fun allJobs(): List<PendingMessageRevocation> =
        store.keys(JOB_PREFIX).mapNotNull { key ->
            val id = key.removePrefix(JOB_PREFIX).ifBlank { return@mapNotNull null }
            job(id)
        }

    /**
     * Records the measured result of one attempt.
     *
     * Retryable failures consume the budget and are rescheduled with backoff; everything else
     * lands in a terminal state that says what happened. The rule that matters is that no path
     * reaches [RevocationState.DELETED_FOR_EVERYONE] without the client having said so.
     *
     * @return the job as it now stands, or null when there is no job with that key
     */
    fun recordAttempt(
        id: String,
        result: RevocationAttemptResult,
        at: Long = now(),
    ): PendingMessageRevocation? {
        val job = job(id) ?: return null
        // Idempotency: a result arriving twice for a finished job is ignored rather than
        // turning a recorded failure into a success or the other way round.
        if (job.isTerminal) return job
        val updated =
            when (result) {
                RevocationAttemptResult.DeletedForEveryone ->
                    job.copy(state = RevocationState.DELETED_FOR_EVERYONE, lastAttemptAt = at, failureReason = null)

                RevocationAttemptResult.AlreadyDeleted ->
                    job.copy(state = RevocationState.ALREADY_DELETED, lastAttemptAt = at, failureReason = null)

                RevocationAttemptResult.IdentityUnavailable ->
                    job.copy(state = RevocationState.IDENTITY_UNAVAILABLE, lastAttemptAt = at)

                RevocationAttemptResult.TargetUnavailable ->
                    job.copy(state = RevocationState.TARGET_UNAVAILABLE, lastAttemptAt = at)

                RevocationAttemptResult.UnsupportedVersion ->
                    job.copy(state = RevocationState.UNSUPPORTED_VERSION, lastAttemptAt = at)

                RevocationAttemptResult.Unknown ->
                    job.copy(state = RevocationState.UNKNOWN_RESULT, lastAttemptAt = at)

                is RevocationAttemptResult.Failed -> retryOrFail(job, result.reason, at)
            }
        write(updated)
        return updated
    }

    /** Marks a job as having missed the client's revocation window. Never a success. */
    fun recordWindowExpired(
        id: String,
        at: Long = now(),
    ): PendingMessageRevocation? {
        val job = job(id) ?: return null
        if (job.isTerminal) return job
        val updated = job.copy(state = RevocationState.REVOKE_WINDOW_EXPIRED, lastAttemptAt = at)
        write(updated)
        return updated
    }

    /**
     * The delay before the next attempt for a job that has already failed [attempts] times.
     *
     * Exponential with a ceiling, so a burst of failures does not turn into a burst of
     * requests, and a job that keeps failing stops being retried within the hour rather than
     * hammering the client.
     */
    fun backoffMillis(attempts: Int): Long {
        if (attempts <= 0) return 0L
        var delay = BASE_BACKOFF_MILLIS
        repeat(attempts - 1) { delay = (delay * 2).coerceAtMost(MAX_BACKOFF_MILLIS) }
        return delay.coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /**
     * Drops entries the codec cannot read and the oldest finished jobs.
     *
     * Both halves matter: an unreadable entry is dead weight that would be scanned on every
     * `due` call forever, and completed history grows with every message the user sends.
     */
    fun pruneCompleted(keep: Int = 200) {
        store.keys(JOB_PREFIX).forEach { key ->
            val id = key.removePrefix(JOB_PREFIX)
            if (id.isBlank() || job(id) == null) store.remove(key)
        }
        val terminal = allJobs().filter { it.isTerminal }
        if (terminal.size <= keep) return
        terminal
            .sortedByDescending { it.lastAttemptAt ?: it.deleteAt }
            .drop(keep)
            .forEach { store.remove(jobKey(it.key.id)) }
    }

    /** Encodes every job so it can be restored verbatim after a process restart. */
    fun exportJobs(): String = RevocationCodec.encodeQueue(allJobs())

    /**
     * Restores jobs from [text], replacing the queue's contents.
     *
     * Structural validation lives in the codec: a job with no target, no identity or an
     * unknown state is dropped rather than restored, so a corrupt backup cannot schedule a
     * deletion against a message the module cannot describe.
     *
     * @return the number of jobs restored
     */
    fun restoreJobs(text: String): Int {
        val restored = RevocationCodec.decodeQueue(text)
        store.keys(JOB_PREFIX).forEach { store.remove(it) }
        restored.forEach { store.putString(jobKey(it.key.id), RevocationCodec.encodeJob(it)) }
        return restored.size
    }

    /** Removes every job and every rate-limit counter. Used by tests and a factory reset. */
    fun clear() {
        store.keys(JOB_PREFIX).forEach { store.remove(it) }
        store.keys(RATE_PREFIX).forEach { store.remove(it) }
    }

    private fun retryOrFail(
        job: PendingMessageRevocation,
        reason: String,
        at: Long,
    ): PendingMessageRevocation {
        val attempts = job.attemptCount + 1
        return if (attempts >= limits.maxAttempts) {
            job.copy(attemptCount = attempts, lastAttemptAt = at, state = RevocationState.REQUEST_FAILED, failureReason = reason)
        } else {
            job.copy(
                attemptCount = attempts,
                lastAttemptAt = at,
                // The deadline moves out by the backoff, so the next due() call sees it as a
                // fresh attempt rather than something that is due immediately and forever.
                deleteAt = at + backoffMillis(attempts),
                failureReason = reason,
            )
        }
    }

    private fun job(id: String): PendingMessageRevocation? {
        val text = store.getString(jobKey(id)) ?: return null
        // A job must never be restored onto the wrong target: the id carries the target, and a
        // mismatch means the entry was hand-edited or written by a version that disagreed.
        val decoded = RevocationCodec.decodeJob(text) ?: return null
        if (decoded.key.id != id) return null
        return decoded
    }

    private fun write(job: PendingMessageRevocation) {
        store.putString(jobKey(job.key.id), RevocationCodec.encodeJob(job))
    }

    private fun countInWindow(bucket: String): Long {
        val raw = store.getString(RATE_PREFIX + bucket) ?: return 0L
        val windowStart = raw.substringBefore(':').toLongOrNull() ?: return 0L
        val count = raw.substringAfter(':', "0").toLongOrNull() ?: return 0L
        if (now() - windowStart >= limits.windowMillis) return 0L
        return count
    }

    private fun bump(bucket: String) {
        val raw = store.getString(RATE_PREFIX + bucket)
        val windowStart = raw?.substringBefore(':')?.toLongOrNull() ?: 0L
        val count = raw?.substringAfter(':')?.toLongOrNull() ?: 0L
        val current = now()
        if (raw == null || current - windowStart >= limits.windowMillis) {
            store.putString(RATE_PREFIX + bucket, "$current:1")
        } else {
            store.putString(RATE_PREFIX + bucket, "$windowStart:${count + 1}")
        }
    }

    private fun chatBucket(key: RevocationKey): String = "chat.${key.target.code}.${key.chatId}"

    private fun targetBucket(target: TargetApp): String = "target.${target.code}"

    private fun jobKey(id: String): String = JOB_PREFIX + id

    companion object {
        /** Prefix for stored jobs. */
        const val JOB_PREFIX: String = "wae.revoke.job."

        /** Prefix for the safety-limit counters. */
        const val RATE_PREFIX: String = "wae.revoke.rate."

        /** The first retry wait. */
        const val BASE_BACKOFF_MILLIS: Long = 30 * AutoDeleteDurations.SECOND

        /** The longest a retry ever waits. */
        const val MAX_BACKOFF_MILLIS: Long = 15 * AutoDeleteDurations.MINUTE
    }
}

/** The message a revocation is being scheduled for. */
data class RevocationRequest(
    val target: TargetApp,
    val chatId: String,
    val messageClass: OutgoingMessageClass,
    val accountId: String? = null,
)

/**
 * The storage format for pending revocations.
 *
 * Hand-written and total, like every other persisted format in the platform: parsing never
 * throws and never half-succeeds, and anything that does not decode completely is dropped.
 * Dropping is the correct direction of failure here — a job WA X cannot fully describe is a
 * job it might otherwise point at the wrong message.
 */
object RevocationCodec {
    /** The schema version written into every document. */
    const val SCHEMA_VERSION: Int = 1

    /** Encodes one job. */
    fun encodeJob(job: PendingMessageRevocation): String =
        MiniJson.write(
            jsonObject(
                "v" to jsonNumber(SCHEMA_VERSION.toLong()),
                "target" to jsonString(job.key.target.code),
                "accountId" to job.key.accountId?.let { jsonString(it) },
                "chatId" to jsonString(job.key.chatId),
                "messageId" to jsonString(job.key.messageId),
                "class" to jsonString(job.messageClass.name),
                "sentAt" to jsonNumber(job.sentAt),
                "deleteAt" to jsonNumber(job.deleteAt),
                "policySource" to jsonString(job.policySource.code),
                "attempts" to jsonNumber(job.attemptCount.toLong()),
                "lastAttempt" to job.lastAttemptAt?.let { jsonNumber(it) },
                "state" to jsonString(job.state.name),
                "failure" to job.failureReason?.let { jsonString(it) },
            ),
        )

    /** Decodes one job, or null when the document is not a complete, valid job. */
    fun decodeJob(text: String?): PendingMessageRevocation? {
        val fields = MiniJson.parse(text)?.objOrNull() ?: return null
        val target = TargetApp.fromCode(fields.string("target")) ?: return null
        val chatId = fields.string("chatId")?.takeIf { it.isNotBlank() } ?: return null
        val messageId = fields.string("messageId")?.takeIf { it.isNotBlank() } ?: return null
        val messageClass =
            OutgoingMessageClass.entries.firstOrNull { it.name == fields.string("class") } ?: return null
        val state = RevocationState.entries.firstOrNull { it.name == fields.string("state") } ?: return null
        val sentAt = fields.long("sentAt") ?: return null
        val deleteAt = fields.long("deleteAt") ?: return null
        return PendingMessageRevocation(
            key = RevocationKey(target, fields.string("accountId"), chatId, messageId),
            messageClass = messageClass,
            sentAt = sentAt,
            deleteAt = deleteAt,
            policySource = PolicyScope.parse(fields.string("policySource")) ?: PolicyScope.Global,
            attemptCount = (fields.long("attempts") ?: 0L).toInt(),
            lastAttemptAt = fields.long("lastAttempt"),
            state = state,
            failureReason = fields.string("failure"),
        )
    }

    /** Encodes a whole queue. */
    fun encodeQueue(jobs: List<PendingMessageRevocation>): String =
        MiniJson.write(
            jsonObject(
                "v" to jsonNumber(SCHEMA_VERSION.toLong()),
                "jobs" to JsonValue.Arr(jobs.map { MiniJson.parse(encodeJob(it)) ?: JsonValue.Null }),
            ),
        )

    /** Decodes a whole queue, dropping entries that do not decode. */
    fun decodeQueue(text: String?): List<PendingMessageRevocation> {
        val fields = MiniJson.parse(text)?.objOrNull() ?: return emptyList()
        val items = fields.array("jobs") ?: return emptyList()
        return items.mapNotNull { item -> decodeJob(MiniJson.write(item)) }
    }

    /** Whether the document is a revocation queue this build understands. */
    fun isReadable(text: String?): Boolean {
        val fields = MiniJson.parse(text)?.objOrNull() ?: return false
        return fields.long("v") == SCHEMA_VERSION.toLong() && fields.array("jobs") != null
    }
}
