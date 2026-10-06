package com.wax.module.notifications

import com.wax.module.outgoing.PolicyScope
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.boolean
import com.wax.module.platform.jsonBoolean
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonStrings
import com.wax.module.platform.long
import com.wax.module.platform.objOrNull
import com.wax.module.platform.stringList

/**
 * One part of an alert that a burst cooldown can silence without silencing the others.
 *
 * The split is what makes the feature usable rather than a mute button: a busy group usually
 * wants the head-up banner gone while the vibration still tells the user something arrived,
 * and an alert that silenced all three would be indistinguishable from Do Not Disturb.
 */
enum class AlertChannel(
    /** Name shown in the cooldown editor. */
    val label: String,
) {
    SOUND("Sound"),
    VIBRATION("Vibration"),
    HEADS_UP("Heads-up"),
}

/** The cooldown windows the editor offers, and a label for any value it is given. */
object CooldownWindows {
    const val SECOND: Long = 1_000L
    const val MINUTE: Long = 60 * SECOND

    /** The window a fresh rule starts on: long enough to absorb a burst, short enough to notice. */
    const val DEFAULT_MILLIS: Long = 20 * SECOND

    /** The presets, in the order the interface shows them. */
    @JvmField
    val PRESETS: List<Long> = listOf(10 * SECOND, 20 * SECOND, 30 * SECOND, MINUTE, 5 * MINUTE)

    /** Whether [millis] is one of the offered presets. */
    fun isPreset(millis: Long): Boolean = millis in PRESETS

    /** A short label derived from the value, so a preset and a custom window read the same way. */
    fun label(millis: Long): String =
        when {
            millis <= 0L -> "off"
            millis % MINUTE == 0L -> plural(millis / MINUTE, "minute")
            millis % SECOND == 0L -> plural(millis / SECOND, "second")
            else -> "$millis ms"
        }

    private fun plural(
        count: Long,
        unit: String,
    ): String = if (count == 1L) "1 $unit" else "$count ${unit}s"
}

/**
 * What one scope says about absorbing message bursts.
 *
 * [channels] lists the channels the cooldown silences, so a rule can keep vibration alive while
 * the sound and the banner are suppressed. A rule with no channels, a non-positive window or
 * [enabled] false is an explicit "off": it is stored, and it deliberately overrides a more
 * general rule, which is how one chat opts out of a device-wide cooldown.
 */
data class CooldownRule(
    val enabled: Boolean = true,
    val windowMillis: Long = CooldownWindows.DEFAULT_MILLIS,
    val channels: Set<AlertChannel> = AlertChannel.entries.toSet(),
) {
    /** Whether this rule suppresses anything at all. */
    val isDisabled: Boolean get() = !enabled || windowMillis <= 0L || channels.isEmpty()

    /** One line for the settings summary. */
    fun describe(): String {
        if (isDisabled) return "Cooldown off."
        val names = channels.sortedBy { it.ordinal }.joinToString(", ") { it.label.lowercase() }
        return "Silence $names for ${CooldownWindows.label(windowMillis)} after the first message."
    }

    companion object {
        /** The default is off: stock notification behaviour is the baseline, not a burst filter. */
        val Disabled = CooldownRule(enabled = false)

        /** A rule with the feature on and every channel silenced. */
        val Default = CooldownRule()
    }
}

/**
 * What the notification path should do with one arriving message.
 *
 * The decision carries the muted set rather than a single boolean because the channels are
 * independent: `soundEnabled`, `vibrationEnabled` and `headsUpEnabled` are the three questions
 * the notifier actually asks.
 */
data class CooldownDecision(
    /** Whether this is the first message of a burst, which always alerts normally. */
    val alert: Boolean,
    val mutedChannels: Set<AlertChannel>,
    /** How many messages this burst has absorbed, including the one being decided. */
    val burstCount: Int,
    /** How long the cooldown still applies for, or zero when it has passed. */
    val cooldownRemainingMillis: Long,
    /** Sanitized sentence for diagnostics. Never contains the chat identifier. */
    val explanation: String,
) {
    val soundEnabled: Boolean get() = AlertChannel.SOUND !in mutedChannels

    val vibrationEnabled: Boolean get() = AlertChannel.VIBRATION !in mutedChannels

    val headsUpEnabled: Boolean get() = AlertChannel.HEADS_UP !in mutedChannels

    /** Whether anything was suppressed. */
    val isSuppressed: Boolean get() = mutedChannels.isNotEmpty()

    /** One line for the audit log. */
    fun toDisplayLine(): String {
        if (!isSuppressed) return "cooldown: alerting (burst $burstCount)"
        val names = mutedChannels.joinToString(",") { it.name.lowercase() }
        return "cooldown: burst $burstCount, muted $names, ${cooldownRemainingMillis}ms left"
    }
}

/**
 * Where each scope's cooldown rule lives.
 *
 * One key per scope rather than one key per field, for the same reason the outgoing policy
 * store does it: a rule is written and read as a unit, so a half-applied rule cannot exist on
 * disk. A stored value that cannot be decoded is treated as "this scope has no opinion", which
 * inherits rather than muting — the safe direction, since a corrupt entry must never silence a
 * user's notifications.
 */
class NotificationCooldownStore(
    private val store: KeyValueStore,
) {
    /** The rule stored for [scope], or null when this scope has no opinion. */
    fun ruleFor(scope: PolicyScope): CooldownRule? {
        val text = store.getString(keyFor(scope)) ?: return null
        return decodeRule(text)
    }

    /** Stores [rule] for [scope]. */
    fun save(
        scope: PolicyScope,
        rule: CooldownRule,
    ) {
        store.putString(keyFor(scope), MiniJson.write(encodeRule(rule)))
    }

    /** Removes a scope's rule so it follows its parent again. */
    fun remove(scope: PolicyScope): Boolean {
        val key = keyFor(scope)
        if (store.getString(key) == null) return false
        store.remove(key)
        return true
    }

    /**
     * The rule that applies to [chain], most specific first.
     *
     * The first scope that has an opinion wins, including an explicitly disabled one, so a chat
     * can opt out of a device-wide cooldown by storing `CooldownRule.Disabled` rather than by
     * having to delete the parent rule.
     */
    fun effectiveRule(chain: List<PolicyScope>): CooldownRule =
        chain
            .sortedByDescending { it.precedence }
            .firstNotNullOfOrNull { ruleFor(it) }
            ?: CooldownRule.Disabled

    /** Every scope that currently holds a rule, in precedence order. */
    fun configuredScopes(): List<PolicyScope> =
        store
            .keys(KEY_PREFIX)
            .mapNotNull { PolicyScope.parse(it.removePrefix(KEY_PREFIX)) }
            .sortedBy { it.precedence }

    /** Removes every rule. Used by tests and by a factory reset. */
    fun clear() {
        store.keys(KEY_PREFIX).forEach { store.remove(it) }
    }

    private fun keyFor(scope: PolicyScope): String = KEY_PREFIX + scope.code

    private fun encodeRule(rule: CooldownRule) =
        jsonObject(
            "enabled" to jsonBoolean(rule.enabled),
            "window" to jsonNumber(rule.windowMillis),
            "channels" to jsonStrings(rule.channels.map { it.name }),
        )

    private fun decodeRule(text: String): CooldownRule? {
        val fields = MiniJson.parse(text)?.objOrNull() ?: return null
        val channels =
            fields
                .stringList("channels")
                .mapNotNull { name -> AlertChannel.entries.firstOrNull { it.name == name } }
                .toSet()
        return CooldownRule(
            enabled = fields.boolean("enabled") ?: return null,
            windowMillis = fields.long("window") ?: return null,
            channels = channels,
        )
    }

    companion object {
        /** Prefix for every stored cooldown rule. */
        const val KEY_PREFIX: String = "wae.notifications.cooldown."
    }
}

/**
 * Absorbs the message bursts a chat generates, without turning into Do Not Disturb.
 *
 * The rule the prompt describes is: the first message of a burst alerts normally, messages
 * inside the window update the existing notification quietly, and once the window has passed
 * the next message alerts normally again. The window is anchored to the *first* alert of the
 * burst and is not extended by later messages — a busy group that kept pushing the window
 * forward would stay silent indefinitely, which is the bug this anchoring avoids.
 *
 * The engine holds the burst state and takes its clock as a parameter, so every boundary
 * condition is testable without waiting twenty seconds.
 */
class NotificationCooldownEngine(
    private val rules: NotificationCooldownStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Burst(
        val startedAt: Long,
    ) {
        var count: Int = 1
    }

    private val bursts = LinkedHashMap<String, Burst>()

    /** Decides for [chatKey] using the rule that applies to [chain]. */
    fun decide(
        chain: List<PolicyScope>,
        chatKey: String,
    ): CooldownDecision = decide(chain, chatKey, rules.effectiveRule(chain))

    /** Decides with an already-resolved rule, which is what a notification path will have. */
    fun decide(
        chain: List<PolicyScope>,
        chatKey: String,
        rule: CooldownRule,
    ): CooldownDecision {
        if (rule.isDisabled) {
            return CooldownDecision(
                alert = true,
                mutedChannels = emptySet(),
                burstCount = 1,
                cooldownRemainingMillis = 0L,
                explanation = "No cooldown applies to this chat.",
            )
        }
        val key = keyOf(chain, chatKey, rule)
        val now = clock()
        val existing = bursts[key]
        if (existing == null || now - existing.startedAt >= rule.windowMillis) {
            bursts[key] = Burst(now)
            return CooldownDecision(
                alert = true,
                mutedChannels = emptySet(),
                burstCount = 1,
                cooldownRemainingMillis = 0L,
                explanation = "First message of the burst; alerting normally.",
            )
        }
        existing.count += 1
        val remaining = rule.windowMillis - (now - existing.startedAt)
        return CooldownDecision(
            alert = false,
            mutedChannels = rule.channels,
            burstCount = existing.count,
            cooldownRemainingMillis = remaining.coerceAtLeast(0L),
            explanation =
                "Message ${existing.count} of the burst; silencing " +
                    "${rule.channels.size} channel(s) for ${CooldownWindows.label(remaining)}.",
        )
    }

    /** Whether [chatKey] is inside a cooldown right now, without consuming a burst slot. */
    fun isCoolingDown(
        chain: List<PolicyScope>,
        chatKey: String,
    ): Boolean {
        val rule = rules.effectiveRule(chain)
        if (rule.isDisabled) return false
        val existing = bursts[keyOf(chain, chatKey, rule)] ?: return false
        return clock() - existing.startedAt < rule.windowMillis
    }

    /** Forgets a chat's burst state, for when the chat is read or the notification cleared. */
    fun forget(chatKey: String): Boolean {
        val marker = "|$chatKey|"
        val stale = bursts.keys.filter { it.contains(marker) }
        stale.forEach { bursts.remove(it) }
        return stale.isNotEmpty()
    }

    /**
     * Drops burst state that has been idle for longer than any configured window.
     *
     * The engine keeps one entry per chat, so a device with thousands of chats would otherwise
     * accumulate entries that can never suppress anything again.
     */
    fun prune(idleMillis: Long): Int {
        val now = clock()
        val stale = bursts.filterValues { now - it.startedAt >= idleMillis }.keys.toList()
        stale.forEach { bursts.remove(it) }
        return stale.size
    }

    /** Drops all burst state. Used by tests and by a full reload. */
    fun clear() {
        bursts.clear()
    }

    /**
     * The burst key: the scope the message arrived in, the chat, and the rule's shape.
     *
     * The window and channel count are part of it so that changing the rule starts a fresh
     * burst instead of inheriting a count from a configuration that no longer exists.
     */
    private fun keyOf(
        chain: List<PolicyScope>,
        chatKey: String,
        rule: CooldownRule,
    ): String = "${chain.lastOrNull()?.code.orEmpty()}|$chatKey|${rule.windowMillis}|${rule.channels.size}"
}
