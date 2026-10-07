package com.wax.module.presence

import com.wax.module.notifications.CooldownRule
import com.wax.module.notifications.CooldownWindows
import com.wax.module.notifications.NotificationCooldownEngine
import com.wax.module.notifications.NotificationCooldownStore
import com.wax.module.platform.InMemoryKeyValueStore

/**
 * Decides whether one observed activity should alert, and why.
 *
 * The engine is a pure function of its inputs plus the burst state it holds, so every rule below
 * is testable without a device, a muted chat or a clock. It answers three questions in a fixed
 * order — can this be observed at all, does the user want it here, and has it already been
 * reported recently — and the order matters: a capability answer is about the build, a rule
 * answer is about the user's choice, and a burst answer is about the last few seconds, and mixing
 * them would produce one sentence for three different situations.
 *
 * The burst state is not reimplemented here. "The peer keeps composing for as long as they type"
 * is the same problem the notification cooldown already solves — first alert of a burst is
 * delivered, the rest of the window is absorbed, and the window is anchored to the first alert so
 * a long session cannot push it forward forever — so this engine composes that engine and hands
 * it the rule for the activity. The injected cooldown engine is given an empty rule store on
 * purpose: every call passes an explicit rule derived from the presence rule, so the store would
 * never be read, and an empty one makes that visible instead of hiding a second source of truth.
 */
class PresenceAlertEngine(
    private val rules: PresenceAlertStore,
    private val cooldowns: NotificationCooldownEngine =
        NotificationCooldownEngine(NotificationCooldownStore(InMemoryKeyValueStore())),
) {
    /**
     * What to do about [observation].
     *
     * [capability] defaults to [PresenceCapability.Unknown], which allows the activity and says so
     * in the reason. That is the deliberate direction: a probe that could not read the client must
     * not silence a feature the user configured, the same way an undeclared WhatsApp build is
     * loaded and marked unverified rather than refused.
     */
    fun decide(
        observation: PresenceObservation,
        capability: PresenceCapability = PresenceCapability.Unknown,
    ): PresenceAlertDecision {
        val activity = observation.activity
        if (observation.isFromMe) {
            return PresenceAlertDecision.silent(activity, reason = "This is your own activity in this chat.")
        }
        if (!capability.isObservable(activity)) {
            return PresenceAlertDecision.silent(activity, reason = capability.describe(activity))
        }
        val note = if (capability.isReadable) "" else " ${capability.describe(activity)}"
        val rule =
            rules.effectiveRule(observation.ref.scopes(), activity)
                ?: return PresenceAlertDecision.silent(
                    activity,
                    reason = "No alert is configured for ${activity.label.lowercase()} in this chat.$note",
                )
        if (rule.isSilent) {
            return PresenceAlertDecision.silent(
                activity,
                rule.style,
                "This chat is set to stay silent for ${activity.label.lowercase()}.$note",
            )
        }
        suppressedBy(rule, observation)?.let { reason ->
            return PresenceAlertDecision.silent(activity, rule.style, reason + note)
        }
        return deliver(observation, rule, note)
    }

    /** Forgets the burst state of every activity in [chatId], for when the chat is opened or read. */
    fun forget(chatId: String): Boolean {
        var forgotten = false
        PresenceActivity.entries.forEach { activity ->
            forgotten = cooldowns.forget("$chatId|${activity.id}") || forgotten
        }
        return forgotten
    }

    /** Drops every burst this engine is holding. Used by tests and by a factory reset. */
    fun clear() {
        cooldowns.clear()
    }

    /** The first condition, in the order they are documented, that silences this alert. */
    private fun suppressedBy(
        rule: PresenceAlertRule,
        observation: PresenceObservation,
    ): String? {
        val state = observation.state
        val suppressors = rule.suppressors
        val what = observation.activity.label.lowercase()
        return when {
            suppressors.mutedChats && state.chatIsMuted -> {
                "This chat is muted, and $what alerts respect that."
            }

            suppressors.blockedContacts && state.contactIsBlocked -> {
                "This contact is blocked, and $what alerts respect that."
            }

            suppressors.quietHours && state.quietHoursActive -> {
                "Quiet hours are active, and $what alerts respect those."
            }

            !rule.alertWhenChatIsOpen && state.chatIsOpen -> {
                "This chat is open, and this alert is set for chats you are not looking at."
            }

            else -> {
                null
            }
        }
    }

    /** Delivers the alert unless the same activity in the same chat already alerted recently. */
    private fun deliver(
        observation: PresenceObservation,
        rule: PresenceAlertRule,
        note: String,
    ): PresenceAlertDecision {
        val activity = observation.activity
        val burst =
            cooldowns.decide(
                observation.ref.scopes(),
                observation.ref.burstKey(activity),
                cooldownRuleFor(rule),
            )
        if (!burst.alert) {
            return PresenceAlertDecision.silent(
                activity,
                rule.style,
                "Already alerted for this burst; ${burst.burstCount - 1} further observation(s) " +
                    "inside the ${CooldownWindows.label(rule.cooldownMillis)} window.$note",
            )
        }
        val cadence = if (rule.hasNoCooldown) "no cooldown" else "at most once per ${CooldownWindows.label(rule.cooldownMillis)}"
        return PresenceAlertDecision(
            alert = true,
            activity = activity,
            style = rule.style,
            channels = rule.style.channels,
            reason = "${activity.label} alert as configured for this chat: ${rule.style.label.lowercase()}, $cadence.$note",
        )
    }

    /** The burst rule a presence rule implies. Zero cooldown means the cooldown is switched off. */
    private fun cooldownRuleFor(rule: PresenceAlertRule): CooldownRule =
        CooldownRule(
            enabled = !rule.hasNoCooldown,
            windowMillis = rule.cooldownMillis,
            channels = rule.style.channels,
        )
}
