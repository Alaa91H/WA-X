package com.wmods.wppenhacer.history

import com.wmods.wppenhacer.platform.JsonValue
import com.wmods.wppenhacer.platform.KeyValueStore
import com.wmods.wppenhacer.platform.MiniJson
import com.wmods.wppenhacer.platform.jsonArray
import com.wmods.wppenhacer.platform.jsonNumber
import com.wmods.wppenhacer.platform.jsonObject
import com.wmods.wppenhacer.platform.jsonString
import com.wmods.wppenhacer.platform.long
import com.wmods.wppenhacer.platform.string

/**
 * A private note attached to a message.
 *
 * Notes exist to answer "what was I supposed to do about this?" — so they are local, never
 * synced, and never included in any export that leaves the device unless the user exports
 * them deliberately. The model stores the message and chat ids only to be able to show the
 * note in context again; nothing here is transmitted.
 */
data class MessageNote(
    val messageId: String,
    val chatId: String,
    val text: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    /** One line for a diagnostics list; the note text is not included. */
    fun toDisplayLine(): String = "note (${text.length} chars), updated $updatedAtMillis"
}

/**
 * Stores message notes.
 *
 * One note per message: setting a second note replaces the first, because two notes on one
 * message would make "the note" ambiguous everywhere it is displayed. A blank text removes
 * the note, which gives the UI a single "save or clear" action without a separate delete.
 */
class MessageNoteStore(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Sets or replaces the note for a message.
     *
     * @return the stored note, or null when the message id is invalid or [text] was blank
     *   (which removes any note)
     */
    fun setNote(
        messageId: String,
        chatId: String,
        text: String,
    ): MessageNote? {
        if (messageId.isBlank() || chatId.isBlank()) return null
        if (text.isBlank()) {
            removeNote(messageId)
            return null
        }
        val existing = noteFor(messageId)
        val note =
            MessageNote(
                messageId = messageId,
                chatId = chatId,
                text = text.trim(),
                createdAtMillis = existing?.createdAtMillis ?: now(),
                updatedAtMillis = now(),
            )
        write(notes().filterNot { it.messageId == messageId } + note)
        return note
    }

    /** The note for [messageId], or null. */
    fun noteFor(messageId: String): MessageNote? = notes().firstOrNull { it.messageId == messageId }

    /** Whether [messageId] has a note. */
    fun hasNote(messageId: String): Boolean = noteFor(messageId) != null

    /** All notes, newest first. */
    fun notes(): List<MessageNote> {
        val text = store.getString(KEY_NOTES) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decode(it) }
    }

    /** Removes the note for a message. */
    fun removeNote(messageId: String): Boolean {
        val current = notes()
        val remaining = current.filterNot { it.messageId == messageId }
        if (remaining.size == current.size) return false
        write(remaining)
        return true
    }

    /** The number of stored notes. */
    fun count(): Int = notes().size

    /** Drops every note. Used by tests and by the privacy cleanup action. */
    fun clear() {
        store.remove(KEY_NOTES)
    }

    private fun write(notes: List<MessageNote>) {
        store.putString(KEY_NOTES, MiniJson.write(jsonArray(notes.map { encode(it) })))
    }

    private fun encode(note: MessageNote): JsonValue.Obj =
        jsonObject(
            "messageId" to jsonString(note.messageId),
            "chatId" to jsonString(note.chatId),
            "text" to jsonString(note.text),
            "createdAt" to jsonNumber(note.createdAtMillis),
            "updatedAt" to jsonNumber(note.updatedAtMillis),
        )

    private fun decode(value: JsonValue): MessageNote? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return MessageNote(
            messageId = fields.string("messageId") ?: return null,
            chatId = fields.string("chatId") ?: return null,
            text = fields.string("text") ?: return null,
            createdAtMillis = fields.long("createdAt") ?: 0L,
            updatedAtMillis = fields.long("updatedAt") ?: 0L,
        )
    }

    companion object {
        /** Storage key for the note array. */
        const val KEY_NOTES: String = "wae.history.notes"
    }
}

/** A bookmark collection such as Work, Important or Receipts. */
data class BookmarkCollection(
    val id: String,
    val name: String,
    val builtIn: Boolean,
) {
    /** One line for the collection picker. */
    fun toDisplayLine(): String = name
}

/** One message saved into one collection. */
data class Bookmark(
    val messageId: String,
    val chatId: String,
    val collectionId: String,
    val addedAtMillis: Long,
)

/** The outcome of a collection operation. */
sealed interface CollectionResult {
    /** The operation completed. */
    data class Success(
        val collection: BookmarkCollection,
    ) : CollectionResult

    /** The operation was refused; [message] explains what to change. */
    data class Rejected(
        val message: String,
    ) : CollectionResult

    /** No collection with that id exists. */
    data class NotFound(
        val id: String,
    ) : CollectionResult
}

/**
 * Stores bookmark collections and their members.
 *
 * The five built-in collections cover the examples in T90 and can be used immediately.
 * They cannot be renamed or deleted so a bookmark always has somewhere to go back to; user
 * collections can be created, renamed and deleted, with deletion refused while members
 * remain — silently moving or discarding someone's bookmarks would be worse than asking.
 */
class BookmarkStore(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Built-ins followed by custom collections in creation order. */
    fun collections(): List<BookmarkCollection> = BuiltInCollections.all + customCollections()

    /** The collection with [id], or null. */
    fun collection(id: String): BookmarkCollection? = collections().firstOrNull { it.id == id }

    /** Creates a custom collection. */
    fun createCollection(name: String): CollectionResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return CollectionResult.Rejected("A collection name cannot be empty.")
        }
        if (trimmed.length > MAX_NAME_LENGTH) {
            return CollectionResult.Rejected("A collection name must be at most $MAX_NAME_LENGTH characters.")
        }
        if (collections().any { it.name.equals(trimmed, ignoreCase = true) }) {
            return CollectionResult.Rejected("A collection named \"$trimmed\" already exists.")
        }
        val collection =
            BookmarkCollection(
                id = nextCustomId(),
                name = trimmed,
                builtIn = false,
            )
        writeCustom(customCollections() + collection)
        return CollectionResult.Success(collection)
    }

    /** Renames a custom collection. */
    fun renameCollection(
        id: String,
        name: String,
    ): CollectionResult {
        val collection = collection(id) ?: return CollectionResult.NotFound(id)
        if (collection.builtIn) {
            return CollectionResult.Rejected("\"${collection.name}\" is a built-in collection and cannot be renamed.")
        }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return CollectionResult.Rejected("A collection name cannot be empty.")
        if (collections().any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) {
            return CollectionResult.Rejected("A collection named \"$trimmed\" already exists.")
        }
        val renamed = collection.copy(name = trimmed)
        writeCustom(customCollections().map { if (it.id == id) renamed else it })
        return CollectionResult.Success(renamed)
    }

    /** Deletes an empty custom collection. */
    fun deleteCollection(id: String): CollectionResult {
        val collection = collection(id) ?: return CollectionResult.NotFound(id)
        if (collection.builtIn) {
            return CollectionResult.Rejected("\"${collection.name}\" is a built-in collection and cannot be deleted.")
        }
        val members = bookmarks(id).size
        if (members > 0) {
            return CollectionResult.Rejected(
                "\"${collection.name}\" still holds $members bookmark(s). Remove them first.",
            )
        }
        writeCustom(customCollections().filterNot { it.id == id })
        return CollectionResult.Success(collection)
    }

    /**
     * Bookmarks a message into a collection.
     *
     * @return false when the ids are invalid, the collection does not exist, or the message
     *   is already in that collection — adding twice is a no-op, not a duplicate
     */
    fun addBookmark(
        messageId: String,
        chatId: String,
        collectionId: String,
    ): Boolean {
        if (messageId.isBlank() || chatId.isBlank()) return false
        if (collection(collectionId) == null) return false
        val current = bookmarks()
        if (current.any { it.messageId == messageId && it.collectionId == collectionId }) return false
        writeBookmarks(
            current +
                Bookmark(
                    messageId = messageId,
                    chatId = chatId,
                    collectionId = collectionId,
                    addedAtMillis = now(),
                ),
        )
        return true
    }

    /** Removes a message from one collection. */
    fun removeBookmark(
        messageId: String,
        collectionId: String,
    ): Boolean {
        val current = bookmarks()
        val remaining = current.filterNot { it.messageId == messageId && it.collectionId == collectionId }
        if (remaining.size == current.size) return false
        writeBookmarks(remaining)
        return true
    }

    /** Bookmarks in one collection, newest first. */
    fun bookmarks(collectionId: String): List<Bookmark> =
        bookmarks().filter { it.collectionId == collectionId }.sortedByDescending { it.addedAtMillis }

    /** Every bookmark. */
    fun bookmarks(): List<Bookmark> {
        val text = store.getString(KEY_BOOKMARKS) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decodeBookmark(it) }
    }

    /** How many collections hold [messageId]. */
    fun collectionsFor(messageId: String): List<BookmarkCollection> =
        bookmarks()
            .filter { it.messageId == messageId }
            .mapNotNull { collection(it.collectionId) }
            .distinctBy { it.id }

    /** Per-collection member counts, including empty built-ins. */
    fun counts(): Map<String, Int> {
        val all = bookmarks().groupingBy { it.collectionId }.eachCount()
        return collections().associate { it.id to (all[it.id] ?: 0) }
    }

    /** Drops custom collections and every bookmark. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_CUSTOM_COLLECTIONS)
        store.remove(KEY_BOOKMARKS)
    }

    private fun customCollections(): List<BookmarkCollection> {
        val text = store.getString(KEY_CUSTOM_COLLECTIONS) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decodeCollection(it) }
    }

    private fun writeCustom(collections: List<BookmarkCollection>) {
        store.putString(
            KEY_CUSTOM_COLLECTIONS,
            MiniJson.write(jsonArray(collections.map { encodeCollection(it) })),
        )
    }

    private fun writeBookmarks(bookmarks: List<Bookmark>) {
        store.putString(KEY_BOOKMARKS, MiniJson.write(jsonArray(bookmarks.map { encodeBookmark(it) })))
    }

    private fun nextCustomId(): String {
        var candidate = "collection.${now()}"
        var counter = 1
        while (collections().any { it.id == candidate }) {
            candidate = "collection.${now()}.$counter"
            counter++
        }
        return candidate
    }

    private fun encodeCollection(collection: BookmarkCollection): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(collection.id),
            "name" to jsonString(collection.name),
        )

    private fun decodeCollection(value: JsonValue): BookmarkCollection? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return BookmarkCollection(
            id = fields.string("id") ?: return null,
            name = fields.string("name") ?: return null,
            builtIn = false,
        )
    }

    private fun encodeBookmark(bookmark: Bookmark): JsonValue.Obj =
        jsonObject(
            "messageId" to jsonString(bookmark.messageId),
            "chatId" to jsonString(bookmark.chatId),
            "collectionId" to jsonString(bookmark.collectionId),
            "addedAt" to jsonNumber(bookmark.addedAtMillis),
        )

    private fun decodeBookmark(value: JsonValue): Bookmark? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return Bookmark(
            messageId = fields.string("messageId") ?: return null,
            chatId = fields.string("chatId") ?: return null,
            collectionId = fields.string("collectionId") ?: return null,
            addedAtMillis = fields.long("addedAt") ?: 0L,
        )
    }

    companion object {
        /** Storage key for custom collections. */
        const val KEY_CUSTOM_COLLECTIONS: String = "wae.history.collections"

        /** Storage key for bookmarks. */
        const val KEY_BOOKMARKS: String = "wae.history.bookmarks"

        /** Longest accepted collection name. */
        const val MAX_NAME_LENGTH: Int = 32
    }
}

/** The five built-in collections from T90. */
object BuiltInCollections {
    const val WORK = "builtin.work"
    const val IMPORTANT = "builtin.important"
    const val LATER = "builtin.later"
    const val RECEIPTS = "builtin.receipts"
    const val PERSONAL = "builtin.personal"

    /** Every built-in collection, in the order the UI should show them. */
    val all: List<BookmarkCollection> =
        listOf(
            BookmarkCollection(WORK, "Work", builtIn = true),
            BookmarkCollection(IMPORTANT, "Important", builtIn = true),
            BookmarkCollection(LATER, "Later", builtIn = true),
            BookmarkCollection(RECEIPTS, "Receipts", builtIn = true),
            BookmarkCollection(PERSONAL, "Personal", builtIn = true),
        )

    /** Finds a built-in by id. */
    fun byId(id: String): BookmarkCollection? = all.firstOrNull { it.id == id }
}
