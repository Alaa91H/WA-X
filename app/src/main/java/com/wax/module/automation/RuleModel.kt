package com.wax.module.automation

import java.time.DayOfWeek
import java.time.LocalTime

/** The message shapes a rule can key on. */
enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    VOICE,
    DOCUMENT,
    STICKER,
    CONTACT,
    LOCATION,
    OTHER,
}

/**
 * One condition in a rule.
 *
 * Conditions are values so they can be stored, rendered, simulated and tested without an
 * Android context. The simulator (T101) depends on this: it must be able to say *which*
 * condition failed, which is only possible when conditions are first-class data rather than
 * lambdas.
 *
 * A condition that cannot be evaluated on the current platform (an unknown package, a
 * malformed regex) evaluates to false and reports why; it never throws.
 */
sealed interface RuleCondition {
    /** The chat is exactly this contact. */
    data class Sender(
        val chatId: String,
    ) : RuleCondition

    /** The event is in exactly this group. */
    data class GroupChat(
        val groupId: String,
    ) : RuleCondition

    /** The message is of this type. */
    data class MessageTypeIs(
        val type: MessageType,
    ) : RuleCondition

    /** The text contains [text]. Case-insensitive unless [caseSensitive]. */
    data class Keyword(
        val text: String,
        val caseSensitive: Boolean = false,
    ) : RuleCondition

    /** The text matches [pattern]. An invalid pattern fails the condition. */
    data class RegexMatch(
        val pattern: String,
        val options: Set<RegexOption> = emptySet(),
    ) : RuleCondition

    /** The local time is inside the window; end earlier than start crosses midnight. */
    data class TimeWindow(
        val start: LocalTime,
        val end: LocalTime,
    ) : RuleCondition

    /** The event happens on one of [days]. */
    data class Weekdays(
        val days: Set<DayOfWeek>,
    ) : RuleCondition

    /** Wi-Fi connectivity state. */
    data class Wifi(
        val connected: Boolean,
    ) : RuleCondition

    /** Charging state. */
    data class Charging(
        val charging: Boolean,
    ) : RuleCondition

    /** Battery level strictly below [percent]. Unknown battery fails the condition. */
    data class BatteryBelow(
        val percent: Int,
    ) : RuleCondition

    /** The event came from this target package. */
    data class PackageProfile(
        val packageName: String,
    ) : RuleCondition
}

/**
 * One action a rule can take.
 *
 * Actions are data too, so the conflict analyser (T100) can reason about them and the audit
 * log can name them without executing anything.
 */
sealed interface RuleAction {
    /** Reply to the chat with [text]. */
    data class AutoReply(
        val text: String,
    ) : RuleAction

    /** Mute the chat. */
    data object MuteChat : RuleAction

    /** Mark the chat to be revisited later. */
    data object MarkLater : RuleAction

    /** Bookmark the message into [collectionId]. */
    data class Bookmark(
        val collectionId: String,
    ) : RuleAction

    /** Save the message's media locally. */
    data object SaveMedia : RuleAction

    /** Raise a local notification with [title] and [text]. */
    data class Notify(
        val title: String,
        val text: String,
    ) : RuleAction

    /** Switch the active privacy profile to [profileId]. */
    data class SwitchPrivacyProfile(
        val profileId: String,
    ) : RuleAction

    /** Send a Tasker event named [name] with an optional [payload]. */
    data class TaskerEvent(
        val name: String,
        val payload: String? = null,
    ) : RuleAction

    /** A stable id used by conflicts, audit and diagnostics. */
    val actionId: String
        get() =
            when (this) {
                is AutoReply -> "auto_reply"
                MuteChat -> "mute_chat"
                MarkLater -> "mark_later"
                is Bookmark -> "bookmark"
                SaveMedia -> "save_media"
                is Notify -> "notify"
                is SwitchPrivacyProfile -> "switch_profile"
                is TaskerEvent -> "tasker_event"
            }
}

/**
 * One incoming event a rule may react to.
 *
 * @param fromAutomation true when the module itself produced this event. The engine refuses
 *   to evaluate those, which is the primary loop-prevention mechanism: a rule whose
 *   auto-reply produces another message must not be able to reply to its own reply.
 */
data class RuleEvent(
    val chatId: String,
    val senderId: String,
    val isGroup: Boolean,
    val messageType: MessageType,
    val text: String,
    val time: LocalTime,
    val dayOfWeek: DayOfWeek,
    val wifiConnected: Boolean,
    val charging: Boolean,
    val batteryPercent: Int?,
    val packageName: String?,
    val messageId: String = "",
    val fromAutomation: Boolean = false,
) {
    /** Whether the event is in [groupId]. */
    fun isFromGroup(groupId: String): Boolean = isGroup && chatId == groupId
}

/**
 * One automation rule: conditions (AND) then actions (in order).
 *
 * @param priority higher runs first; ties keep declaration order, which makes priority an
 *   explicit decision rather than an emergent one
 * @param stopProcessing when true, no rule after this one runs for this event
 */
data class AutomationRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val conditions: List<RuleCondition>,
    val actions: List<RuleAction>,
    val priority: Int = 0,
    val stopProcessing: Boolean = false,
    val createdAtMillis: Long,
) {
    /** One line for the rules list. */
    fun toDisplayLine(): String =
        "$name (priority $priority, ${conditions.size} condition(s), ${actions.size} action(s))" +
            if (enabled) "" else " [disabled]"
}

/** The result of testing one rule against one event. */
data class ConditionMatchResult(
    val matched: Boolean,
    val matchedConditions: List<RuleCondition>,
    val failedConditions: List<RuleCondition>,
) {
    /** One line for the simulator. */
    fun toDisplayLine(): String =
        buildString {
            append(if (matched) "matched" else "not matched")
            if (failedConditions.isNotEmpty()) {
                append("; failed: ")
                append(failedConditions.joinToString(", ") { it.describe() })
            }
        }
}

/** A short description of a condition, used by the simulator and diagnostics. */
fun RuleCondition.describe(): String =
    when (this) {
        is RuleCondition.Sender -> "sender=$chatId"
        is RuleCondition.GroupChat -> "group=$groupId"
        is RuleCondition.MessageTypeIs -> "type=${type.name.lowercase()}"
        is RuleCondition.Keyword -> "keyword" + if (caseSensitive) " (case-sensitive)" else ""
        is RuleCondition.RegexMatch -> "regex"
        is RuleCondition.TimeWindow -> "time $start-$end"
        is RuleCondition.Weekdays -> "weekdays " + days.joinToString(",") { it.name.take(3) }
        is RuleCondition.Wifi -> "wifi=$connected"
        is RuleCondition.Charging -> "charging=$charging"
        is RuleCondition.BatteryBelow -> "battery<$percent%"
        is RuleCondition.PackageProfile -> "package=$packageName"
    }

/** A short description of an action, used by the simulator and audit. */
fun RuleAction.describe(): String =
    when (this) {
        is RuleAction.AutoReply -> "auto-reply (${text.length} chars)"
        RuleAction.MuteChat -> "mute chat"
        RuleAction.MarkLater -> "mark later"
        is RuleAction.Bookmark -> "bookmark to $collectionId"
        RuleAction.SaveMedia -> "save media"
        is RuleAction.Notify -> "notify"
        is RuleAction.SwitchPrivacyProfile -> "switch profile to $profileId"
        is RuleAction.TaskerEvent -> "tasker event"
    }
