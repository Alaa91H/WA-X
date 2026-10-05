package com.wax.module.intelligence

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.jsonArray
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.string
import java.security.MessageDigest

/** The engines T106 abstracts over. */
enum class TranslationEngine {
    /** On-device; nothing leaves the phone. */
    LOCAL,

    /** Google Translate. */
    GOOGLE,

    /** DeepL. */
    DEEPL,

    /** A provider supplied by the user. */
    CUSTOM,
}

/** One translation to perform. */
data class TranslationRequest(
    val text: String,
    val targetLanguage: String,
    val sourceLanguage: String? = null,
)

/** One successful translation. */
data class TranslationResult(
    val translatedText: String,
    val detectedSourceLanguage: String?,
    val engine: TranslationEngine,
    val fromCache: Boolean = false,
)

/** What happened when a translation was requested. */
sealed interface TranslationOutcome {
    /** The text was translated. */
    data class Translated(
        val result: TranslationResult,
    ) : TranslationOutcome

    /** No usable provider is available, optionally because cloud use is not opted in. */
    data class Unavailable(
        val reason: String,
        val cloudBlocked: Boolean = false,
    ) : TranslationOutcome

    /** The provider was used and failed. */
    data class Failed(
        val reason: String,
    ) : TranslationOutcome
}

/**
 * A translation backend.
 *
 * Implementations must not throw for ordinary failures; a network error is a
 * [TranslationOutcome.Failed], because the coordinator needs to fall through to the next
 * provider rather than abort the translation. [isCloud] is part of the contract because the
 * privacy gate decides access on it, not on the engine's name.
 */
interface TranslationProvider {
    /** Which engine this implements. */
    val engine: TranslationEngine

    /** Whether text would leave the device. */
    val isCloud: Boolean

    /** A user-facing name for the provider picker. */
    val displayName: String

    /** Translates [request]. */
    fun translate(request: TranslationRequest): TranslationOutcome
}

/** Keeps the available translation providers. Replacing a provider is allowed on re-register. */
class TranslationProviderRegistry {
    private val providers = LinkedHashMap<TranslationEngine, TranslationProvider>()

    /** Registers (or replaces) a provider. */
    fun register(provider: TranslationProvider) {
        providers[provider.engine] = provider
    }

    /** The provider for [engine], or null. */
    fun providerFor(engine: TranslationEngine): TranslationProvider? = providers[engine]

    /** The on-device provider, or null when none is registered. */
    fun local(): TranslationProvider? = providers[TranslationEngine.LOCAL]

    /** Cloud providers that are registered, in registration order. */
    fun cloud(): List<TranslationProvider> = providers.values.filter { it.isCloud }

    /** Every registered provider. */
    fun all(): List<TranslationProvider> = providers.values.toList()

    /** Drops every registration. Used by tests. */
    fun clear() {
        providers.clear()
    }
}

/** How a chat translates messages (T107). */
enum class TranslationMode {
    /** Never translate automatically. */
    OFF,

    /** Only when the user asks for it. */
    ON_DEMAND,

    /** Translate every message in this chat. */
    ALWAYS_CHAT,

    /** Translate only messages whose detected language differs from the target. */
    ALWAYS_DETECTED,
}

/** The per-chat language configuration of T108. */
data class ChatLanguageProfile(
    val chatId: String,
    val targetLanguage: String,
    val mode: TranslationMode,
) {
    /** One line for diagnostics. */
    fun toDisplayLine(): String = "$targetLanguage (${mode.name.lowercase()})"
}

/** What the inline translation layer should do for one chat and message (T107). */
data class TranslationDecision(
    val shouldTranslate: Boolean,
    val targetLanguage: String?,
    val reason: String,
)

/** The outgoing preview of T109: both texts, and the user's choice of what is sent. */
data class OutgoingPreview(
    val original: String,
    val translatedText: String?,
    val targetLanguage: String?,
) {
    /** Whether a translation is available to choose. */
    val hasTranslation: Boolean get() = !translatedText.isNullOrBlank()
}

/** What the user chose to send. */
enum class OutgoingTranslationChoice {
    ORIGINAL,
    TRANSLATED,
}

/**
 * Stores per-chat language profiles.
 *
 * One document keyed by chat id; a profile is small and the set is bounded by the number of
 * chats the user configures, so a flat map is the honest structure.
 */
class ChatLanguageProfileStore(
    private val store: KeyValueStore,
) {
    /** Sets the target language for a chat, preserving the mode. */
    fun setTarget(
        chatId: String,
        targetLanguage: String,
    ): Boolean {
        if (chatId.isBlank() || targetLanguage.isBlank()) return false
        val existing = profileFor(chatId)
        return write(
            profiles().filterNot { it.chatId == chatId } +
                ChatLanguageProfile(chatId, targetLanguage.trim(), existing?.mode ?: TranslationMode.ON_DEMAND),
        )
    }

    /** Sets the mode for a chat; the chat must already have a target language. */
    fun setMode(
        chatId: String,
        mode: TranslationMode,
    ): Boolean {
        val existing = profileFor(chatId) ?: return false
        return write(profiles().map { if (it.chatId == chatId) it.copy(mode = mode) else it })
    }

    /** The profile for [chatId], or null when the chat is not configured. */
    fun profileFor(chatId: String): ChatLanguageProfile? = profiles().firstOrNull { it.chatId == chatId }

    /** Every configured profile. */
    fun profiles(): List<ChatLanguageProfile> {
        val text = store.getString(KEY_PROFILES) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decode(it) }
    }

    /** Removes a chat's profile. */
    fun remove(chatId: String): Boolean {
        val current = profiles()
        val remaining = current.filterNot { it.chatId == chatId }
        if (remaining.size == current.size) return false
        return write(remaining)
    }

    /** Drops every profile. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_PROFILES)
    }

    private fun write(profiles: List<ChatLanguageProfile>): Boolean {
        store.putString(KEY_PROFILES, MiniJson.write(jsonArray(profiles.map { encode(it) })))
        return true
    }

    private fun encode(profile: ChatLanguageProfile): JsonValue.Obj =
        jsonObject(
            "chatId" to jsonString(profile.chatId),
            "target" to jsonString(profile.targetLanguage),
            "mode" to jsonString(profile.mode.name),
        )

    private fun decode(value: JsonValue): ChatLanguageProfile? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return ChatLanguageProfile(
            chatId = fields.string("chatId") ?: return null,
            targetLanguage = fields.string("target") ?: return null,
            mode =
                TranslationMode.entries.firstOrNull { it.name == fields.string("mode") }
                    ?: TranslationMode.ON_DEMAND,
        )
    }

    companion object {
        /** Storage key for the profile map. */
        const val KEY_PROFILES: String = "wae.intelligence.language_profiles"
    }
}

/**
 * Bounded local cache of translations (T112's counterpart for text).
 *
 * The cache key is a hash of `(target, source text)`, and the stored value is the translated
 * text. Both are local; the only reason to hash the key is to keep chat text out of
 * preference *keys*, which are easy to log or dump by accident.
 */
class TranslationCache(
    private val store: KeyValueStore,
    private val maxEntries: Int = 500,
) {
    /** The cached translation, or null. */
    fun get(
        text: String,
        targetLanguage: String,
    ): String? {
        val key = cacheKey(text, targetLanguage)
        val stored = store.getString("$KEY_ENTRY$key") ?: return null
        // The entry stores "<target>\u0000<translation>" so a cache hit can be sanity
        // checked against the language it was produced for.
        if (stored.substringBefore('\u0000') != targetLanguage) return null
        return stored.substringAfter('\u0000')
    }

    /** Stores a translation, pruning when the bound is exceeded. */
    fun put(
        text: String,
        targetLanguage: String,
        translatedText: String,
    ) {
        val key = cacheKey(text, targetLanguage)
        store.putString("$KEY_ENTRY$key", "$targetLanguage\u0000$translatedText")
        prune()
    }

    /** The number of cached entries. */
    fun size(): Int = store.keys(KEY_ENTRY).size

    /** Removes oldest entries until the bound holds. */
    fun prune() {
        val keys = store.keys(KEY_ENTRY)
        if (keys.size <= maxEntries) return
        keys.take(keys.size - maxEntries).forEach { store.remove(it) }
    }

    /** Deletes the whole cache. */
    fun clear() {
        store.keys(KEY_ENTRY).forEach { store.remove(it) }
    }

    private fun cacheKey(
        text: String,
        targetLanguage: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest((targetLanguage + "\u0000" + text).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)

    companion object {
        private const val KEY_ENTRY = "wae.intelligence.translation."
    }
}

/**
 * Decides when to translate inline and performs translations.
 *
 * Local-first is an ordering rule, not a preference: providers are tried on-device first,
 * and a cloud provider is only ever consulted when the user has explicitly opted that
 * engine in through [CloudPrivacyGate]. When only a blocked cloud provider could do the
 * work, the outcome says so with `cloudBlocked = true` so the UI can offer the opt-in
 * instead of showing a generic failure.
 */
class TranslationCoordinator(
    private val registry: TranslationProviderRegistry,
    private val profiles: ChatLanguageProfileStore,
    private val cache: TranslationCache,
    private val cloudGate: CloudPrivacyGate,
) {
    /** Whether inline translation should run for a message (T107). */
    fun decide(
        chatId: String,
        detectedLanguage: String?,
    ): TranslationDecision {
        val profile =
            profiles.profileFor(chatId)
                ?: return TranslationDecision(false, null, "no language profile for this chat")
        return when (profile.mode) {
            TranslationMode.OFF -> TranslationDecision(false, profile.targetLanguage, "translation is off for this chat")
            TranslationMode.ON_DEMAND ->
                TranslationDecision(false, profile.targetLanguage, "on-demand: waiting for the user to ask")

            TranslationMode.ALWAYS_CHAT ->
                TranslationDecision(true, profile.targetLanguage, "chat is configured to always translate")

            TranslationMode.ALWAYS_DETECTED ->
                when {
                    detectedLanguage == null ->
                        TranslationDecision(false, profile.targetLanguage, "language could not be detected")

                    detectedLanguage.equals(profile.targetLanguage, ignoreCase = true) ->
                        TranslationDecision(false, profile.targetLanguage, "message is already in the target language")

                    else -> TranslationDecision(true, profile.targetLanguage, "detected $detectedLanguage")
                }
        }
    }

    /**
     * Translates [text] for a chat, preferring a cache hit and then the local provider.
     *
     * @param sourceLanguage the detected source language, when known
     */
    fun translate(
        chatId: String,
        text: String,
        sourceLanguage: String? = null,
    ): TranslationOutcome {
        if (text.isBlank()) return TranslationOutcome.Failed("there is nothing to translate")
        val profile =
            profiles.profileFor(chatId)
                ?: return TranslationOutcome.Unavailable("this chat has no target language")

        cache.get(text, profile.targetLanguage)?.let { cached ->
            return TranslationOutcome.Translated(
                TranslationResult(
                    translatedText = cached,
                    detectedSourceLanguage = sourceLanguage,
                    engine = TranslationEngine.LOCAL,
                    fromCache = true,
                ),
            )
        }

        val request = TranslationRequest(text, profile.targetLanguage, sourceLanguage)
        val local = registry.local()
        if (local != null) {
            when (val outcome = local.translate(request)) {
                is TranslationOutcome.Translated -> {
                    cache.put(text, profile.targetLanguage, outcome.result.translatedText)
                    return outcome
                }

                is TranslationOutcome.Failed -> Unit // Fall through to cloud providers.
                is TranslationOutcome.Unavailable -> Unit
            }
        }

        val cloudProviders = registry.cloud()
        // Only engines the user explicitly opted in are eligible; the mapping keeps the
        // privacy decision keyed to the service the gate knows, not the engine's name.
        val allowed =
            cloudProviders.filter { provider ->
                provider.engine.cloudService()?.let { service -> cloudGate.isEnabled(service) } == true
            }
        for (provider in allowed) {
            when (val outcome = provider.translate(request)) {
                is TranslationOutcome.Translated -> {
                    cache.put(text, profile.targetLanguage, outcome.result.translatedText)
                    return outcome
                }

                is TranslationOutcome.Failed -> Unit
                is TranslationOutcome.Unavailable -> Unit
            }
        }

        return if (cloudProviders.isNotEmpty() && allowed.isEmpty()) {
            TranslationOutcome.Unavailable(
                reason = "Only cloud translation is available and it is not enabled for this engine.",
                cloudBlocked = true,
            )
        } else {
            TranslationOutcome.Unavailable("No translation provider could handle this message.")
        }
    }

    /** Builds the T109 preview for an outgoing message. */
    fun previewOutgoing(
        chatId: String,
        text: String,
        sourceLanguage: String? = null,
    ): OutgoingPreview {
        val profile = profiles.profileFor(chatId)
        if (profile == null || text.isBlank()) {
            return OutgoingPreview(original = text, translatedText = null, targetLanguage = profile?.targetLanguage)
        }
        val outcome = translate(chatId, text, sourceLanguage)
        return OutgoingPreview(
            original = text,
            translatedText = (outcome as? TranslationOutcome.Translated)?.result?.translatedText,
            targetLanguage = profile.targetLanguage,
        )
    }

    /** Resolves the user's choice into the text that is actually sent. */
    fun resolveOutgoing(
        choice: OutgoingTranslationChoice,
        preview: OutgoingPreview,
    ): String =
        when (choice) {
            OutgoingTranslationChoice.ORIGINAL -> preview.original
            OutgoingTranslationChoice.TRANSLATED -> preview.translatedText ?: preview.original
        }
}
