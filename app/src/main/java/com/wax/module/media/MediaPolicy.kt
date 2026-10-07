package com.wax.module.media

import com.wax.module.outgoing.PolicyScope
import com.wax.module.outgoing.PolicyScopes
import com.wax.module.platform.ChatKind
import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.TargetApp
import com.wax.module.platform.jsonObject
import com.wax.module.platform.obj
import com.wax.module.platform.objOrNull
import com.wax.module.platform.stringOrNull

/**
 * The kinds of incoming media a download policy can talk about.
 *
 * Voice notes and audio files are separate members for the same reason they are separate in the
 * outgoing policy: a voice note is played in the conversation and an audio file is an
 * attachment, and a user who wants voice notes downloaded automatically usually does not want
 * every large audio attachment pulled down with them.
 */
enum class MediaClass(
    /** Name shown in the policy editor. */
    val label: String,
) {
    IMAGES("Images"),
    VIDEOS("Videos"),
    VOICE_NOTES("Voice notes"),
    AUDIO("Audio files"),
    DOCUMENTS("Documents"),
    GIFS("GIFs"),
    STICKERS("Stickers"),
}

/**
 * What one scope says about downloading a class of media.
 *
 * [USE_PARENT] is the "no opinion here" value rather than a mode of its own, which is what lets
 * a contact set "Never" for documents while still inheriting the group's answer for images. The
 * resolved value when nothing anywhere speaks is [USE_PARENT] as well, and the caller reads
 * that as "leave it to WhatsApp" instead of inventing a policy the user never chose.
 */
enum class MediaPolicyChoice(
    val label: String,
) {
    USE_PARENT("Use chat policy"),
    ALWAYS("Always"),
    WIFI_ONLY("Wi-Fi only"),
    NEVER("Never"),
    MANUAL_TAP_ONLY("Manual tap only"),
}

/** How the device is connected, as far as a download policy is concerned. */
enum class NetworkClass {
    /** Wi-Fi or otherwise unmetered. */
    UNMETERED,

    /** Cellular or otherwise metered. */
    METERED,

    /** No connection at all. */
    OFFLINE,
}

/** The concrete instruction the download path acts on. */
enum class MediaDownloadAction {
    /** WA X has no policy here; WhatsApp's own auto-download setting decides. */
    LEAVE_TO_WHATSAPP,

    /** Fetch it now. */
    DOWNLOAD_NOW,

    /** Hold it until the user taps it. */
    WAIT_FOR_USER_TAP,

    /** Hold it until an unmetered connection is available. */
    WAIT_FOR_UNMETERED,

    /** Do not fetch it at all. */
    DO_NOT_DOWNLOAD,
}

/**
 * What should happen to one incoming media item.
 *
 * [source] is the scope that decided, so the interface can say which rule acted rather than
 * showing a bare "download blocked" with no way to find the setting that caused it.
 */
data class MediaDownloadDecision(
    val action: MediaDownloadAction,
    val choice: MediaPolicyChoice,
    val source: PolicyScope,
    val explanation: String,
) {
    /** Whether the media may be fetched right now. */
    val download: Boolean get() = action == MediaDownloadAction.DOWNLOAD_NOW

    /** One line for diagnostics. Names the scope's kind, never the chat's identifier. */
    fun toDisplayLine(): String = "${action.name.lowercase()} from ${source.label} (${choice.name})"
}

/** One scope's media policy, stored sparsely. Absent classes mean "no opinion". */
data class MediaPolicyLayer(
    val choices: Map<MediaClass, MediaPolicyChoice> = emptyMap(),
) {
    /** Whether this layer says anything at all. */
    val isEmpty: Boolean get() = choices.isEmpty()

    /** Merges [next] over this layer, class by class, with [next] winning where it speaks. */
    fun over(next: MediaPolicyLayer): MediaPolicyLayer = MediaPolicyLayer(choices + next.choices)
}

/** What a media class resolves to, and which scope decided. */
data class ResolvedMediaChoice(
    val choice: MediaPolicyChoice,
    val source: PolicyScope,
) {
    /** Whether any scope has an opinion about this class. */
    val isConfigured: Boolean get() = choice != MediaPolicyChoice.USE_PARENT
}

/**
 * One incoming transfer, described in the terms the policy speaks.
 *
 * [trusted] is the join with the untrusted-sender firewall: a message from someone the user has
 * not classified as trusted is held for a tap regardless of the stored policy, because an
 * unknown sender should not be able to push an attachment onto the device by policy alone. It
 * defaults to true so a caller that has no trust information behaves exactly as before.
 */
data class MediaPolicyRequest(
    val app: TargetApp,
    val chatId: String,
    val kind: ChatKind,
    val accountId: String? = null,
    val listId: String? = null,
    val trusted: Boolean = true,
) {
    /** The scopes that apply to this chat, least specific first. */
    fun scopes(): List<PolicyScope> = PolicyScopes.forChat(app, chatId, kind, accountId, listId)
}

/**
 * Where each scope's media policy lives.
 *
 * One key per scope, with a total decoder: an unreadable entry becomes an empty layer, which
 * inherits and therefore changes nothing. A corrupt policy must never be the reason a message
 * silently fails to download.
 */
class MediaPolicyStore(
    private val store: KeyValueStore,
) {
    /** The layer stored for [scope], or an empty layer when nothing is configured there. */
    fun layerFor(scope: PolicyScope): MediaPolicyLayer {
        val text = store.getString(keyFor(scope)) ?: return MediaPolicyLayer()
        return decodeLayer(text)
    }

    /** Stores [layer] for [scope]. An empty layer removes the entry instead of writing it. */
    fun save(
        scope: PolicyScope,
        layer: MediaPolicyLayer,
    ) {
        if (layer.isEmpty) {
            store.remove(keyFor(scope))
            return
        }
        store.putString(keyFor(scope), MiniJson.write(encodeLayer(layer)))
    }

    /** Every scope that currently holds a layer, in precedence order. */
    fun configuredScopes(): List<PolicyScope> =
        store
            .keys(KEY_PREFIX)
            .mapNotNull { PolicyScope.parse(it.removePrefix(KEY_PREFIX)) }
            .sortedBy { it.precedence }

    /** Removes every layer. Used by tests and by a factory reset. */
    fun clear() {
        store.keys(KEY_PREFIX).forEach { store.remove(it) }
    }

    private fun keyFor(scope: PolicyScope): String = KEY_PREFIX + scope.code

    private fun encodeLayer(layer: MediaPolicyLayer): JsonValue.Obj {
        val choices = LinkedHashMap<String, JsonValue>()
        layer.choices.forEach { (mediaClass, choice) -> choices[mediaClass.name] = JsonValue.Str(choice.name) }
        return jsonObject("choices" to JsonValue.Obj(choices))
    }

    private fun decodeLayer(text: String): MediaPolicyLayer {
        val root = MiniJson.parse(text)?.objOrNull() ?: return MediaPolicyLayer()
        val choices = LinkedHashMap<MediaClass, MediaPolicyChoice>()
        root.obj("choices")?.forEach { (name, raw) ->
            val mediaClass = MediaClass.entries.firstOrNull { it.name == name } ?: return@forEach
            val choice = MediaPolicyChoice.entries.firstOrNull { it.name == raw.stringOrNull() } ?: return@forEach
            choices[mediaClass] = choice
        }
        return MediaPolicyLayer(choices)
    }

    companion object {
        /** Prefix for every stored media policy. */
        const val KEY_PREFIX: String = "wae.media.policy."
    }
}

/**
 * Resolves one chat's media policy and turns it into a download instruction.
 *
 * Resolution walks the shared scope chain from Global up to the chat, last answer wins, which is
 * the same order the outgoing policy engine uses. The resolver never throws: an unreadable
 * layer, a missing class or an offline device all produce an answer with a sentence attached,
 * because the download path runs inside notification handling where an exception is a crash the
 * user sees as lost media.
 */
class MediaPolicyResolver(
    private val policies: MediaPolicyStore,
) {
    /** Every class's resolved choice for [request]. */
    fun resolve(request: MediaPolicyRequest): Map<MediaClass, ResolvedMediaChoice> =
        MediaClass.entries.associateWith { resolve(request, it) }

    /** One class's resolved choice for [request]. */
    fun resolve(
        request: MediaPolicyRequest,
        mediaClass: MediaClass,
    ): ResolvedMediaChoice {
        var choice: MediaPolicyChoice? = null
        var source: PolicyScope = PolicyScope.Global
        request.scopes().forEach { scope ->
            val declared = policies.layerFor(scope).choices[mediaClass]
            if (declared != null && declared != MediaPolicyChoice.USE_PARENT) {
                choice = declared
                source = scope
            }
        }
        return ResolvedMediaChoice(choice ?: MediaPolicyChoice.USE_PARENT, source)
    }

    /** What should happen to [mediaClass] arriving in [request] on [network]. */
    fun decide(
        request: MediaPolicyRequest,
        mediaClass: MediaClass,
        network: NetworkClass,
    ): MediaDownloadDecision {
        val resolved = resolve(request, mediaClass)
        if (!request.trusted) {
            return MediaDownloadDecision(
                action = MediaDownloadAction.WAIT_FOR_USER_TAP,
                choice = resolved.choice,
                source = resolved.source,
                explanation = "This chat is not trusted, so ${mediaClass.label.lowercase()} wait for a tap.",
            )
        }
        if (!resolved.isConfigured) {
            return MediaDownloadDecision(
                action = MediaDownloadAction.LEAVE_TO_WHATSAPP,
                choice = MediaPolicyChoice.USE_PARENT,
                source = resolved.source,
                explanation = "No WA X policy for ${mediaClass.label.lowercase()}; WhatsApp's own setting applies.",
            )
        }
        if (network == NetworkClass.OFFLINE) {
            return MediaDownloadDecision(
                action = MediaDownloadAction.DO_NOT_DOWNLOAD,
                choice = resolved.choice,
                source = resolved.source,
                explanation = "There is no connection, so nothing was fetched.",
            )
        }
        return when (resolved.choice) {
            MediaPolicyChoice.ALWAYS -> {
                MediaDownloadDecision(
                    action = MediaDownloadAction.DOWNLOAD_NOW,
                    choice = resolved.choice,
                    source = resolved.source,
                    explanation = "${mediaClass.label} are downloaded as they arrive.",
                )
            }

            MediaPolicyChoice.WIFI_ONLY -> {
                if (network == NetworkClass.UNMETERED) {
                    MediaDownloadDecision(
                        action = MediaDownloadAction.DOWNLOAD_NOW,
                        choice = resolved.choice,
                        source = resolved.source,
                        explanation = "${mediaClass.label} are downloaded on this unmetered connection.",
                    )
                } else {
                    MediaDownloadDecision(
                        action = MediaDownloadAction.WAIT_FOR_UNMETERED,
                        choice = resolved.choice,
                        source = resolved.source,
                        explanation = "${mediaClass.label} wait for an unmetered connection.",
                    )
                }
            }

            MediaPolicyChoice.NEVER -> {
                MediaDownloadDecision(
                    action = MediaDownloadAction.DO_NOT_DOWNLOAD,
                    choice = resolved.choice,
                    source = resolved.source,
                    explanation = "${mediaClass.label} are never downloaded in this chat.",
                )
            }

            MediaPolicyChoice.MANUAL_TAP_ONLY -> {
                MediaDownloadDecision(
                    action = MediaDownloadAction.WAIT_FOR_USER_TAP,
                    choice = resolved.choice,
                    source = resolved.source,
                    explanation = "${mediaClass.label} wait until you tap them.",
                )
            }

            // Only reachable when a scope stored USE_PARENT, which the resolver already
            // filtered out; kept exhaustive so adding a choice is a compile error here.
            MediaPolicyChoice.USE_PARENT -> {
                MediaDownloadDecision(
                    action = MediaDownloadAction.LEAVE_TO_WHATSAPP,
                    choice = MediaPolicyChoice.USE_PARENT,
                    source = resolved.source,
                    explanation = "No WA X policy for ${mediaClass.label.lowercase()}; WhatsApp's own setting applies.",
                )
            }
        }
    }
}
