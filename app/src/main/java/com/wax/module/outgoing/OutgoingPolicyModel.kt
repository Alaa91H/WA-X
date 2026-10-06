package com.wax.module.outgoing

import com.wax.module.platform.ChatKind
import com.wax.module.platform.TargetApp

/**
 * The kinds of outgoing item an outgoing policy can talk about.
 *
 * One vocabulary for both policies is deliberate. Timed Delete for Everyone applies to a
 * generic outgoing message identity rather than to text only, and View Once applies to a
 * narrow subset of the same list, so a single enum is what stops two near-identical type
 * lists from disagreeing about what a "voice message" is.
 *
 * [VOICE_MESSAGE] and [AUDIO_FILE] are separate members and must stay separate. A native
 * voice message is recorded in WhatsApp and can use View Once on current versions; an audio
 * file picked from storage is an ordinary attachment and cannot. Collapsing them is how a
 * random MP3 would end up being sent as a View Once item.
 */
enum class OutgoingMessageClass(
    /** User-facing name, used in the policy editor and in diagnostics. */
    val label: String,
) {
    TEXT("Text"),
    EMOJI("Emoji"),
    PHOTO("Photo"),
    VIDEO("Video"),
    VOICE_MESSAGE("Voice message"),
    AUDIO_FILE("Audio file"),
    DOCUMENT("Document"),
    STICKER("Sticker"),
    GIF("GIF"),
    CONTACT_CARD("Contact card"),
    LOCATION("Location"),
    POLL("Poll"),
    OTHER("Other"),
    ;

    /** Every media class, for the "all types" default of a policy. */
    companion object {
        /** The classes the media policy and the revocation queue treat as attachment-like. */
        val ATTACHMENTS: Set<OutgoingMessageClass> =
            setOf(PHOTO, VIDEO, VOICE_MESSAGE, AUDIO_FILE, DOCUMENT, STICKER, GIF, CONTACT_CARD, LOCATION)
    }
}

/**
 * The `view_once.*` capability ids, and which class each one governs.
 *
 * The mapping lives here rather than in the policy because it is compatibility metadata: it
 * says what the installed client supports, and a future WhatsApp version that adds native
 * View Once for another class is enabled by adding an id here and a resolver reading it — not
 * by changing the policy. A class with no id cannot use native View Once at all, which is the
 * safe answer rather than an emulation.
 */
object OutgoingCapabilities {
    /** Native View Once for photos. */
    const val VIEW_ONCE_PHOTO = "view_once.photo"

    /** Native View Once for videos. */
    const val VIEW_ONCE_VIDEO = "view_once.video"

    /** Native View Once for native voice messages. */
    const val VIEW_ONCE_VOICE_MESSAGE = "view_once.voice_message"

    /**
     * Native Delete for Everyone, whose window is resolved per target and version.
     *
     * There is no `timed_revoke.text` / `timed_revoke.photo` split on purpose: the client
     * exposes one revocation capability, and the feature routes every eligible class through
     * the same queue rather than keeping a scheduler per media type.
     */
    const val TIMED_REVOKE = "timed_revoke.any_message"

    /** The capability id that governs View Once for [messageClass], or null when none does. */
    fun viewOnceIdFor(messageClass: OutgoingMessageClass): String? =
        when (messageClass) {
            OutgoingMessageClass.PHOTO -> VIEW_ONCE_PHOTO
            OutgoingMessageClass.VIDEO -> VIEW_ONCE_VIDEO
            OutgoingMessageClass.VOICE_MESSAGE -> VIEW_ONCE_VOICE_MESSAGE
            else -> null
        }

    /** The classes that may use native View Once, in the order the policy editor lists them. */
    @JvmField
    val VIEW_ONCE_CLASSES: List<OutgoingMessageClass> =
        listOf(OutgoingMessageClass.PHOTO, OutgoingMessageClass.VIDEO, OutgoingMessageClass.VOICE_MESSAGE)
}

/** The preset durations the auto-delete policy offers. */
object AutoDeleteDurations {
    const val SECOND: Long = 1_000L
    const val MINUTE: Long = 60 * SECOND
    const val HOUR: Long = 60 * MINUTE

    /** The presets, in the order the interface shows them. */
    @JvmField
    val PRESETS: List<Long> =
        listOf(
            30 * SECOND,
            MINUTE,
            5 * MINUTE,
            15 * MINUTE,
            30 * MINUTE,
            HOUR,
            6 * HOUR,
            12 * HOUR,
            24 * HOUR,
        )

    /** Whether [millis] is one of the offered presets. */
    fun isPreset(millis: Long): Boolean = millis in PRESETS

    /**
     * A short label for a delay.
     *
     * Derived from the value rather than stored, so a custom delay and a preset are described
     * by the same code and cannot disagree with each other.
     */
    fun label(millis: Long): String =
        when {
            millis <= 0L -> "disabled"
            millis % HOUR == 0L -> plural(millis / HOUR, "hour")
            millis % MINUTE == 0L -> plural(millis / MINUTE, "minute")
            millis % SECOND == 0L -> plural(millis / SECOND, "second")
            else -> "$millis ms"
        }

    private fun plural(
        count: Long,
        unit: String,
    ): String = if (count == 1L) "1 $unit" else "$count ${unit}s"
}

/** What a scope says about View Once for one media class. */
enum class ViewOnceChoice(
    val label: String,
) {
    /** No opinion here; whatever a lower-precedence scope said applies. */
    USE_PARENT("Use chat policy"),

    /** Never send this class as View Once from this scope. */
    NORMAL("Normal"),

    /** Always send this class as View Once from this scope. */
    ALWAYS_VIEW_ONCE("View Once"),

    /** Ask before each send. */
    ASK_EACH_TIME("Ask each time"),
}

/** What a scope says about timed Delete for Everyone. */
enum class AutoDeleteChoice {
    /** No opinion here. */
    USE_PARENT,

    /** Do not schedule a revocation from this scope. */
    DISABLED,

    /** Schedule a revocation from this scope. */
    ENABLED,
}

/**
 * What to do when the chosen delay is past the window the installed client still allows.
 *
 * [DO_NOTHING] is the default and [DELETE_LOCAL_COPY_ONLY] has to be chosen explicitly,
 * because a failed Delete for Everyone that quietly becomes a Delete for Me would remove the
 * message from the user's own device while leaving it on everyone else's — the opposite of
 * what they asked for.
 */
enum class ExpiredWindowFallback {
    /** Leave the message alone and say so. */
    DO_NOTHING,

    /** Remove the local copy only, having told the user that is all that will happen. */
    DELETE_LOCAL_COPY_ONLY,
}

/**
 * Where a policy value came from.
 *
 * The order of the members is the conflict-resolution order from the feature contract, and
 * [precedence] makes it comparable instead of a comment. The most specific scope wins.
 */
sealed interface PolicyScope {
    /** Higher wins. */
    val precedence: Int

    /** Stable code used in storage keys and diagnostics. */
    val code: String

    /** Name used in the policy editor and in the send-time indicator. */
    val label: String

    /** The device-wide default. */
    data object Global : PolicyScope {
        override val precedence: Int = 0
        override val code: String = "global"
        override val label: String = "Global"
    }

    /** One hooked application. */
    data class Target(
        val app: TargetApp,
    ) : PolicyScope {
        override val precedence: Int = 1
        override val code: String get() = "target.${app.code}"
        override val label: String get() = app.displayName
    }

    /** One account inside a target, where the client exposes account identity. */
    data class Account(
        val app: TargetApp,
        val accountId: String,
    ) : PolicyScope {
        override val precedence: Int = 2
        override val code: String get() = "account.${app.code}.$accountId"
        override val label: String get() = "Account"
    }

    /** A native WhatsApp list (folder). */
    data class ListScope(
        val app: TargetApp,
        val listId: String,
    ) : PolicyScope {
        override val precedence: Int = 3
        override val code: String get() = "list.${app.code}.$listId"
        override val label: String get() = "List"
    }

    /** One contact or group. */
    data class Chat(
        val app: TargetApp,
        val chatId: String,
        val kind: ChatKind,
    ) : PolicyScope {
        override val precedence: Int = 4
        override val code: String get() = "chat.${app.code}.${kind.name.lowercase()}.$chatId"
        override val label: String get() = if (kind == ChatKind.GROUP) "Group" else "Contact"
    }

    /**
     * A one-time choice made for a single send.
     *
     * Never persisted: the whole point is that it applies to the message being sent and
     * leaves the stored policy alone.
     */
    data class Message(
        val app: TargetApp,
    ) : PolicyScope {
        override val precedence: Int = 5
        override val code: String get() = "message.${app.code}"
        override val label: String get() = "this message"
    }

    companion object {
        /** Every scope a user can configure, in precedence order. */
        val CONFIGURABLE: List<PolicyScope>
            get() =
                buildList {
                    add(Global)
                    TargetApp.entries.forEach { add(Target(it)) }
                }

        /**
         * Parses a [code] back to a scope, or null when it is not one of ours.
         *
         * An unparsable code is null rather than a guess: a stored policy whose scope cannot be
         * identified must be ignored, because attaching it to the wrong chat is worse than
         * losing it. The two helpers below keep this readable, since the shapes differ — a
         * chat scope carries its kind before its identifier and the others do not.
         */
        fun parse(code: String?): PolicyScope? {
            if (code.isNullOrBlank()) return null
            val parts = code.split('.')
            val app = parts.getOrNull(1)?.let { TargetApp.fromCode(it) }
            val identifier = identifierOf(parts)
            return when (parts.firstOrNull()) {
                "global" -> Global
                "target" -> app?.let(::Target)
                "account" -> if (app != null && identifier != null) Account(app, identifier) else null
                "list" -> if (app != null && identifier != null) ListScope(app, identifier) else null
                "chat" -> parseChat(app, parts)
                "message" -> app?.let(::Message)
                else -> null
            }
        }

        /** Everything after the target segment, or null when there is nothing left. */
        private fun identifierOf(parts: List<String>): String? = parts.drop(2).joinToString(".").ifBlank { null }

        /** A chat scope, whose identifier starts after the kind segment. */
        private fun parseChat(
            app: TargetApp?,
            parts: List<String>,
        ): PolicyScope? {
            if (app == null) return null
            val kind = ChatKind.entries.firstOrNull { it.name.lowercase() == parts.getOrNull(2) } ?: return null
            val identifier = parts.drop(3).joinToString(".").ifBlank { return null }
            return Chat(app, identifier, kind)
        }
    }
}

/**
 * One scope's policy, stored sparsely.
 *
 * Absent fields mean "no opinion here", never "off". That is the same rule the privacy
 * overrides follow, and it is what lets a contact override a group-level delay while still
 * inheriting the group's choice of message types.
 */
data class OutgoingPolicyLayer(
    /** Per class, so one chat can send photos as View Once and voice messages normally. */
    val viewOnce: Map<OutgoingMessageClass, ViewOnceChoice> = emptyMap(),
    /** Whole-scope auto-delete choice. */
    val autoDelete: AutoDeleteChoice = AutoDeleteChoice.USE_PARENT,
    /** The delay, when [autoDelete] is ENABLED here. */
    val autoDeleteDelayMillis: Long? = null,
    /** The classes auto delete applies to; null means every revocable class. */
    val autoDeleteClasses: Set<OutgoingMessageClass>? = null,
    /** What to do when the delay is outside the client's revocation window. */
    val expiredWindowFallback: ExpiredWindowFallback = ExpiredWindowFallback.DO_NOTHING,
) {
    /** Whether this layer says anything at all. */
    val isEmpty: Boolean
        get() = viewOnce.isEmpty() && autoDelete == AutoDeleteChoice.USE_PARENT && autoDeleteClasses == null

    /** Merges [next] over this layer, field by field, with [next] winning where it speaks. */
    fun over(next: OutgoingPolicyLayer): OutgoingPolicyLayer =
        OutgoingPolicyLayer(
            viewOnce = viewOnce + next.viewOnce,
            autoDelete = if (next.autoDelete != AutoDeleteChoice.USE_PARENT) next.autoDelete else autoDelete,
            autoDeleteDelayMillis = next.autoDeleteDelayMillis ?: autoDeleteDelayMillis,
            autoDeleteClasses = next.autoDeleteClasses ?: autoDeleteClasses,
            expiredWindowFallback =
                if (next.expiredWindowFallback != ExpiredWindowFallback.DO_NOTHING) {
                    next.expiredWindowFallback
                } else {
                    expiredWindowFallback
                },
        )
}

/** Whether a capability the outgoing policies need is present on the installed client. */
fun interface CapabilityProbe {
    /** Whether [capabilityId] resolves on this target and version. */
    fun supports(capabilityId: String): Boolean

    companion object {
        /** A probe that answers yes to everything. Used by tests and by a permissive config. */
        val AllSupported: CapabilityProbe = CapabilityProbe { true }

        /** A probe that answers no to everything, for testing the blocked paths. */
        val NoneSupported: CapabilityProbe = CapabilityProbe { false }
    }
}

/**
 * How long the installed client still allows Delete for Everyone for.
 *
 * This is a probe rather than a constant on purpose. WhatsApp documents a finite revocation
 * window and has changed it before, so a hardcoded maximum would either promise a deletion
 * the client will refuse or refuse one it would honour. The engine reads the window from
 * here and never assumes a value.
 */
fun interface RevokeWindowProbe {
    /**
     * The longest delay that still supports Delete for Everyone, in milliseconds.
     *
     * Zero means "could not be determined" and is never read as "no window": the engine
     * treats the two cases differently, and only the unknown case is recoverable.
     */
    fun maxRevokeWindowMillis(): Long

    companion object {
        /** The window could not be resolved. Remote deletion must not be promised. */
        val Unknown: RevokeWindowProbe = RevokeWindowProbe { 0L }
    }
}
