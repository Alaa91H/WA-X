package com.wax.module.presence

import com.wax.module.notifications.CooldownWindows

/**
 * The conditions one activity's alert defers to.
 *
 * Grouped into their own value rather than three booleans on the rule for one reason: they are
 * answered by state the caller already has — whether the chat is muted, whether the contact is
 * blocked, whether quiet hours are active — and keeping them together makes "what this alert
 * respects" a single answer that can be handed to a filter, logged in one line, and tested
 * without constructing a whole rule. All three default to on, because an alert that ignores
 * them is the surprising case, not the expected one.
 *
 * Quiet hours are passed in rather than computed here. The notification platform already owns
 * the quiet-hours model and its time-zone handling; a second implementation of "is it quiet
 * now" inside the alert engine is exactly how two features come to disagree about 22:00.
 */
data class PresenceSuppressors(
    /** Do not alert for a chat the user muted. */
    val mutedChats: Boolean = true,
    /** Do not alert for a contact the user blocked. */
    val blockedContacts: Boolean = true,
    /** Do not alert while quiet hours are active. */
    val quietHours: Boolean = true,
) {
    /** The sentence the settings screen shows under the three switches. */
    fun describe(): String {
        val respected =
            buildList {
                if (mutedChats) add("muted chats")
                if (blockedContacts) add("blocked contacts")
                if (quietHours) add("quiet hours")
            }
        return if (respected.isEmpty()) {
            "This alert ignores muted chats, blocked contacts and quiet hours."
        } else {
            "This alert stays silent for ${respected.joinToString(", ")}."
        }
    }

    companion object {
        /** The alert that defers to nothing, for a user who wants it under every condition. */
        val NONE: PresenceSuppressors = PresenceSuppressors(mutedChats = false, blockedContacts = false, quietHours = false)
    }
}

/**
 * One activity's alert configuration at one scope.
 *
 * The rule is stored per (scope, activity) rather than as one document per scope holding a map of
 * activities, and the difference is inheritance. With a map per scope, a chat-level rule that
 * only names typing would still carry a cooldown value that *overrides* the device-wide one the
 * user set, because a stored document cannot say "leave this field alone" without every field
 * becoming nullable. With one document per (scope, activity), inheritance is the plain thing it
 * looks like: the most specific scope that has a rule for this activity wins, and a scope that
 * simply does not have one for an activity says nothing about it. Per-contact and per-activity
 * customisation therefore compose instead of fighting.
 */
data class PresenceAlertRule(
    /** The activity this rule configures. */
    val activity: PresenceActivity,
    /** How the alert is delivered. `NOTHING` is an explicit "silent here". */
    val style: PresenceAlertStyle = PresenceAlertStyle.NOTHING,
    /**
     * The shortest gap between two alerts for this activity in this chat.
     *
     * A peer keeps composing for as long as they type, so without a window every keystroke would
     * be an alert. Zero means no cooldown at all, which is a real choice and is documented as the
     * noisy one rather than hidden.
     */
    val cooldownMillis: Long = CooldownWindows.DEFAULT_MILLIS,
    /**
     * Whether the alert also fires for a chat the user is looking at.
     *
     * Off by default: a banner or a beep for the conversation already on screen is noise, and
     * WhatsApp itself treats the open chat as read. On is for a user watching a second chat.
     */
    val alertWhenChatIsOpen: Boolean = false,
    /** The conditions this alert stays silent for. */
    val suppressors: PresenceSuppressors = PresenceSuppressors(),
) {
    /** Whether this rule delivers nothing. */
    val isSilent: Boolean get() = style.isSilent

    /** Whether this rule has no cooldown, so every observation is an alert. */
    val hasNoCooldown: Boolean get() = cooldownMillis <= 0L

    /** One line for the settings summary and the audit log. Carries no message content. */
    fun describe(): String {
        val cooldown = if (hasNoCooldown) "no cooldown" else "${CooldownWindows.label(cooldownMillis)} cooldown"
        val whenOpen = if (alertWhenChatIsOpen) "also when the chat is open" else "only when the chat is not open"
        return "${activity.label}: ${style.label.lowercase()}, $cooldown, $whenOpen."
    }

    /** This rule with a different style. */
    fun withStyle(style: PresenceAlertStyle): PresenceAlertRule = copy(style = style)

    /** This rule with a different cooldown. */
    fun withCooldown(millis: Long): PresenceAlertRule = copy(cooldownMillis = millis.coerceAtLeast(0L))

    companion object {
        /** A silent rule for [activity], which is how a scope opts one activity out. */
        fun silent(activity: PresenceActivity): PresenceAlertRule = PresenceAlertRule(activity)

        /** The alert most users mean: a beep and the floating banner. */
        fun beepAndBanner(activity: PresenceActivity): PresenceAlertRule =
            PresenceAlertRule(activity, style = PresenceAlertStyle.BEEP_AND_BANNER)
    }
}
