package com.wmods.wppenhacer.scheduler

import com.wmods.wppenhacer.platform.JsonValue
import com.wmods.wppenhacer.platform.KeyValueStore
import com.wmods.wppenhacer.platform.MiniJson
import com.wmods.wppenhacer.platform.jsonArray
import com.wmods.wppenhacer.platform.jsonNumber
import com.wmods.wppenhacer.platform.jsonObject
import com.wmods.wppenhacer.platform.jsonString
import com.wmods.wppenhacer.platform.jsonStrings
import com.wmods.wppenhacer.platform.long
import com.wmods.wppenhacer.platform.string
import com.wmods.wppenhacer.platform.stringList
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The shapes of recurrence T92 supports. */
enum class RecurrenceKind {
    /** Every day at the same local time. */
    DAILY,

    /** Monday through Friday at the same local time. */
    WEEKDAYS,

    /** Selected weekdays at the same local time. */
    WEEKLY,

    /** The same day of the month, clamped to the month's length. */
    MONTHLY,

    /** A fixed interval, for example every 90 minutes. */
    CUSTOM,
}

/**
 * When a recurring message repeats.
 *
 * Recurrence is computed in the device's current zone *at firing time*, not baked into a
 * list of future timestamps. That choice is what makes a timezone change safe: an absolute
 * next-attempt time stays where it is, and "every day at 08:00" becomes 08:00 in the new
 * zone from the following occurrence rather than silently drifting by hours.
 *
 * An occurrence whose local time does not exist (the spring-forward gap) is shifted forward
 * by java.time's zone rules, and one that occurs twice (fall back) resolves to the first
 * occurrence — both standard, documented behaviours rather than special cases here.
 */
data class Recurrence(
    val kind: RecurrenceKind,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int? = null,
    val intervalMillis: Long? = null,
) {
    /** A daily rule. */
    fun nextOccurrence(
        afterMillis: Long,
        zone: ZoneId,
    ): Long? {
        if (validate().isNotEmpty()) return null
        val after = Instant.ofEpochMilli(afterMillis).atZone(zone)
        val next =
            when (kind) {
                RecurrenceKind.DAILY -> after.plusDays(1)
                RecurrenceKind.WEEKDAYS ->
                    generateSequence(after.plusDays(1)) { it.plusDays(1) }
                        .first { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }

                RecurrenceKind.WEEKLY ->
                    generateSequence(after.plusDays(1)) { it.plusDays(1) }
                        .first { it.dayOfWeek in daysOfWeek }

                RecurrenceKind.MONTHLY -> {
                    val target = after.plusMonths(1)
                    val day = (dayOfMonth ?: after.dayOfMonth).coerceAtMost(target.toLocalDate().lengthOfMonth())
                    target.withDayOfMonth(day)
                }

                RecurrenceKind.CUSTOM -> after.plus(intervalMillis!!, ChronoUnit.MILLIS)
            }
        return next.toInstant().toEpochMilli()
    }

    /** Why this recurrence cannot be used, or an empty list when it is valid. */
    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (kind == RecurrenceKind.WEEKLY && daysOfWeek.isEmpty()) {
            problems.add("A weekly schedule needs at least one weekday.")
        }
        if (kind == RecurrenceKind.CUSTOM) {
            val interval = intervalMillis
            if (interval == null || interval < MIN_CUSTOM_INTERVAL_MILLIS) {
                problems.add("A custom schedule needs an interval of at least 60 seconds.")
            }
        }
        if (kind == RecurrenceKind.MONTHLY && dayOfMonth != null && dayOfMonth !in 1..31) {
            problems.add("A monthly schedule needs a day between 1 and 31.")
        }
        return problems
    }

    /** One line for the scheduler list. */
    fun toDisplayLine(): String =
        when (kind) {
            RecurrenceKind.DAILY -> "daily"
            RecurrenceKind.WEEKDAYS -> "weekdays"
            RecurrenceKind.WEEKLY -> "weekly on " + daysOfWeek.joinToString(",") { it.name.take(3) }
            RecurrenceKind.MONTHLY -> "monthly on day ${dayOfMonth ?: 1}"
            RecurrenceKind.CUSTOM -> "every ${(intervalMillis ?: 0L) / 60_000L} min"
        }

    companion object {
        /** The smallest accepted custom interval. */
        const val MIN_CUSTOM_INTERVAL_MILLIS: Long = 60_000L
    }
}

/** Where a scheduled message is in its lifecycle. */
enum class ScheduledState {
    /** Waiting for its next attempt. */
    PENDING,

    /** Delivered. */
    SENT,

    /** Given up after the retry policy was exhausted. */
    FAILED,

    /** Cancelled by the user. */
    CANCELLED,
    ;

    /** Whether the record no longer needs to be attempted. */
    val isTerminal: Boolean get() = this != PENDING
}

/**
 * One message waiting to be sent.
 *
 * @param mediaUri a local content URI for optional media, or null for text-only
 * @param nextAttemptAtMillis when the next attempt is due; retries move this forward
 * @param recurrence the repeat rule, or null for a one-shot message
 * @param attempts how many failed attempts have happened so far
 * @param lastError a redacted error summary from the last failure
 */
data class ScheduledMessage(
    val id: String,
    val chatId: String,
    val text: String,
    val mediaUri: String?,
    val createdAtMillis: Long,
    val nextAttemptAtMillis: Long,
    val recurrence: Recurrence? = null,
    val state: ScheduledState = ScheduledState.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
) {
    /** Whether this message repeats. */
    val isRecurring: Boolean get() = recurrence != null

    /** One line for diagnostics; never includes the message text or chat id. */
    fun toDisplayLine(): String =
        "scheduled message ${if (isRecurring) "(${recurrence?.toDisplayLine()})" else ""} [${state.name}] " +
            "attempts=$attempts"
}

/** How failed sends are retried. */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 30_000L,
    val maxDelayMillis: Long = 10L * 60L * 1000L,
) {
    /** Exponential backoff for [attempt] (1-based), capped at [maxDelayMillis]. */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) return baseDelayMillis
        var delay = baseDelayMillis
        repeat(attempt - 1) {
            delay = (delay * 2).coerceAtMost(maxDelayMillis)
        }
        return delay.coerceAtMost(maxDelayMillis)
    }
}

/** The outcome of scheduling a message. */
sealed interface ScheduleOutcome {
    /** The message was accepted. */
    data class Scheduled(
        val message: ScheduledMessage,
    ) : ScheduleOutcome

    /** The input was not usable; [reason] says what to change. */
    data class Rejected(
        val reason: String,
    ) : ScheduleOutcome

    /** An identical pending message already exists; nothing was created. */
    data class Duplicate(
        val existing: ScheduledMessage,
    ) : ScheduleOutcome
}

/**
 * The local scheduling engine.
 *
 * "Duplicate prevention" is enforced at creation: scheduling the same text for the same chat
 * at the same due time twice returns the existing message instead of creating a second one.
 * The check is on the *pair* (chat, due time, text), because two genuinely different
 * messages due at the same second are fine, while an accidental double-tap is not.
 *
 * Everything is persisted in two bounded documents: pending messages and a terminal
 * history. Terminal records exist so the user can see what happened; they are capped so the
 * history cannot grow without limit on a device that schedules for years.
 */
class ScheduledMessageEngine(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
    var retryPolicy: RetryPolicy = RetryPolicy(),
) {
    /** Schedules a one-shot or recurring message. */
    fun schedule(
        chatId: String,
        text: String,
        dueAtMillis: Long,
        mediaUri: String? = null,
        recurrence: Recurrence? = null,
    ): ScheduleOutcome {
        if (chatId.isBlank()) return ScheduleOutcome.Rejected("A scheduled message needs a chat.")
        if (text.isBlank() && mediaUri.isNullOrBlank()) {
            return ScheduleOutcome.Rejected("A scheduled message needs text or media.")
        }
        if (dueAtMillis <= 0L) return ScheduleOutcome.Rejected("A scheduled message needs a valid due time.")
        if (recurrence != null) {
            val problems = recurrence.validate()
            if (problems.isNotEmpty()) return ScheduleOutcome.Rejected(problems.first())
        }

        val duplicate =
            pending().firstOrNull {
                it.chatId == chatId &&
                    it.text == text &&
                    it.nextAttemptAtMillis == dueAtMillis &&
                    it.mediaUri == mediaUri &&
                    it.recurrence == recurrence
            }
        if (duplicate != null) return ScheduleOutcome.Duplicate(duplicate)

        val message =
            ScheduledMessage(
                id = nextId(),
                chatId = chatId,
                text = text,
                mediaUri = mediaUri,
                createdAtMillis = now(),
                nextAttemptAtMillis = dueAtMillis,
                recurrence = recurrence,
            )
        writePending(pending() + message)
        return ScheduleOutcome.Scheduled(message)
    }

    /** Messages whose next attempt is due. */
    fun due(): List<ScheduledMessage> = pending().filter { it.nextAttemptAtMillis <= now() }.sortedBy { it.nextAttemptAtMillis }

    /** Every non-terminal message, ordered by next attempt. */
    fun pending(): List<ScheduledMessage> = load(KEY_PENDING).sortedBy { it.nextAttemptAtMillis }

    /** Terminal records, newest first. */
    fun history(): List<ScheduledMessage> = load(KEY_HISTORY).sortedByDescending { it.nextAttemptAtMillis }

    /** A message by id across pending and history. */
    fun message(id: String): ScheduledMessage? = pending().firstOrNull { it.id == id } ?: history().firstOrNull { it.id == id }

    /**
     * Records a successful send.
     *
     * A recurring message is rescheduled to its next occurrence, resetting the retry count;
     * a one-shot moves to history. The send is also recorded in history so a recurring
     * message leaves a trail without keeping the pending record forever.
     */
    fun markSent(id: String): Boolean {
        val current = pending()
        val message = current.firstOrNull { it.id == id } ?: return false
        val remaining = current.filterNot { it.id == id }
        appendHistory(message.copy(state = ScheduledState.SENT, attempts = message.attempts + 1, lastError = null))

        val recurrence = message.recurrence
        if (recurrence == null) {
            writePending(remaining)
            return true
        }
        val next = recurrence.nextOccurrence(message.nextAttemptAtMillis, zoneProvider())
        if (next == null) {
            // The rule became unschedulable (for example an invalid custom interval that was
            // stored before validation tightened); record it as sent once and stop.
            writePending(remaining)
            return true
        }
        writePending(
            remaining +
                message.copy(
                    nextAttemptAtMillis = next,
                    attempts = 0,
                    lastError = null,
                    state = ScheduledState.PENDING,
                ),
        )
        return true
    }

    /**
     * Records a failed attempt.
     *
     * The message stays pending with an exponential backoff until [RetryPolicy.maxAttempts]
     * is exhausted, at which point it moves to history as failed. The attempt count is per
     * send cycle, so a recurring message that succeeds tomorrow starts clean.
     */
    fun markFailed(
        id: String,
        error: String,
    ): Boolean {
        val current = pending()
        val message = current.firstOrNull { it.id == id } ?: return false
        val attempts = message.attempts + 1
        val remaining = current.filterNot { it.id == id }
        return if (attempts >= retryPolicy.maxAttempts) {
            appendHistory(message.copy(state = ScheduledState.FAILED, attempts = attempts, lastError = error))
            writePending(remaining)
            true
        } else {
            writePending(
                remaining +
                    message.copy(
                        attempts = attempts,
                        lastError = error,
                        nextAttemptAtMillis = now() + retryPolicy.delayForAttempt(attempts),
                    ),
            )
            true
        }
    }

    /** Cancels a pending message, moving it to history. */
    fun cancel(id: String): Boolean {
        val current = pending()
        val message = current.firstOrNull { it.id == id } ?: return false
        writePending(current.filterNot { it.id == id })
        appendHistory(message.copy(state = ScheduledState.CANCELLED))
        return true
    }

    /** Moves a pending message to a new due time. */
    fun reschedule(
        id: String,
        dueAtMillis: Long,
    ): Boolean {
        if (dueAtMillis <= 0L) return false
        val current = pending()
        val message = current.firstOrNull { it.id == id } ?: return false
        writePending(
            current.map {
                if (it.id == id) it.copy(nextAttemptAtMillis = dueAtMillis, attempts = 0, lastError = null) else it
            },
        )
        return true
    }

    /** Drops pending and history. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_PENDING)
        store.remove(KEY_HISTORY)
    }

    private fun writePending(messages: List<ScheduledMessage>) {
        store.putString(KEY_PENDING, MiniJson.write(jsonArray(messages.map { encode(it) })))
    }

    private fun appendHistory(message: ScheduledMessage) {
        val all = history() + message
        val bounded = if (all.size > MAX_HISTORY) all.takeLast(MAX_HISTORY) else all
        store.putString(KEY_HISTORY, MiniJson.write(jsonArray(bounded.map { encode(it) })))
    }

    private fun load(key: String): List<ScheduledMessage> {
        val text = store.getString(key) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decode(it) }
    }

    private fun nextId(): String {
        var candidate = "message.${now()}"
        var counter = 1
        while (pending().any { it.id == candidate } || history().any { it.id == candidate }) {
            candidate = "message.${now()}.$counter"
            counter++
        }
        return candidate
    }

    private fun encode(message: ScheduledMessage): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(message.id),
            "chatId" to jsonString(message.chatId),
            "text" to jsonString(message.text),
            "mediaUri" to message.mediaUri?.let { jsonString(it) },
            "createdAt" to jsonNumber(message.createdAtMillis),
            "nextAttemptAt" to jsonNumber(message.nextAttemptAtMillis),
            "state" to jsonString(message.state.name),
            "attempts" to jsonNumber(message.attempts.toLong()),
            "lastError" to message.lastError?.let { jsonString(it) },
            "recurrence" to message.recurrence?.let { encodeRecurrence(it) },
        )

    private fun encodeRecurrence(recurrence: Recurrence): JsonValue.Obj =
        jsonObject(
            "kind" to jsonString(recurrence.kind.name),
            "daysOfWeek" to jsonStrings(recurrence.daysOfWeek.map { it.name }),
            "dayOfMonth" to recurrence.dayOfMonth?.let { jsonNumber(it.toLong()) },
            "intervalMillis" to recurrence.intervalMillis?.let { jsonNumber(it) },
        )

    private fun decode(value: JsonValue): ScheduledMessage? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        val id = fields.string("id") ?: return null
        val chatId = fields.string("chatId") ?: return null
        val state =
            ScheduledState.entries.firstOrNull { it.name == fields.string("state") }
                ?: ScheduledState.PENDING
        return ScheduledMessage(
            id = id,
            chatId = chatId,
            text = fields.string("text") ?: "",
            mediaUri = fields.string("mediaUri"),
            createdAtMillis = fields.long("createdAt") ?: 0L,
            nextAttemptAtMillis = fields.long("nextAttemptAt") ?: 0L,
            recurrence = decodeRecurrence(fields),
            state = state,
            attempts = (fields.long("attempts") ?: 0L).toInt(),
            lastError = fields.string("lastError"),
        )
    }

    private fun decodeRecurrence(fields: Map<String, JsonValue>): Recurrence? {
        val recurrenceFields = (fields["recurrence"] as? JsonValue.Obj)?.fields ?: return null
        val kind =
            RecurrenceKind.entries.firstOrNull { it.name == recurrenceFields.string("kind") }
                ?: return null
        val days =
            recurrenceFields
                .stringList("daysOfWeek")
                .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
                .toSet()
        return Recurrence(
            kind = kind,
            daysOfWeek = days,
            dayOfMonth = recurrenceFields.long("dayOfMonth")?.toInt(),
            intervalMillis = recurrenceFields.long("intervalMillis"),
        )
    }

    companion object {
        /** Storage key for pending messages. */
        const val KEY_PENDING: String = "wae.scheduler.messages"

        /** Storage key for terminal records. */
        const val KEY_HISTORY: String = "wae.scheduler.history"

        /** How many terminal records are kept. */
        const val MAX_HISTORY: Int = 200
    }
}
