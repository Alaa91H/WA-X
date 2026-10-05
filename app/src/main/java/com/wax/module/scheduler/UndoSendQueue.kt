package com.wax.module.scheduler

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.jsonArray
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.long
import com.wax.module.platform.string

/** The delay options T93 allows. Arbitrary delays are deliberately not offered. */
enum class UndoDelay(
    val seconds: Int,
) {
    THREE(3),
    FIVE(5),
    TEN(10),
    ;

    /** The delay in milliseconds. */
    val millis: Long get() = seconds * 1000L
}

/**
 * A message held for a few seconds, during which it can still be cancelled.
 *
 * The hold is purely local: the message has not been handed to WhatsApp yet, so cancelling
 * is a deletion rather than an attempt to delete something already sent. That distinction
 * is the whole point — the feature must never claim to unsend something that left the
 * device.
 */
data class PendingSend(
    val id: String,
    val chatId: String,
    val text: String,
    val mediaUri: String?,
    val queuedAtMillis: Long,
    val sendAtMillis: Long,
) {
    /** How many milliseconds remain before dispatch at [nowMillis]; zero when due. */
    fun remainingMillis(nowMillis: Long): Long = (sendAtMillis - nowMillis).coerceAtLeast(0L)

    /** One line for diagnostics; the text is not included. */
    fun toDisplayLine(): String = "pending send, due at $sendAtMillis"
}

/**
 * Holds outgoing messages for the configured delay.
 *
 * The queue is persisted so a restart during the delay cannot silently lose a message the
 * user believes was sent; on the next start the message is still pending and dispatches
 * when due. Cancelling before dispatch removes it completely.
 */
class UndoSendQueue(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Queues a message for [delay].
     *
     * @return the queued record, or null when the input is unusable (blank chat, and blank
     *   text with no media)
     */
    fun enqueue(
        chatId: String,
        text: String,
        delay: UndoDelay,
        mediaUri: String? = null,
    ): PendingSend? {
        if (chatId.isBlank()) return null
        if (text.isBlank() && mediaUri.isNullOrBlank()) return null
        val queuedAt = now()
        val send =
            PendingSend(
                id = nextId(),
                chatId = chatId,
                text = text,
                mediaUri = mediaUri,
                queuedAtMillis = queuedAt,
                sendAtMillis = queuedAt + delay.millis,
            )
        write(pending() + send)
        return send
    }

    /** Cancels a queued message. Returns false when it was already dispatched or unknown. */
    fun cancel(id: String): Boolean {
        val current = pending()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        write(remaining)
        return true
    }

    /** Messages whose delay has elapsed and which are ready to be handed to WhatsApp. */
    fun due(): List<PendingSend> = pending().filter { it.sendAtMillis <= now() }.sortedBy { it.sendAtMillis }

    /** Removes a message after it has been handed off. */
    fun markDispatched(id: String): Boolean {
        val current = pending()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        write(remaining)
        return true
    }

    /** Every held message, ordered by dispatch time. */
    fun pending(): List<PendingSend> {
        val text = store.getString(KEY_PENDING) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decode(it) }.sortedBy { it.sendAtMillis }
    }

    /** Drops the queue. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_PENDING)
    }

    private fun write(sends: List<PendingSend>) {
        store.putString(KEY_PENDING, MiniJson.write(jsonArray(sends.map { encode(it) })))
    }

    private fun nextId(): String {
        var candidate = "send.${now()}"
        var counter = 1
        while (pending().any { it.id == candidate }) {
            candidate = "send.${now()}.$counter"
            counter++
        }
        return candidate
    }

    private fun encode(send: PendingSend): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(send.id),
            "chatId" to jsonString(send.chatId),
            "text" to jsonString(send.text),
            "mediaUri" to send.mediaUri?.let { jsonString(it) },
            "queuedAt" to jsonNumber(send.queuedAtMillis),
            "sendAt" to jsonNumber(send.sendAtMillis),
        )

    private fun decode(value: JsonValue): PendingSend? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return PendingSend(
            id = fields.string("id") ?: return null,
            chatId = fields.string("chatId") ?: return null,
            text = fields.string("text") ?: "",
            mediaUri = fields.string("mediaUri"),
            queuedAtMillis = fields.long("queuedAt") ?: 0L,
            sendAtMillis = fields.long("sendAt") ?: 0L,
        )
    }

    companion object {
        /** Storage key for the held-send queue. */
        const val KEY_PENDING: String = "wae.scheduler.undo_send"
    }
}
