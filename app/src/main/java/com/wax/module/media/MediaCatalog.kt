package com.wax.module.media

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.boolean
import com.wax.module.platform.jsonArray
import com.wax.module.platform.jsonBoolean
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.long
import com.wax.module.platform.string

/** Everything the media center is expected to cover (T116). */
enum class MediaKind {
    IMAGE,
    VIDEO,
    AUDIO,
    VOICE,
    DOCUMENT,
    STATUS,
    PROFILE_PHOTO,
}

/**
 * One indexed media item.
 *
 * The catalog stores metadata only — no pixels, no audio, no file contents. That keeps the
 * index cheap and, more importantly, makes it safe to keep in the same local store as the
 * rest of the module's settings.
 */
data class MediaItem(
    val id: String,
    val kind: MediaKind,
    val chatId: String?,
    val fileName: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val mimeType: String? = null,
    val localUri: String? = null,
    val isFavorite: Boolean = false,
) {
    /** One line for the media list. */
    fun toDisplayLine(): String = "$fileName (${kind.name.lowercase()}, ${sizeBytes / 1024} KiB)"
}

/**
 * The single local index behind the media center.
 *
 * One catalog with a `kind` field rather than one list per media type, because the product
 * promise of T116 is a single interface: filtering by kind, chat or name is then a query, not
 * a different screen. Metadata persists locally in one bounded document.
 */
class MediaCatalog(
    private val store: KeyValueStore,
    private val maxItems: Int = 5000,
) {
    /** Adds an item. Re-adding the same id replaces it; returns false for invalid input. */
    fun add(item: MediaItem): Boolean {
        if (item.id.isBlank() || item.fileName.isBlank()) return false
        val current = byId(item.id)?.let { all().filterNot { existing -> existing.id == item.id } } ?: all()
        val updated = current + item
        if (updated.size > maxItems) return false
        write(updated)
        return true
    }

    /** Every indexed item, newest first. */
    fun all(): List<MediaItem> {
        val text = store.getString(KEY_ITEMS) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decode(it) }.sortedByDescending { it.createdAtMillis }
    }

    /** Items of one kind. */
    fun byKind(kind: MediaKind): List<MediaItem> = all().filter { it.kind == kind }

    /** Items belonging to one chat. */
    fun byChat(chatId: String): List<MediaItem> = all().filter { it.chatId == chatId }

    /** Items whose file name contains [query], case-insensitively. */
    fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        return all().filter {
            it.fileName.contains(query, ignoreCase = true) ||
                (it.mimeType?.contains(query, ignoreCase = true) ?: false)
        }
    }

    /** One item by id. */
    fun byId(id: String): MediaItem? = all().firstOrNull { it.id == id }

    /** Removes an item. */
    fun remove(id: String): Boolean {
        val current = all()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        write(remaining)
        return true
    }

    /** Marks or unmarks an item as favourite. */
    fun setFavorite(
        id: String,
        favorite: Boolean,
    ): Boolean {
        val current = all()
        if (current.none { it.id == id }) return false
        write(current.map { if (it.id == id) it.copy(isFavorite = favorite) else it })
        return true
    }

    /** Total bytes across the index. */
    fun totalSizeBytes(): Long = all().sumOf { it.sizeBytes }

    /** Total bytes per kind. */
    fun sizeByKind(): Map<MediaKind, Long> = MediaKind.entries.associateWith { kind -> byKind(kind).sumOf { it.sizeBytes } }

    /** How many items are indexed. */
    fun count(): Int = all().size

    /** Drops the index. Media files themselves are untouched. */
    fun clear() {
        store.remove(KEY_ITEMS)
    }

    private fun write(items: List<MediaItem>) {
        store.putString(KEY_ITEMS, MiniJson.write(jsonArray(items.map { encode(it) })))
    }

    private fun encode(item: MediaItem): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(item.id),
            "kind" to jsonString(item.kind.name),
            "chatId" to item.chatId?.let { jsonString(it) },
            "fileName" to jsonString(item.fileName),
            "size" to jsonNumber(item.sizeBytes),
            "createdAt" to jsonNumber(item.createdAtMillis),
            "mimeType" to item.mimeType?.let { jsonString(it) },
            "localUri" to item.localUri?.let { jsonString(it) },
            "favorite" to jsonBoolean(item.isFavorite),
        )

    private fun decode(value: JsonValue): MediaItem? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return MediaItem(
            id = fields.string("id") ?: return null,
            kind = MediaKind.entries.firstOrNull { it.name == fields.string("kind") } ?: return null,
            chatId = fields.string("chatId"),
            fileName = fields.string("fileName") ?: return null,
            sizeBytes = fields.long("size") ?: 0L,
            createdAtMillis = fields.long("createdAt") ?: 0L,
            mimeType = fields.string("mimeType"),
            localUri = fields.string("localUri"),
            isFavorite = fields.boolean("favorite") ?: false,
        )
    }

    companion object {
        /** Storage key for the media index. */
        const val KEY_ITEMS: String = "wae.media.catalog"
    }
}
