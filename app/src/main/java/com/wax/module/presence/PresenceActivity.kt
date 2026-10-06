package com.wax.module.presence

/**
 * Something the other party starts doing in a chat before the message arrives.
 *
 * WhatsApp already knows all of this — it is what turns the contact's name into "typing…" and
 * what draws the upload ring on a media message — and WA X's job is only to make it audible and
 * visible to the user who asked for it. That framing matters for what is *not* in this enum:
 * there is no "reading your message", no "online", no "last seen" and no "typing in another
 * chat". Those are presence states the other party's client does not send to this one, and
 * inventing an activity that cannot be observed would be a claim the module cannot keep.
 *
 * Each activity names the client signal it is observed through, so a build that does not expose
 * that signal can say so instead of leaving a switch that does nothing.
 */
enum class PresenceActivity(
    /** Stable id. Written to storage and referenced by the settings UI, so it may be added to. */
    val id: String,
    /** Shown in the settings screen. */
    val label: String,
    /** The client surface this activity is observed through. */
    val signal: PresenceSignal,
) {
    /** Text being composed in the chat. */
    TYPING("typing", "Typing", PresenceSignal.CHAT_STATE),

    /** A voice message being recorded in the chat. */
    RECORDING_VOICE("recording_voice", "Recording a voice message", PresenceSignal.CHAT_STATE),

    /** A video message being recorded in the chat, the round preview in the composer. */
    RECORDING_VIDEO("recording_video", "Recording a video message", PresenceSignal.CHAT_STATE),

    /** A file or media message being uploaded into the chat before it is sent. */
    UPLOADING_MEDIA("uploading_media", "Sending media", PresenceSignal.MEDIA_TRANSFER),
    ;

    /** The sentence the settings screen shows under the switch. */
    fun describe(): String =
        when (this) {
            TYPING -> "Alert while this contact is composing a message in the chat."
            RECORDING_VOICE -> "Alert while this contact is recording a voice message."
            RECORDING_VIDEO -> "Alert while this contact is recording a video message."
            UPLOADING_MEDIA -> "Alert while this contact is uploading media into the chat."
        }

    companion object {
        /** The activity [id] names, or null when the stored value is not one this build knows. */
        fun fromId(id: String?): PresenceActivity? = entries.firstOrNull { it.id == id }

        /** Stable ids in declaration order, for the settings screen. */
        val IDS: List<String> = entries.map { it.id }
    }
}

/**
 * The client surface an activity is observed through.
 *
 * The two signals are kept apart because they fail independently: a build that stopped reporting
 * chat state still uploads media, and the other way round. Grouping them would mean one missing
 * hook silently disables activities that would have worked.
 */
enum class PresenceSignal(
    val label: String,
) {
    /** The chat-state update a client sends while the other party composes or records. */
    CHAT_STATE("chat state"),

    /** The transfer of a media message into the chat before it is sent. */
    MEDIA_TRANSFER("media transfer"),
    ;

    /** The sentence shown when this signal could not be found in the installed client. */
    fun missingReason(activity: PresenceActivity): String =
        "Unavailable on this WhatsApp build: it does not expose the $label update that " +
            "${activity.label.lowercase()} is observed through."
}

/**
 * What the installed client was found to expose.
 *
 * This is a report from the capability probe, not a preference, and the safe reading of a
 * missing report is the opposite of the safe reading of a negative one. [Unknown] means the
 * probe could not read the client, so an activity is still allowed and the reason says it is
 * unverified — the same rule the version gate applies to an undeclared build, and for the same
 * reason: a probe that fails must not silently disable a feature the user configured. A
 * [Resolved] report that is missing the signal is a positive statement that this build does not
 * send it, and there the switch is honestly unavailable.
 */
sealed interface PresenceCapability {
    /** Whether the probe could read the client at all. */
    val isReadable: Boolean

    /** Whether an activity observed through this capability may be alerted on. */
    fun isObservable(activity: PresenceActivity): Boolean

    /** Why an activity is or is not observable, for the log and the settings screen. */
    fun describe(activity: PresenceActivity): String

    /** The probe could not read the client. Activities are allowed and reported as unverified. */
    data object Unknown : PresenceCapability {
        override val isReadable: Boolean = false

        override fun isObservable(activity: PresenceActivity): Boolean = true

        override fun describe(activity: PresenceActivity): String =
            "${activity.label} alerts are unverified: the installed client's " +
                "${activity.signal.label} support could not be read."
    }

    /** The probe read the client and found exactly [signals]. */
    data class Resolved(
        val signals: Set<PresenceSignal>,
    ) : PresenceCapability {
        override val isReadable: Boolean = true

        override fun isObservable(activity: PresenceActivity): Boolean = activity.signal in signals

        override fun describe(activity: PresenceActivity): String =
            if (isObservable(activity)) {
                "${activity.label} alerts are available: this build reports ${activity.signal.label}."
            } else {
                activity.signal.missingReason(activity)
            }
    }
}
