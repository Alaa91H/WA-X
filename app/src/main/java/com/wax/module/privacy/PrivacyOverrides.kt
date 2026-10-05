package com.wax.module.privacy

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.obj

/**
 * Whether an override belongs to a direct chat or a group.
 *
 * The kind is part of the storage key rather than being derived from the chat id. A JID
 * ending in `@g.us` would be derivable, but the platform also deals with LIDs and future
 * identifier shapes, and a wrong derivation would silently apply a contact rule to a group.
 * Being explicit is the only version that cannot misclassify.
 *
 * This is an alias of the platform's shared [com.wax.module.platform.ChatKind] so
 * every feature stores the same vocabulary.
 */
typealias PrivacyChatKind = com.wax.module.platform.ChatKind

/**
 * Per-chat overrides of the active profile.
 *
 * The rule that keeps this predictable is "only explicitly configured fields are
 * overridden". The store therefore holds a sparse map per chat — never a full profile — and
 * resolution starts from the global profile and writes only the overridden fields over it.
 * A field the user never touched keeps following the global profile when it changes.
 *
 * Group and contact overrides use separate key spaces ([KEY_PREFIX] plus the kind), so the
 * same identifier string as a contact and as a group cannot leak into the other.
 */
class PrivacyOverrideStore(
    private val store: KeyValueStore,
) {
    /**
     * Sets one field for one chat.
     *
     * @return false when the chat id is blank, because an override with no chat to attach to
     *   would be unreachable state; nothing is written in that case
     */
    fun setOverride(
        chatId: String,
        kind: PrivacyChatKind,
        field: PrivacyField,
        choice: Visibility,
    ): Boolean {
        if (chatId.isBlank()) return false
        val current = overrides(chatId, kind).toMutableMap()
        current[field] = choice
        store.putString(keyFor(chatId, kind), encode(current))
        return true
    }

    /**
     * Removes one field's override, returning the chat to the global profile for it.
     *
     * @return false when the chat id is blank or had no override for that field
     */
    fun clearOverride(
        chatId: String,
        kind: PrivacyChatKind,
        field: PrivacyField,
    ): Boolean {
        if (chatId.isBlank()) return false
        val current = overrides(chatId, kind).toMutableMap()
        if (current.remove(field) == null) return false
        if (current.isEmpty()) {
            store.remove(keyFor(chatId, kind))
        } else {
            store.putString(keyFor(chatId, kind), encode(current))
        }
        return true
    }

    /** Removes every override for one chat. */
    fun clearChat(
        chatId: String,
        kind: PrivacyChatKind,
    ): Boolean {
        if (chatId.isBlank()) return false
        if (overrides(chatId, kind).isEmpty()) return false
        store.remove(keyFor(chatId, kind))
        return true
    }

    /** The fields explicitly overridden for one chat. Empty when the chat follows the profile. */
    fun overrides(
        chatId: String,
        kind: PrivacyChatKind,
    ): Map<PrivacyField, Visibility> {
        if (chatId.isBlank()) return emptyMap()
        val text = store.getString(keyFor(chatId, kind)) ?: return emptyMap()
        val fields = (MiniJson.parse(text) as? JsonValue.Obj)?.fields ?: return emptyMap()
        val result = LinkedHashMap<PrivacyField, Visibility>()
        fields.obj("visibility")?.forEach { (key, raw) ->
            val field = PrivacyField.entries.firstOrNull { it.name == key } ?: return@forEach
            val choice =
                Visibility.entries.firstOrNull { it.name == (raw as? JsonValue.Str)?.value }
                    ?: return@forEach
            result[field] = choice
        }
        return result
    }

    /** Whether the chat has at least one override. */
    fun hasOverrides(
        chatId: String,
        kind: PrivacyChatKind,
    ): Boolean = overrides(chatId, kind).isNotEmpty()

    /** The number of chats with at least one override. */
    fun count(): Int = store.keys(KEY_PREFIX).count { decodeChatId(it) != null }

    /** Every configured override, for diagnostics and export. Never contains messages. */
    fun all(): List<ChatOverride> =
        store.keys(KEY_PREFIX).mapNotNull { key ->
            val parsed = decodeChatId(key) ?: return@mapNotNull null
            val fields = overrides(parsed.first, parsed.second)
            if (fields.isEmpty()) null else ChatOverride(parsed.first, parsed.second, fields)
        }

    /** Drops every override. Used by tests and by a factory reset. */
    fun clearAll() {
        store.keys(KEY_PREFIX).forEach { store.remove(it) }
    }

    private fun keyFor(
        chatId: String,
        kind: PrivacyChatKind,
    ): String = "$KEY_PREFIX${kind.name}.$chatId"

    /** Splits a storage key back into (chatId, kind), or null when the key is malformed. */
    private fun decodeChatId(key: String): Pair<String, PrivacyChatKind>? {
        val remainder = key.removePrefix(KEY_PREFIX)
        val separator = remainder.indexOf('.')
        if (separator <= 0) return null
        val kind =
            PrivacyChatKind.entries.firstOrNull { it.name == remainder.substring(0, separator) }
                ?: return null
        val chatId = remainder.substring(separator + 1)
        return if (chatId.isBlank()) null else chatId to kind
    }

    private fun encode(fields: Map<PrivacyField, Visibility>): String =
        MiniJson.write(
            JsonValue.Obj(
                linkedMapOf(
                    "visibility" to
                        JsonValue.Obj(
                            fields.entries.associate { (field, choice) -> field.name to JsonValue.Str(choice.name) },
                        ),
                ),
            ),
        )

    companion object {
        /** Storage key prefix for all overrides. */
        const val KEY_PREFIX: String = "wae.privacy.override."
    }
}

/** One chat's configured overrides, used by diagnostics and export. */
data class ChatOverride(
    val chatId: String,
    val kind: PrivacyChatKind,
    val fields: Map<PrivacyField, Visibility>,
) {
    /** One line that deliberately does not include the raw chat id. */
    fun toDisplayLine(): String = "${kind.name.lowercase()} override: ${fields.size} field(s)"
}

/**
 * What actually applies to a chat after the profile and its overrides are combined.
 *
 * [overriddenFields] is carried explicitly so the UI can show which choices are chat-local
 * and which follow the global profile — the user must be able to see why a toggle behaves
 * differently in one conversation.
 */
data class EffectivePrivacy(
    val visibility: Map<PrivacyField, Visibility>,
    val callBehavior: CallBehavior,
    val notificationPrivacy: NotificationPrivacy,
    val overriddenFields: Set<PrivacyField>,
    val profileId: String,
    val profileName: String,
) {
    /** The choice for [field]. */
    fun choiceFor(field: PrivacyField): Visibility = visibility[field] ?: Visibility.SHOW

    /** Whether [field] comes from a per-chat override rather than the profile. */
    fun isOverridden(field: PrivacyField): Boolean = field in overriddenFields
}

/**
 * Resolves the effective privacy for a chat.
 *
 * One function, one order: start from the active profile, apply only the chat's explicit
 * overrides. Everything that reads privacy behaviour goes through here so a new feature
 * cannot invent its own precedence and disagree with the rest of the module.
 */
class PrivacyResolver(
    private val profiles: PrivacyProfileStore,
    private val overrides: PrivacyOverrideStore,
) {
    /** The effective policy for one chat. */
    fun effective(
        chatId: String,
        kind: PrivacyChatKind,
    ): EffectivePrivacy {
        val profile = profiles.active()
        val chatOverrides = overrides.overrides(chatId, kind)
        val visibility =
            PrivacyField.entries.associateWith { field ->
                chatOverrides[field] ?: profile.choiceFor(field)
            }
        return EffectivePrivacy(
            visibility = visibility,
            callBehavior = profile.callBehavior,
            notificationPrivacy = profile.notificationPrivacy,
            overriddenFields = chatOverrides.keys,
            profileId = profile.id,
            profileName = profile.name,
        )
    }
}
