package com.wax.module.presence

import com.wax.module.notifications.AlertChannel
import com.wax.module.outgoing.PolicyScope
import com.wax.module.outgoing.PolicyScopes
import com.wax.module.platform.ChatKind
import com.wax.module.platform.TargetApp

/**
 * Which chat an observation is about.
 *
 * The identity half of an observation, split from the moment half so the scope chain is built
 * once and can be reused for a second activity in the same chat — and so a caller cannot pass the
 * chat id of one chat with the account id of another by accident. `accountId` and `listId` are
 * optional for the same reason they are optional in [PolicyScopes]: a client that does not expose
 * them produces no scope at all rather than an empty one every consumer has to know to ignore.
 */
data class PresenceChatRef(
    val app: TargetApp,
    val chatId: String,
    val kind: ChatKind,
    val accountId: String? = null,
    val listId: String? = null,
) {
    /** The scope chain this chat's alert rules resolve through, least specific first. */
    fun scopes(): List<PolicyScope> = PolicyScopes.forChat(app, chatId, kind, accountId, listId)

    /** The key the burst state of one activity in this chat is held under. */
    internal fun burstKey(activity: PresenceActivity): String = "$chatId|${activity.id}"
}

/**
 * The conditions of the moment an activity was observed in.
 *
 * All four are things the caller already knows and the engine must not guess. They are passed in
 * rather than looked up so the decision stays a pure function of its inputs, which is what lets
 * every one of them be tested without a device, a muted chat or a clock.
 */
data class PresenceChatState(
    /** The user muted this chat. */
    val chatIsMuted: Boolean = false,
    /** The user blocked this contact. */
    val contactIsBlocked: Boolean = false,
    /** This chat is the one on screen. */
    val chatIsOpen: Boolean = false,
    /** Quiet hours are active now, as decided by the notification platform's own model. */
    val quietHoursActive: Boolean = false,
)

/**
 * One observed activity, ready to be decided on.
 *
 * [isFromMe] has no default, and that is the point: the single most damaging mistake this feature
 * can make is alerting the user to their own typing, and a default would let a caller who forgot
 * the field ship exactly that bug. Making it required means the compiler asks the question.
 */
data class PresenceObservation(
    /** Which chat, and therefore which scope chain the rules resolve through. */
    val ref: PresenceChatRef,
    /** What the other party started doing. */
    val activity: PresenceActivity,
    /** Whether this activity is the user's own. Own activity never alerts. */
    val isFromMe: Boolean,
    /** The conditions of the moment. */
    val state: PresenceChatState = PresenceChatState(),
)

/**
 * What one observed activity should do, and why.
 *
 * The decision carries the activity, the style, the channels and a sentence — and no message
 * text, no media name and no phone number, because it is written to the audit log and read back
 * by diagnostics. The reason is a full sentence rather than a code so the settings screen can
 * show the same explanation the log recorded, instead of the screen inventing its own wording for
 * a decision it did not make.
 */
data class PresenceAlertDecision(
    val alert: Boolean,
    val activity: PresenceActivity,
    val style: PresenceAlertStyle,
    val channels: Set<AlertChannel>,
    val reason: String,
) {
    val soundEnabled: Boolean get() = alert && AlertChannel.SOUND in channels

    val bannerEnabled: Boolean get() = alert && AlertChannel.HEADS_UP in channels

    val vibrationEnabled: Boolean get() = alert && AlertChannel.VIBRATION in channels

    /** Whether the decision was to stay silent. */
    val isSilent: Boolean get() = !alert

    /** One line for the audit log. */
    fun toDisplayLine(): String {
        if (!alert) return "presence ${activity.id}: silent — $reason"
        val names = channels.joinToString(",") { it.name.lowercase() }
        return "presence ${activity.id}: alert on $names — $reason"
    }

    companion object {
        /** A silent decision with the reason why. */
        fun silent(
            activity: PresenceActivity,
            style: PresenceAlertStyle = PresenceAlertStyle.NOTHING,
            reason: String,
        ): PresenceAlertDecision =
            PresenceAlertDecision(
                alert = false,
                activity = activity,
                style = style,
                channels = emptySet(),
                reason = reason,
            )
    }
}
