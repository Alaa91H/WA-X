package com.wmods.wppenhacer.history

import com.wmods.wppenhacer.platform.JsonValue
import com.wmods.wppenhacer.platform.KeyValueStore
import com.wmods.wppenhacer.platform.MiniJson
import com.wmods.wppenhacer.platform.array
import com.wmods.wppenhacer.platform.jsonArray
import com.wmods.wppenhacer.platform.jsonNumber
import com.wmods.wppenhacer.platform.jsonObject
import com.wmods.wppenhacer.platform.jsonString
import com.wmods.wppenhacer.platform.long
import com.wmods.wppenhacer.platform.string
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Formats the hour and minute of a timeline entry. */
private val TIMELINE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** What one stored version of a message is. */
enum class MessageVersionKind {
    /** The text as first observed. */
    ORIGINAL,

    /** A later edit. */
    EDIT,

    /** The deletion event; carries no text. */
    DELETED,
}

/**
 * One observed version of a message.
 *
 * @param kind original, edit or the deletion marker
 * @param text the message text for original/edit; empty for deletion
 * @param atMillis when the version was observed, epoch millis
 * @param mediaState a short local description of media at that time (for example
 *   "photo"), or null. Never a file path or a URL.
 */
data class MessageVersion(
    val kind: MessageVersionKind,
    val text: String,
    val atMillis: Long,
    val mediaState: String? = null,
)

/**
 * The locally observed history of one message.
 *
 * The roadmap's structure is `Original → Edit 1 → Edit 2 → Latest`, plus a deletion
 * marker, and this type keeps exactly that: an ordered list where the original is always
 * first and any deletion closes it. The entry never tries to reconstruct edits that were
 * not observed — a message edited before the module saw it has one version, which is the
 * honest record.
 */
data class MessageTimelineEntry(
    val messageId: String,
    val chatId: String,
    val versions: List<MessageVersion>,
) {
    /** The first observed version. */
    val original: MessageVersion? get() = versions.firstOrNull { it.kind == MessageVersionKind.ORIGINAL }

    /** The most recent version. */
    val latest: MessageVersion? get() = versions.lastOrNull()

    /** Whether a deletion was observed. */
    val isDeleted: Boolean get() = versions.any { it.kind == MessageVersionKind.DELETED }

    /** When the deletion happened, or null when the message is not deleted. */
    val deletedAtMillis: Long?
        get() = versions.lastOrNull { it.kind == MessageVersionKind.DELETED }?.atMillis

    /** How many edits were observed (excluding the original and the deletion marker). */
    val editCount: Int get() = versions.count { it.kind == MessageVersionKind.EDIT }

    /** The last observed text; for a deleted message this is the text before deletion. */
    val lastKnownText: String?
        get() = versions.lastOrNull { it.kind != MessageVersionKind.DELETED }?.text

    /**
     * The T88 timeline rendering, for example:
     * ```
     * 12:02 Original
     * 12:05 Edited
     * 12:15 Deleted
     * ```
     */
    fun render(zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            versions.forEach { version ->
                append(TIMELINE_TIME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(version.atMillis)))
                append(' ')
                append(
                    when (version.kind) {
                        MessageVersionKind.ORIGINAL -> "Original"
                        MessageVersionKind.EDIT -> "Edited"
                        MessageVersionKind.DELETED -> "Deleted"
                    },
                )
                if (version.mediaState != null && version.kind != MessageVersionKind.DELETED) {
                    append(" (").append(version.mediaState).append(')')
                }
                append('\n')
            }
        }.trimEnd()

    /** One line for a diagnostics list; contains no message text. */
    fun toDisplayLine(): String = "message timeline: ${versions.size} version(s), deleted=$isDeleted"
}

/**
 * Bounded retention for the local timeline.
 *
 * "Local only, bounded retention, configurable cleanup" is the T86 requirement, and a bound
 * has to have real numbers to be enforceable: entries older than [maxAgeMillis] or beyond
 * [maxEntries] are dropped, oldest first. Both values are user-configurable in the UI; the
 * defaults keep roughly a month of edits, which is the window in which people actually look
 * for one.
 */
data class TimelineRetention(
    val maxEntries: Int = 500,
    val maxAgeMillis: Long = 30L * 24L * 60L * 60L * 1000L,
)

/**
 * Records locally observed message versions.
 *
 * Everything here is local: no version, no text and no chat id is ever sent anywhere. The
 * store is a single bounded JSON document rather than a database because the timeline is
 * both small and disposable — if it is lost, the only consequence is that a later edit
 * shows fewer versions, which is acceptable in a way data loss usually is not.
 */
class MessageTimelineStore(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    var retention: TimelineRetention = TimelineRetention(),
) {
    /** Records the first observation of [messageId]. Replaces an existing entry's original. */
    fun recordOriginal(
        messageId: String,
        chatId: String,
        text: String,
        mediaState: String? = null,
    ): MessageTimelineEntry? {
        if (messageId.isBlank() || chatId.isBlank()) return null
        val existing = entry(messageId)
        val versions = existing?.versions.orEmpty()
        if (versions.any { it.kind == MessageVersionKind.ORIGINAL }) {
            // Already known: an observed "first sight" must not overwrite the true original.
            return existing
        }
        val entry =
            MessageTimelineEntry(
                messageId = messageId,
                chatId = chatId,
                versions = versions + MessageVersion(MessageVersionKind.ORIGINAL, text, now(), mediaState),
            )
        save(entry)
        return entry
    }

    /**
     * Records an edit.
     *
     * The original is never lost: once recorded, edits append. If the message was never
     * seen before, the edit's text becomes the original because inventing a version that
     * was never observed would be a lie about history.
     */
    fun recordEdit(
        messageId: String,
        chatId: String,
        text: String,
    ): MessageTimelineEntry? {
        if (messageId.isBlank() || chatId.isBlank()) return null
        val existing = entry(messageId)
        val versions = existing?.versions.orEmpty()
        val updated =
            if (versions.none { it.kind == MessageVersionKind.ORIGINAL }) {
                versions + MessageVersion(MessageVersionKind.ORIGINAL, text, now())
            } else {
                versions + MessageVersion(MessageVersionKind.EDIT, text, now())
            }
        val entry = MessageTimelineEntry(messageId, chatId, boundVersions(updated))
        save(entry)
        return entry
    }

    /** Records a deletion. A second deletion is ignored, preserving the first time. */
    fun recordDeletion(
        messageId: String,
        chatId: String,
    ): MessageTimelineEntry? {
        if (messageId.isBlank() || chatId.isBlank()) return null
        val existing = entry(messageId) ?: return null
        if (existing.isDeleted) return existing
        val entry =
            existing.copy(
                versions = existing.versions + MessageVersion(MessageVersionKind.DELETED, "", now()),
            )
        save(entry)
        return entry
    }

    /** The timeline for one message. */
    fun entry(messageId: String): MessageTimelineEntry? = loadEntries()[messageId]

    /** Timelines for [chatId], most recently changed first. */
    fun timelinesFor(
        chatId: String,
        limit: Int = 50,
    ): List<MessageTimelineEntry> =
        loadEntries()
            .values
            .filter { it.chatId == chatId }
            .sortedByDescending { it.versions.lastOrNull()?.atMillis ?: 0L }
            .take(limit)

    /** The number of stored timelines. */
    fun size(): Int = loadEntries().size

    /** Whether the timeline holds anything for [messageId]. */
    fun hasTimeline(messageId: String): Boolean = entry(messageId) != null

    /**
     * Applies retention and returns how many entries were dropped.
     *
     * Called after every write so the bound cannot be exceeded by a burst of edits, and
     * exposed for the "clean up now" action in storage settings.
     */
    fun prune(): Int {
        val entries = loadEntries()
        val cutoff = now() - retention.maxAgeMillis
        val fresh = entries.values.filter { (it.versions.lastOrNull()?.atMillis ?: 0L) >= cutoff }
        val bounded =
            fresh
                .sortedByDescending { it.versions.lastOrNull()?.atMillis ?: 0L }
                .take(retention.maxEntries)
        val dropped = entries.size - bounded.size
        if (dropped > 0) writeEntries(bounded)
        return dropped
    }

    /** Drops the timeline for one message. */
    fun remove(messageId: String): Boolean {
        val entries = loadEntries()
        if (entries.remove(messageId) == null) return false
        writeEntries(entries.values.toList())
        return true
    }

    /** Drops every timeline. Used by tests and by the privacy cleanup action. */
    fun clear() {
        store.remove(KEY_TIMELINE)
    }

    private fun save(entry: MessageTimelineEntry) {
        val entries = loadEntries()
        entries[entry.messageId] = entry
        writeEntries(entries.values.toList())
        prune()
    }

    private fun loadEntries(): LinkedHashMap<String, MessageTimelineEntry> {
        val text = store.getString(KEY_TIMELINE) ?: return LinkedHashMap()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return LinkedHashMap()
        val entries = LinkedHashMap<String, MessageTimelineEntry>()
        items.forEach { item ->
            val entry = decodeEntry(item) ?: return@forEach
            entries[entry.messageId] = entry
        }
        return entries
    }

    private fun writeEntries(entries: List<MessageTimelineEntry>) {
        store.putString(KEY_TIMELINE, MiniJson.write(jsonArray(entries.map { encodeEntry(it) })))
    }

    /**
     * Keeps an entry bounded: the original is preserved, and the newest versions win.
     *
     * Dropping middle edits rather than old ones keeps both ends of the story — what was
     * first said and what it says now — which is what a user is looking for. The deletion
     * marker is never dropped.
     */
    private fun boundVersions(versions: List<MessageVersion>): List<MessageVersion> {
        if (versions.size <= MAX_VERSIONS_PER_MESSAGE) return versions
        val original = versions.first()
        val deletion = versions.lastOrNull { it.kind == MessageVersionKind.DELETED && it != original }
        val tail =
            versions
                .filter { it != original && it != deletion }
                .takeLast(MAX_VERSIONS_PER_MESSAGE - 1 - if (deletion == null) 0 else 1)
        return buildList {
            add(original)
            addAll(tail)
            if (deletion != null) add(deletion)
        }
    }

    private fun encodeEntry(entry: MessageTimelineEntry): JsonValue.Obj =
        jsonObject(
            "messageId" to jsonString(entry.messageId),
            "chatId" to jsonString(entry.chatId),
            "versions" to
                jsonArray(
                    entry.versions.map { version ->
                        jsonObject(
                            "kind" to jsonString(version.kind.name),
                            "text" to jsonString(version.text),
                            "at" to jsonNumber(version.atMillis),
                            "mediaState" to version.mediaState?.let { jsonString(it) },
                        )
                    },
                ),
        )

    private fun decodeEntry(value: JsonValue): MessageTimelineEntry? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        val messageId = fields.string("messageId") ?: return null
        val chatId = fields.string("chatId") ?: return null
        val versions = fields.array("versions").orEmpty().mapNotNull { decodeVersion(it) }
        if (versions.isEmpty()) return null
        return MessageTimelineEntry(messageId, chatId, versions)
    }

    private fun decodeVersion(value: JsonValue): MessageVersion? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        val kind = MessageVersionKind.entries.firstOrNull { it.name == fields.string("kind") } ?: return null
        return MessageVersion(
            kind = kind,
            text = fields.string("text") ?: "",
            atMillis = fields.long("at") ?: 0L,
            mediaState = fields.string("mediaState"),
        )
    }

    companion object {
        /** Storage key for the timeline document. */
        const val KEY_TIMELINE: String = "wae.history.timeline"

        /** Versions kept per message, including original and deletion marker. */
        const val MAX_VERSIONS_PER_MESSAGE: Int = 20
    }
}
