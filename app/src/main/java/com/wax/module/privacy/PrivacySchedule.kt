package com.wax.module.privacy

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.array
import com.wax.module.platform.boolean
import com.wax.module.platform.jsonArray
import com.wax.module.platform.jsonBoolean
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.jsonStrings
import com.wax.module.platform.long
import com.wax.module.platform.string
import com.wax.module.platform.stringList
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * One condition that can activate a scheduled profile.
 *
 * Triggers of one rule are combined with AND: a rule that says "night on weekdays while
 * charging" should not fire on a Tuesday afternoon. That is the conservative reading —
 * OR would make a single accidental trigger activate a privacy profile, and a privacy
 * switch firing when it was not asked to is worse than one that fires late.
 */
sealed interface PrivacyTrigger {
    /** A time-of-day window; an end earlier than the start means the window crosses midnight. */
    data class TimeWindow(
        val start: LocalTime,
        val end: LocalTime,
    ) : PrivacyTrigger

    /** Selected weekdays. */
    data class Weekdays(
        val days: Set<DayOfWeek>,
    ) : PrivacyTrigger

    /** Charging state. */
    data class Charging(
        val charging: Boolean,
    ) : PrivacyTrigger

    /** Wi-Fi connectivity state. */
    data class Wifi(
        val connected: Boolean,
    ) : PrivacyTrigger

    /** Bluetooth connectivity state. */
    data class Bluetooth(
        val connected: Boolean,
    ) : PrivacyTrigger

    /** A named event sent by Tasker. */
    data class TaskerEvent(
        val name: String,
    ) : PrivacyTrigger
}

/** The environment a rule is evaluated against. */
data class ScheduleContext(
    val time: LocalTime,
    val dayOfWeek: DayOfWeek,
    val charging: Boolean,
    val wifiConnected: Boolean,
    val bluetoothConnected: Boolean,
    val taskerEvents: Set<String> = emptySet(),
)

/** One schedule rule: when its triggers all match, switch to [profileId]. */
data class PrivacyScheduleRule(
    val id: String,
    val name: String,
    val profileId: String,
    val triggers: List<PrivacyTrigger>,
    val enabled: Boolean = true,
) {
    /** Whether this rule matches [context]; an empty trigger list never matches. */
    fun matches(context: ScheduleContext): Boolean {
        if (triggers.isEmpty()) return false
        return triggers.all { it.matches(context) }
    }

    /** One line for diagnostics. */
    fun toDisplayLine(): String = "$name -> $profileId (${triggers.size} trigger(s))${if (enabled) "" else ", disabled"}"
}

/** Whether one trigger matches the context. */
fun PrivacyTrigger.matches(context: ScheduleContext): Boolean =
    when (this) {
        is PrivacyTrigger.TimeWindow -> {
            if (start <= end) {
                context.time >= start && context.time < end
            } else {
                // Crossing midnight: 22:00-07:00 matches 23:00 and 06:00 but not 12:00.
                context.time >= start || context.time < end
            }
        }

        is PrivacyTrigger.Weekdays -> context.dayOfWeek in days
        is PrivacyTrigger.Charging -> context.charging == charging
        is PrivacyTrigger.Wifi -> context.wifiConnected == connected
        is PrivacyTrigger.Bluetooth -> context.bluetoothConnected == connected
        is PrivacyTrigger.TaskerEvent -> context.taskerEvents.contains(name)
    }

/** What the scheduler wants to happen next. */
sealed interface PrivacyScheduleDecision {
    /** Nothing to do; the active profile is already correct. */
    data class KeepCurrent(
        val reason: String,
    ) : PrivacyScheduleDecision

    /** Switch to [profileId] because of an automatic rule. */
    data class SwitchTo(
        val profileId: String,
        val ruleId: String,
        val reason: String,
    ) : PrivacyScheduleDecision

    /** Switch to [profileId] because the user set a manual override. */
    data class ManualOverride(
        val profileId: String,
    ) : PrivacyScheduleDecision
}

/** One recorded switch, kept locally and bounded. */
data class ScheduledSwitch(
    val timestampMillis: Long,
    val ruleId: String,
    val fromProfileId: String,
    val toProfileId: String,
    val automatic: Boolean,
    val reason: String,
) {
    /** One line for diagnostics. */
    fun toDisplayLine(): String = (if (automatic) "auto" else "manual") + " $fromProfileId -> $toProfileId at $timestampMillis"
}

/**
 * Time- and context-driven profile switching.
 *
 * Two behaviours are part of the contract, because leaving them undefined is how scheduled
 * privacy becomes surprising:
 *
 * 1. **Rules are ordered and first-match wins.** The first enabled rule whose triggers all
 *    match decides; later rules do not get a vote. Priority is therefore explicit and
 *    reorderable rather than emergent.
 * 2. **A manual override wins until it expires or is cleared.** Automatic rules never
 *    fight the user: while an override is active, evaluation returns it instead of a rule,
 *    and the override's own expiry is the only automatic way out.
 *
 * Every switch the engine proposes is appended to a bounded local history, satisfying
 * "automatic switches are recorded locally". The history stores ids only — never message
 * content, contact names or phone numbers.
 */
class PrivacyScheduleEngine(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val profileExists: (String) -> Boolean = { true },
) {
    /** All rules, in priority order. */
    fun rules(): List<PrivacyScheduleRule> {
        val text = store.getString(KEY_RULES) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decodeRule(it) }
    }

    /**
     * Adds a rule at the end of the priority order.
     *
     * @return [PrivacyOpResult.Rejected] when the profile does not exist or no triggers were
     *   given; a rule that can never fire is a configuration mistake, not a feature
     */
    fun addRule(
        name: String,
        profileId: String,
        triggers: List<PrivacyTrigger>,
    ): PrivacyOpResult<PrivacyScheduleRule> {
        if (!profileExists(profileId)) {
            return PrivacyOpResult.Rejected(
                listOf(PrivacyValidationProblem("profile_missing", "There is no profile with id \"$profileId\".")),
            )
        }
        if (triggers.isEmpty()) {
            return PrivacyOpResult.Rejected(
                listOf(
                    PrivacyValidationProblem(
                        "triggers_empty",
                        "A scheduled profile needs at least one trigger, otherwise it can never activate.",
                    ),
                ),
            )
        }
        if (name.isBlank()) {
            return PrivacyOpResult.Rejected(
                listOf(PrivacyValidationProblem("name_blank", "The rule name cannot be empty.")),
            )
        }
        val rule =
            PrivacyScheduleRule(
                id = nextRuleId(),
                name = name.trim(),
                profileId = profileId,
                triggers = triggers,
            )
        writeRules(rules() + rule)
        return PrivacyOpResult.Success(rule)
    }

    /** Removes a rule by id. */
    fun removeRule(id: String): Boolean {
        val current = rules()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        writeRules(remaining)
        return true
    }

    /** Enables or disables a rule without losing its position in the priority order. */
    fun setRuleEnabled(
        id: String,
        enabled: Boolean,
    ): Boolean {
        val current = rules()
        if (current.none { it.id == id }) return false
        writeRules(current.map { if (it.id == id) it.copy(enabled = enabled) else it })
        return true
    }

    /** Moves a rule to [index] in the priority order, clamping out-of-range input. */
    fun moveRule(
        id: String,
        index: Int,
    ): Boolean {
        val current = rules().toMutableList()
        val from = current.indexOfFirst { it.id == id }
        if (from < 0) return false
        val target = index.coerceIn(0, current.size - 1)
        val rule = current.removeAt(from)
        current.add(target, rule)
        writeRules(current)
        return true
    }

    /** Sets a manual override to [profileId] until [durationMillis] from now. */
    fun manualOverride(
        profileId: String,
        durationMillis: Long = DEFAULT_OVERRIDE_MILLIS,
    ): Boolean {
        if (!profileExists(profileId)) return false
        store.putString(KEY_MANUAL_PROFILE, profileId)
        store.putLong(KEY_MANUAL_UNTIL, now() + durationMillis)
        return true
    }

    /** Clears the manual override, letting rules apply again. */
    fun clearManualOverride() {
        store.remove(KEY_MANUAL_PROFILE)
        store.remove(KEY_MANUAL_UNTIL)
    }

    /** The active manual override profile, or null when there is none or it has expired. */
    fun manualOverrideProfileId(): String? {
        val profileId = store.getString(KEY_MANUAL_PROFILE) ?: return null
        val until = store.getLong(KEY_MANUAL_UNTIL)
        if (until > 0L && now() >= until) {
            clearManualOverride()
            return null
        }
        return profileId
    }

    /**
     * Decides what should happen for [context] given the currently active profile.
     *
     * Records the proposed switch in the local history before returning, so the history
     * reflects what the scheduler decided even if the caller applies it asynchronously.
     */
    fun evaluate(
        context: ScheduleContext,
        activeProfileId: String,
    ): PrivacyScheduleDecision {
        val manual = manualOverrideProfileId()
        if (manual != null) {
            return if (manual == activeProfileId) {
                PrivacyScheduleDecision.KeepCurrent("manual override already active")
            } else {
                recordSwitch(
                    ScheduledSwitch(
                        timestampMillis = now(),
                        ruleId = MANUAL_RULE_ID,
                        fromProfileId = activeProfileId,
                        toProfileId = manual,
                        automatic = false,
                        reason = "manual override",
                    ),
                )
                PrivacyScheduleDecision.ManualOverride(manual)
            }
        }

        val matched =
            rules().firstOrNull { it.enabled && it.matches(context) }
                ?: return PrivacyScheduleDecision.KeepCurrent("no rule matched")
        if (matched.profileId == activeProfileId) {
            return PrivacyScheduleDecision.KeepCurrent("rule \"${matched.name}\" already active")
        }
        recordSwitch(
            ScheduledSwitch(
                timestampMillis = now(),
                ruleId = matched.id,
                fromProfileId = activeProfileId,
                toProfileId = matched.profileId,
                automatic = true,
                reason = "rule \"${matched.name}\" matched",
            ),
        )
        return PrivacyScheduleDecision.SwitchTo(matched.profileId, matched.id, matched.reason())
    }

    /** The recorded switches, oldest first. */
    fun history(): List<ScheduledSwitch> {
        val text = store.getString(KEY_HISTORY) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decodeSwitch(it) }
    }

    /** Drops rules, override and history. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_RULES)
        store.remove(KEY_HISTORY)
        clearManualOverride()
    }

    private fun recordSwitch(record: ScheduledSwitch) {
        val all = history() + record
        val bounded = if (all.size > MAX_HISTORY) all.takeLast(MAX_HISTORY) else all
        store.putString(KEY_HISTORY, MiniJson.write(jsonArray(bounded.map { encodeSwitch(it) })))
    }

    private fun PrivacyScheduleRule.reason(): String = "rule \"$name\" matched ${triggers.size} trigger(s)"

    private fun nextRuleId(): String {
        var candidate = "schedule.${now()}"
        var counter = 1
        while (rules().any { it.id == candidate }) {
            candidate = "schedule.${now()}.$counter"
            counter++
        }
        return candidate
    }

    private fun writeRules(rules: List<PrivacyScheduleRule>) {
        store.putString(KEY_RULES, MiniJson.write(jsonArray(rules.map { encodeRule(it) })))
    }

    private fun encodeRule(rule: PrivacyScheduleRule): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(rule.id),
            "name" to jsonString(rule.name),
            "profileId" to jsonString(rule.profileId),
            "enabled" to jsonBoolean(rule.enabled),
            "triggers" to jsonArray(rule.triggers.map { encodeTrigger(it) }),
        )

    private fun encodeTrigger(trigger: PrivacyTrigger): JsonValue.Obj =
        when (trigger) {
            is PrivacyTrigger.TimeWindow ->
                jsonObject(
                    "type" to jsonString("time"),
                    "start" to jsonString(trigger.start.toString()),
                    "end" to jsonString(trigger.end.toString()),
                )

            is PrivacyTrigger.Weekdays ->
                jsonObject(
                    "type" to jsonString("weekdays"),
                    "days" to jsonStrings(trigger.days.map { it.name }),
                )

            is PrivacyTrigger.Charging ->
                jsonObject(
                    "type" to jsonString("charging"),
                    "value" to jsonBoolean(trigger.charging),
                )

            is PrivacyTrigger.Wifi ->
                jsonObject(
                    "type" to jsonString("wifi"),
                    "value" to jsonBoolean(trigger.connected),
                )

            is PrivacyTrigger.Bluetooth ->
                jsonObject(
                    "type" to jsonString("bluetooth"),
                    "value" to jsonBoolean(trigger.connected),
                )

            is PrivacyTrigger.TaskerEvent ->
                jsonObject(
                    "type" to jsonString("tasker"),
                    "name" to jsonString(trigger.name),
                )
        }

    private fun decodeRule(value: JsonValue): PrivacyScheduleRule? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        val id = fields.string("id") ?: return null
        val name = fields.string("name") ?: return null
        val profileId = fields.string("profileId") ?: return null
        val triggers = fields.array("triggers").orEmpty().mapNotNull { decodeTrigger(it) }
        if (triggers.isEmpty()) return null
        return PrivacyScheduleRule(
            id = id,
            name = name,
            profileId = profileId,
            triggers = triggers,
            enabled = fields.boolean("enabled") ?: true,
        )
    }

    private fun decodeTrigger(value: JsonValue): PrivacyTrigger? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return when (fields.string("type")) {
            "time" -> {
                val start =
                    fields.string("start")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                        ?: return null
                val end =
                    fields.string("end")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                        ?: return null
                PrivacyTrigger.TimeWindow(start, end)
            }

            "weekdays" -> {
                val days =
                    fields
                        .stringList("days")
                        .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
                        .toSet()
                if (days.isEmpty()) null else PrivacyTrigger.Weekdays(days)
            }

            "charging" -> PrivacyTrigger.Charging(fields.boolean("value") ?: return null)
            "wifi" -> PrivacyTrigger.Wifi(fields.boolean("value") ?: return null)
            "bluetooth" -> PrivacyTrigger.Bluetooth(fields.boolean("value") ?: return null)
            "tasker" -> fields.string("name")?.let { PrivacyTrigger.TaskerEvent(it) }
            else -> null
        }
    }

    private fun encodeSwitch(record: ScheduledSwitch): JsonValue.Obj =
        jsonObject(
            "at" to jsonNumber(record.timestampMillis),
            "rule" to jsonString(record.ruleId),
            "from" to jsonString(record.fromProfileId),
            "to" to jsonString(record.toProfileId),
            "automatic" to jsonBoolean(record.automatic),
            "reason" to jsonString(record.reason),
        )

    private fun decodeSwitch(value: JsonValue): ScheduledSwitch? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return ScheduledSwitch(
            timestampMillis = fields.long("at") ?: return null,
            ruleId = fields.string("rule") ?: return null,
            fromProfileId = fields.string("from") ?: return null,
            toProfileId = fields.string("to") ?: return null,
            automatic = fields.boolean("automatic") ?: true,
            reason = fields.string("reason") ?: "",
        )
    }

    companion object {
        /** Storage key for the rule array. */
        const val KEY_RULES: String = "wae.privacy.schedule.rules"

        /** Storage key for the switch history. */
        const val KEY_HISTORY: String = "wae.privacy.schedule.history"

        /** Storage key for the manual override profile. */
        const val KEY_MANUAL_PROFILE: String = "wae.privacy.schedule.manual.profile"

        /** Storage key for the manual override expiry. */
        const val KEY_MANUAL_UNTIL: String = "wae.privacy.schedule.manual.until"

        /** Default manual override duration: until the next morning. */
        const val DEFAULT_OVERRIDE_MILLIS: Long = 8L * 60L * 60L * 1000L

        /** How many switch records are kept. */
        const val MAX_HISTORY: Int = 100

        /** Pseudo rule id used by history entries for manual overrides. */
        const val MANUAL_RULE_ID: String = "manual"
    }
}
