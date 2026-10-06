package com.wax.module.presence

import com.wax.module.outgoing.PolicyScope
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.boolean
import com.wax.module.platform.jsonBoolean
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.long
import com.wax.module.platform.objOrNull
import com.wax.module.platform.string

/**
 * Where each activity's alert rule lives.
 *
 * One key per (scope, activity) — `wae.presence.alert.<activity>.<scope>` — because a rule is
 * written and read as a unit, the same reason the outgoing policy store and the notification
 * cooldown store do it: a half-applied rule cannot exist on disk. The activity comes first in the
 * key on purpose, so the scopes configured for one activity are a prefix scan and a scope code
 * never has to be sliced out of a key.
 *
 * A stored value that cannot be decoded is treated as "this scope has no opinion", which inherits
 * rather than silencing — the safe direction, since a corrupt entry must never be the reason an
 * alert the user configured stops arriving. That rule extends to the one corruption a per-activity
 * key can have: a document whose `activity` field disagrees with the key it was found under is
 * refused, because applying it would put one activity's style on another.
 */
class PresenceAlertStore(
    private val store: KeyValueStore,
) {
    /** The rule stored for this scope and activity, or null when the scope has no opinion. */
    fun ruleFor(
        scope: PolicyScope,
        activity: PresenceActivity,
    ): PresenceAlertRule? {
        val text = store.getString(keyFor(scope, activity)) ?: return null
        return decodeRule(text, activity)
    }

    /** Stores [rule] for its activity at [scope]. */
    fun save(
        scope: PolicyScope,
        rule: PresenceAlertRule,
    ) {
        store.putString(keyFor(scope, rule.activity), MiniJson.write(encodeRule(rule)))
    }

    /** Removes a scope's rule for one activity, so it inherits again. */
    fun remove(
        scope: PolicyScope,
        activity: PresenceActivity,
    ): Boolean {
        val key = keyFor(scope, activity)
        if (store.getString(key) == null) return false
        store.remove(key)
        return true
    }

    /**
     * The rule that applies to [activity] along [chain], most specific first.
     *
     * Resolution is per activity, which is what makes per-contact and per-activity customisation
     * compose: a chat that names typing and says nothing about recording keeps whatever the
     * wider scopes said about recording, instead of the chat's typing rule standing in for an
     * opinion it never expressed.
     */
    fun effectiveRule(
        chain: List<PolicyScope>,
        activity: PresenceActivity,
    ): PresenceAlertRule? =
        chain
            .sortedByDescending { it.precedence }
            .firstNotNullOfOrNull { ruleFor(it, activity) }

    /** Every activity configured at [scope], in declaration order. */
    fun configured(scope: PolicyScope): List<PresenceAlertRule> = PresenceActivity.entries.mapNotNull { ruleFor(scope, it) }

    /** Every scope that configures [activity], in precedence order. */
    fun configuredScopes(activity: PresenceActivity): List<PolicyScope> =
        store
            .keys(prefixFor(activity))
            .mapNotNull { PolicyScope.parse(it.removePrefix(prefixFor(activity))) }
            .sortedBy { it.precedence }

    /** How many scopes configure each activity, for the settings summary. */
    fun configuredActivityCounts(): Map<PresenceActivity, Int> = PresenceActivity.entries.associateWith { store.keys(prefixFor(it)).size }

    /** Removes every rule. Used by tests and by a factory reset. */
    fun clear() {
        store.keys(KEY_PREFIX).forEach { store.remove(it) }
    }

    private fun encodeRule(rule: PresenceAlertRule) =
        jsonObject(
            "activity" to jsonString(rule.activity.id),
            "style" to jsonString(rule.style.name),
            "cooldown" to jsonNumber(rule.cooldownMillis),
            "whenOpen" to jsonBoolean(rule.alertWhenChatIsOpen),
            "mutedChats" to jsonBoolean(rule.suppressors.mutedChats),
            "blockedContacts" to jsonBoolean(rule.suppressors.blockedContacts),
            "quietHours" to jsonBoolean(rule.suppressors.quietHours),
        )

    private fun decodeRule(
        text: String,
        activity: PresenceActivity,
    ): PresenceAlertRule? {
        val fields = MiniJson.parse(text)?.objOrNull() ?: return null
        if (fields.string("activity") != activity.id) return null
        val style = PresenceAlertStyle.fromName(fields.string("style")) ?: return null
        val cooldownMillis = fields.long("cooldown") ?: return null
        return PresenceAlertRule(
            activity = activity,
            style = style,
            cooldownMillis = cooldownMillis,
            alertWhenChatIsOpen = fields.boolean("whenOpen") ?: return null,
            suppressors =
                PresenceSuppressors(
                    mutedChats = fields.boolean("mutedChats") ?: return null,
                    blockedContacts = fields.boolean("blockedContacts") ?: return null,
                    quietHours = fields.boolean("quietHours") ?: return null,
                ),
        )
    }

    companion object {
        /** Prefix for every stored presence alert rule. */
        const val KEY_PREFIX: String = "wae.presence.alert."

        private fun prefixFor(activity: PresenceActivity): String = KEY_PREFIX + activity.id + "."

        private fun keyFor(
            scope: PolicyScope,
            activity: PresenceActivity,
        ): String = prefixFor(activity) + scope.code
    }
}
