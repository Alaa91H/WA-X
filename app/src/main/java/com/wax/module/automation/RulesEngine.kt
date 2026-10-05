package com.wax.module.automation

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
 * Evaluates conditions against events.
 *
 * Every failure mode is a `false`, never a throw: a malformed regex or an unknown enum in
 * stored data must disable one condition, not the rule engine. That also makes the
 * simulator honest — it reports *that* a condition failed, and callers can inspect which.
 */
object RuleEvaluator {
    /** Cache of compiled regexes, because a rule is evaluated on every matching message. */
    private val regexCache = HashMap<Pair<String, Set<RegexOption>>, Regex?>()

    /** Tests every condition of [rule] against [event] (AND semantics). */
    fun evaluate(
        rule: AutomationRule,
        event: RuleEvent,
    ): ConditionMatchResult {
        val matched = ArrayList<RuleCondition>()
        val failed = ArrayList<RuleCondition>()
        rule.conditions.forEach { condition ->
            if (evaluate(condition, event)) matched.add(condition) else failed.add(condition)
        }
        return ConditionMatchResult(
            matched = failed.isEmpty() && rule.conditions.isNotEmpty(),
            matchedConditions = matched,
            failedConditions = failed,
        )
    }

    /** Tests one condition. */
    fun evaluate(
        condition: RuleCondition,
        event: RuleEvent,
    ): Boolean =
        when (condition) {
            is RuleCondition.Sender -> !event.isGroup && event.chatId == condition.chatId
            is RuleCondition.GroupChat -> event.isFromGroup(condition.groupId)
            is RuleCondition.MessageTypeIs -> event.messageType == condition.type
            is RuleCondition.Keyword -> {
                if (condition.text.isEmpty()) {
                    false
                } else if (condition.caseSensitive) {
                    event.text.contains(condition.text)
                } else {
                    event.text.contains(condition.text, ignoreCase = true)
                }
            }

            is RuleCondition.RegexMatch -> compiled(condition)?.containsMatchIn(event.text) ?: false
            is RuleCondition.TimeWindow -> matchesWindow(event.time, condition.start, condition.end)
            is RuleCondition.Weekdays -> event.dayOfWeek in condition.days
            is RuleCondition.Wifi -> event.wifiConnected == condition.connected
            is RuleCondition.Charging -> event.charging == condition.charging
            is RuleCondition.BatteryBelow -> event.batteryPercent?.let { it < condition.percent } ?: false
            is RuleCondition.PackageProfile -> event.packageName == condition.packageName
        }

    /** Whether [pattern] compiles; used at rule-save time to reject broken regexes early. */
    fun isValidRegex(
        pattern: String,
        options: Set<RegexOption> = emptySet(),
    ): Boolean = compiled(RuleCondition.RegexMatch(pattern, options)) != null

    private fun compiled(condition: RuleCondition.RegexMatch): Regex? =
        synchronized(regexCache) {
            regexCache.getOrPut(condition.pattern to condition.options) {
                runCatching { Regex(condition.pattern, condition.options) }.getOrNull()
            }
        }

    private fun matchesWindow(
        time: LocalTime,
        start: LocalTime,
        end: LocalTime,
    ): Boolean = if (start <= end) time >= start && time < end else time >= start || time < end
}

/** One action chosen for execution, with the rule that produced it. */
data class PlannedAction(
    val ruleId: String,
    val ruleName: String,
    val action: RuleAction,
)

/** One rule that was not executed, with the reason. */
data class SkippedRule(
    val ruleId: String,
    val ruleName: String,
    val reason: String,
)

/** The kind of conflict the resolver detected. */
enum class RuleConflictKind {
    /** Two matching rules would both auto-reply; the first in priority order wins. */
    DUPLICATE_AUTO_REPLY,

    /** Two matching rules switch to different privacy profiles; the first wins. */
    CONFLICTING_PROFILE_SWITCH,

    /** The per-event action limit was reached; later actions were dropped. */
    ACTION_LIMIT,
}

/** A detected conflict, reported rather than silently resolved. */
data class RuleConflict(
    val kind: RuleConflictKind,
    val ruleIds: List<String>,
    val description: String,
)

/** The full decision for one event, before anything is executed. */
data class RuleExecutionPlan(
    val plannedActions: List<PlannedAction>,
    val executedRules: List<String>,
    val skippedRules: List<SkippedRule>,
    val conflicts: List<RuleConflict>,
    val blockedReason: String? = null,
) {
    /** Human-readable summary for diagnostics and the simulator. */
    fun describe(): String =
        buildString {
            if (blockedReason != null) {
                appendLine("Automation blocked: $blockedReason")
                return@buildString
            }
            appendLine("Rules executed: ${executedRules.size}")
            plannedActions.forEach { appendLine("* [${it.ruleName}] ${it.action.describe()}") }
            skippedRules.forEach { appendLine("skipped [${it.ruleName}]: ${it.reason}") }
            conflicts.forEach { appendLine("conflict: ${it.description}") }
        }
}

/**
 * Bounds how fast automation may run.
 *
 * The roadmap requires action rate limits and loop prevention, and this is where the rate
 * half lives. The per-event limit is enforced by the planner (so a single event cannot
 * queue unbounded work), and the per-chat window is enforced here at execution time (so a
 * burst of events cannot fan out into a burst of actions).
 *
 * The window is in-memory on purpose: it is loop protection, not a quota, and persisting it
 * across restarts would risk permanently silencing a chat after a bad day.
 */
class AutomationRateLimiter(
    private val maxActionsPerChatPerWindow: Int = 20,
    private val windowMillis: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val history = HashMap<String, ArrayDeque<Long>>()

    /** Reserves one action for [chatId]; false when the window is exhausted. */
    fun tryConsume(chatId: String): Boolean {
        val current = now()
        val timestamps = history.getOrPut(chatId) { ArrayDeque() }
        while (timestamps.isNotEmpty() && timestamps.first() <= current - windowMillis) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxActionsPerChatPerWindow) return false
        timestamps.addLast(current)
        return true
    }

    /** How many actions have run for [chatId] inside the current window. */
    fun actionsThisWindow(chatId: String): Int {
        val timestamps = history[chatId] ?: return 0
        val cutoff = now() - windowMillis
        return timestamps.count { it > cutoff }
    }

    /** Drops all recorded actions. Used by tests and by the emergency disable path. */
    fun reset() {
        history.clear()
    }
}

/** What happened to one action. */
enum class ActionOutcome {
    /** The executor completed. */
    EXECUTED,

    /** The executor threw. */
    FAILED,

    /** The per-chat rate limit refused the action. */
    RATE_LIMITED,
}

/**
 * One audit record.
 *
 * The T102 contract is explicit about what may be stored: rule id, event type, result and
 * timestamp. [detail] is populated only in developer mode, and even then it holds
 * descriptions — action names and exception classes — never message text, senders or chat
 * identifiers.
 */
data class AuditEntry(
    val timestampMillis: Long,
    val ruleId: String,
    val actionId: String,
    val outcome: ActionOutcome,
    val eventType: MessageType,
    val detail: String? = null,
) {
    /** One line for the audit list. */
    fun toDisplayLine(): String =
        "$timestampMillis [$ruleId/$actionId] ${outcome.name.lowercase()} on ${eventType.name.lowercase()}" +
            if (detail != null) " ($detail)" else ""
}

/** Executes one action; allowed to throw, which the engine records as FAILED. */
fun interface RuleActionExecutor {
    fun execute(
        action: RuleAction,
        event: RuleEvent,
    )
}

/** The outcome of saving a rule. */
sealed interface RuleWriteResult {
    /** The rule was stored. */
    data class Success(
        val rule: AutomationRule,
    ) : RuleWriteResult

    /** The rule was refused; [message] says why. */
    data class Rejected(
        val message: String,
    ) : RuleWriteResult
}

/** One rule shown in a simulation, with its decision detail. */
data class SimulatedRule(
    val rule: AutomationRule,
    val result: ConditionMatchResult,
    val plannedActions: List<RuleAction>,
    val skippedReason: String?,
)

/** The result of simulating an event without executing anything. */
data class SimulationReport(
    val eventSummary: String,
    val rules: List<SimulatedRule>,
    val plan: RuleExecutionPlan,
) {
    /** Human-readable rendering, used by the simulator screen. */
    fun describe(): String =
        buildString {
            appendLine(eventSummary)
            rules.forEach { simulated ->
                appendLine()
                appendLine("- ${simulated.rule.name}: ${simulated.result.toDisplayLine()}")
                simulated.result.matchedConditions.forEach { appendLine("    matched: ${it.describe()}") }
                simulated.result.failedConditions.forEach { appendLine("    failed: ${it.describe()}") }
                simulated.plannedActions.forEach { appendLine("    would run: ${it.describe()}") }
                simulated.skippedReason?.let { appendLine("    skipped: $it") }
            }
        }
}

/** What actually happened for one event. */
data class RuleExecutionReport(
    val entries: List<AuditEntry>,
    val skippedRules: List<SkippedRule>,
    val conflicts: List<RuleConflict>,
    val blockedReason: String?,
)

/**
 * The rules engine core.
 *
 * Execution order is defined once, here, and nowhere else:
 *
 * ```
 * disabled rules and non-matching rules are skipped
 * → matching rules run by priority (higher first), ties in declaration order
 * → a rule that stops processing ends the chain
 * → conflicting actions are dropped with a diagnostic, the first rule winning
 * → the per-event action cap bounds the plan
 * → execution is rate limited per chat
 * → everything executed is audited locally
 * ```
 *
 * Two safety mechanisms are unconditional: events flagged as produced by automation are
 * never evaluated (loop prevention), and a global emergency disable blocks everything
 * without touching stored rules.
 */
class RulesEngine(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val rateLimiter: AutomationRateLimiter = AutomationRateLimiter(),
    private val developerMode: () -> Boolean = { false },
    private val maxActionsPerEvent: Int = DEFAULT_MAX_ACTIONS_PER_EVENT,
) {
    /** Every stored rule, in declaration order. */
    fun rules(): List<AutomationRule> {
        val text = store.getString(KEY_RULES) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decodeRule(it) }
    }

    /** Creates a rule. */
    fun addRule(
        name: String,
        conditions: List<RuleCondition>,
        actions: List<RuleAction>,
        priority: Int = 0,
        stopProcessing: Boolean = false,
    ): RuleWriteResult {
        if (rules().size >= MAX_RULES) {
            return RuleWriteResult.Rejected("The rule limit ($MAX_RULES) is reached. Delete a rule first.")
        }
        val rule =
            AutomationRule(
                id = nextRuleId(),
                name = name.trim(),
                enabled = true,
                conditions = conditions,
                actions = actions,
                priority = priority,
                stopProcessing = stopProcessing,
                createdAtMillis = now(),
            )
        val problems = validate(rule, isNew = true)
        if (problems.isNotEmpty()) return RuleWriteResult.Rejected(problems.first())

        writeRules(rules() + rule)
        return RuleWriteResult.Success(rule)
    }

    /** Replaces an existing rule; the id must match a stored rule. */
    fun updateRule(rule: AutomationRule): RuleWriteResult {
        val current = rules()
        if (current.none { it.id == rule.id }) {
            return RuleWriteResult.Rejected("There is no rule with id \"${rule.id}\".")
        }
        val problems = validate(rule, isNew = false)
        if (problems.isNotEmpty()) return RuleWriteResult.Rejected(problems.first())

        writeRules(current.map { if (it.id == rule.id) rule else it })
        return RuleWriteResult.Success(rule)
    }

    /** Deletes a rule. */
    fun removeRule(id: String): Boolean {
        val current = rules()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        writeRules(remaining)
        return true
    }

    /** Enables or disables a rule without changing its priority. */
    fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Boolean {
        val current = rules()
        if (current.none { it.id == id }) return false
        writeRules(current.map { if (it.id == id) it.copy(enabled = enabled) else it })
        return true
    }

    /** Changes a rule's priority. */
    fun setPriority(
        id: String,
        priority: Int,
    ): Boolean {
        val current = rules()
        if (current.none { it.id == id }) return false
        writeRules(current.map { if (it.id == id) it.copy(priority = priority) else it })
        return true
    }

    /** Whether the emergency disable is on. */
    fun isEmergencyDisabled(): Boolean = store.getBoolean(KEY_EMERGENCY_DISABLED, false)

    /** Turns the emergency disable on or off. Rules are untouched while it is on. */
    fun setEmergencyDisabled(disabled: Boolean) {
        store.putBoolean(KEY_EMERGENCY_DISABLED, disabled)
    }

    /** Computes the plan for [event] without executing anything. */
    fun plan(event: RuleEvent): RuleExecutionPlan = buildPlan(event)

    /**
     * Simulates [event] against every rule, including disabled ones.
     *
     * The simulator deliberately evaluates disabled rules so the user can test before
     * enabling, and it shows *which* conditions failed — testing that only says "no" would
     * make the feature useless for debugging a rule.
     */
    fun simulate(event: RuleEvent): SimulationReport {
        val plan = buildPlan(event)
        val plannedByRule = plan.plannedActions.groupBy { it.ruleId }
        val skippedByRule = plan.skippedRules.associateBy { it.ruleId }
        val simulated =
            rules().map { rule ->
                val result = RuleEvaluator.evaluate(rule, event)
                val reason =
                    when {
                        !rule.enabled -> "rule is disabled"
                        !result.matched -> "conditions not met"
                        plan.blockedReason != null -> plan.blockedReason
                        else -> skippedByRule[rule.id]?.reason
                    }
                SimulatedRule(
                    rule = rule,
                    result = result,
                    plannedActions = plannedByRule[rule.id].orEmpty().map { it.action },
                    skippedReason = reason,
                )
            }
        val summary =
            "${event.messageType.name.lowercase()} message, " +
                if (event.isGroup) "group chat" else "direct chat"
        return SimulationReport(summary, simulated, plan)
    }

    /**
     * Executes the plan for [event].
     *
     * Every action runs inside its own try/catch and its own rate-limit reservation, so one
     * failing or throttled action cannot stop the ones after it.
     */
    fun execute(
        event: RuleEvent,
        executor: RuleActionExecutor,
    ): RuleExecutionReport {
        val plan = buildPlan(event)
        val entries = ArrayList<AuditEntry>()
        val at = now()
        if (plan.blockedReason == null) {
            for (planned in plan.plannedActions) {
                if (!rateLimiter.tryConsume(event.chatId)) {
                    entries.add(
                        AuditEntry(at, planned.ruleId, planned.action.actionId, ActionOutcome.RATE_LIMITED, event.messageType, null),
                    )
                    continue
                }
                var outcome = ActionOutcome.EXECUTED
                var detail = developerDetail(planned.action)
                try {
                    executor.execute(planned.action, event)
                } catch (error: Throwable) {
                    outcome = ActionOutcome.FAILED
                    detail = "failed: ${error.javaClass.simpleName}" + (developerDetail(planned.action)?.let { " ($it)" } ?: "")
                }
                entries.add(
                    AuditEntry(at, planned.ruleId, planned.action.actionId, outcome, event.messageType, detail),
                )
            }
        }
        appendAudit(entries)
        return RuleExecutionReport(entries, plan.skippedRules, plan.conflicts, plan.blockedReason)
    }

    /** Audit records, oldest first. */
    fun audit(): List<AuditEntry> {
        val text = store.getString(KEY_AUDIT) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decodeAudit(it) }
    }

    /** Drops rules, audit and the emergency flag. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_RULES)
        store.remove(KEY_AUDIT)
        store.remove(KEY_EMERGENCY_DISABLED)
        rateLimiter.reset()
    }

    private fun buildPlan(event: RuleEvent): RuleExecutionPlan {
        if (event.fromAutomation) {
            return RuleExecutionPlan(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                blockedReason = "event was produced by a rule (loop prevention)",
            )
        }
        if (isEmergencyDisabled()) {
            return RuleExecutionPlan(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                blockedReason = "emergency disable is on",
            )
        }

        val candidates =
            rules()
                .withIndex()
                .filter { (_, rule) -> rule.enabled && RuleEvaluator.evaluate(rule, event).matched }
                .sortedWith(compareByDescending<IndexedValue<AutomationRule>> { it.value.priority }.thenBy { it.index })

        val planned = ArrayList<PlannedAction>()
        val executedRules = ArrayList<String>()
        val skipped = ArrayList<SkippedRule>()
        val conflicts = ArrayList<RuleConflict>()
        var autoReplyRuleId: String? = null
        var profileSwitch: Pair<String, String>? = null
        var stopped = false

        for ((_, rule) in candidates) {
            if (stopped) {
                skipped.add(SkippedRule(rule.id, rule.name, "an earlier rule stops processing"))
                continue
            }
            var plannedForRule = false
            for (action in rule.actions) {
                if (planned.size >= maxActionsPerEvent) {
                    conflicts.add(
                        RuleConflict(
                            RuleConflictKind.ACTION_LIMIT,
                            listOf(rule.id),
                            "the per-event action limit ($maxActionsPerEvent) was reached; later actions were dropped",
                        ),
                    )
                    stopped = true
                    break
                }
                when (action) {
                    is RuleAction.AutoReply -> {
                        if (autoReplyRuleId != null) {
                            conflicts.add(
                                RuleConflict(
                                    RuleConflictKind.DUPLICATE_AUTO_REPLY,
                                    listOf(autoReplyRuleId, rule.id),
                                    "two matching rules would auto-reply; the higher-priority rule wins",
                                ),
                            )
                        } else {
                            planned.add(PlannedAction(rule.id, rule.name, action))
                            autoReplyRuleId = rule.id
                            plannedForRule = true
                        }
                    }

                    is RuleAction.SwitchPrivacyProfile -> {
                        val existing = profileSwitch
                        when {
                            existing == null -> {
                                planned.add(PlannedAction(rule.id, rule.name, action))
                                profileSwitch = rule.id to action.profileId
                                plannedForRule = true
                            }

                            existing.second == action.profileId -> Unit // Already switching there; dedupe.
                            else ->
                                conflicts.add(
                                    RuleConflict(
                                        RuleConflictKind.CONFLICTING_PROFILE_SWITCH,
                                        listOf(existing.first, rule.id),
                                        "two matching rules switch to different profiles; the higher-priority rule wins",
                                    ),
                                )
                        }
                    }

                    else -> {
                        planned.add(PlannedAction(rule.id, rule.name, action))
                        plannedForRule = true
                    }
                }
            }
            if (plannedForRule) {
                executedRules.add(rule.id)
            } else {
                skipped.add(SkippedRule(rule.id, rule.name, "all actions were suppressed by conflict resolution"))
            }
            if (rule.stopProcessing) stopped = true
        }

        return RuleExecutionPlan(
            plannedActions = planned,
            executedRules = executedRules,
            skippedRules = skipped,
            conflicts = conflicts,
        )
    }

    private fun validate(
        rule: AutomationRule,
        isNew: Boolean,
    ): List<String> {
        val problems = ArrayList<String>()
        if (rule.name.isBlank()) problems.add("A rule needs a name.")
        if (rule.conditions.isEmpty()) {
            problems.add("A rule needs at least one condition, otherwise it would run on every message.")
        }
        if (rule.actions.isEmpty()) {
            problems.add("A rule needs at least one action.")
        }
        rule.conditions
            .filterIsInstance<RuleCondition.RegexMatch>()
            .filterNot { RuleEvaluator.isValidRegex(it.pattern, it.options) }
            .forEach { problems.add("The regular expression is invalid: ${it.pattern}") }
        if (isNew && rule.id.isBlank()) problems.add("A rule needs an id.")
        return problems
    }

    /** Detail stored with an audit entry: descriptions only, and only in developer mode. */
    private fun developerDetail(action: RuleAction): String? = if (developerMode()) action.describe() else null

    private fun appendAudit(entries: List<AuditEntry>) {
        if (entries.isEmpty()) return
        val all = audit() + entries
        val bounded = if (all.size > MAX_AUDIT) all.takeLast(MAX_AUDIT) else all
        store.putString(KEY_AUDIT, MiniJson.write(jsonArray(bounded.map { encodeAudit(it) })))
    }

    private fun writeRules(rules: List<AutomationRule>) {
        store.putString(KEY_RULES, MiniJson.write(jsonArray(rules.map { encodeRule(it) })))
    }

    private fun nextRuleId(): String {
        var candidate = "rule.${now()}"
        var counter = 1
        while (rules().any { it.id == candidate }) {
            candidate = "rule.${now()}.$counter"
            counter++
        }
        return candidate
    }

    // --- rule codec -------------------------------------------------------------------

    private fun encodeRule(rule: AutomationRule): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(rule.id),
            "name" to jsonString(rule.name),
            "enabled" to jsonBoolean(rule.enabled),
            "priority" to jsonNumber(rule.priority.toLong()),
            "stopProcessing" to jsonBoolean(rule.stopProcessing),
            "createdAt" to jsonNumber(rule.createdAtMillis),
            "conditions" to jsonArray(rule.conditions.mapNotNull { encodeCondition(it) }),
            "actions" to jsonArray(rule.actions.mapNotNull { encodeAction(it) }),
        )

    private fun encodeCondition(condition: RuleCondition): JsonValue.Obj =
        when (condition) {
            is RuleCondition.Sender -> jsonObject("type" to jsonString("sender"), "chatId" to jsonString(condition.chatId))
            is RuleCondition.GroupChat -> jsonObject("type" to jsonString("group"), "groupId" to jsonString(condition.groupId))
            is RuleCondition.MessageTypeIs ->
                jsonObject("type" to jsonString("message_type"), "value" to jsonString(condition.type.name))

            is RuleCondition.Keyword ->
                jsonObject(
                    "type" to jsonString("keyword"),
                    "text" to jsonString(condition.text),
                    "caseSensitive" to jsonBoolean(condition.caseSensitive),
                )

            is RuleCondition.RegexMatch ->
                jsonObject(
                    "type" to jsonString("regex"),
                    "pattern" to jsonString(condition.pattern),
                    "options" to jsonStrings(condition.options.map { it.name }),
                )

            is RuleCondition.TimeWindow ->
                jsonObject(
                    "type" to jsonString("time"),
                    "start" to jsonString(condition.start.toString()),
                    "end" to jsonString(condition.end.toString()),
                )

            is RuleCondition.Weekdays ->
                jsonObject(
                    "type" to jsonString("weekdays"),
                    "days" to jsonStrings(condition.days.map { it.name }),
                )

            is RuleCondition.Wifi -> jsonObject("type" to jsonString("wifi"), "value" to jsonBoolean(condition.connected))
            is RuleCondition.Charging ->
                jsonObject("type" to jsonString("charging"), "value" to jsonBoolean(condition.charging))

            is RuleCondition.BatteryBelow ->
                jsonObject("type" to jsonString("battery"), "percent" to jsonNumber(condition.percent.toLong()))

            is RuleCondition.PackageProfile ->
                jsonObject("type" to jsonString("package"), "package" to jsonString(condition.packageName))
        }

    private fun encodeAction(action: RuleAction): JsonValue.Obj =
        when (action) {
            is RuleAction.AutoReply -> jsonObject("type" to jsonString("auto_reply"), "text" to jsonString(action.text))
            RuleAction.MuteChat -> jsonObject("type" to jsonString("mute"))
            RuleAction.MarkLater -> jsonObject("type" to jsonString("mark_later"))
            is RuleAction.Bookmark ->
                jsonObject("type" to jsonString("bookmark"), "collection" to jsonString(action.collectionId))

            RuleAction.SaveMedia -> jsonObject("type" to jsonString("save_media"))
            is RuleAction.Notify ->
                jsonObject(
                    "type" to jsonString("notify"),
                    "title" to jsonString(action.title),
                    "text" to jsonString(action.text),
                )

            is RuleAction.SwitchPrivacyProfile ->
                jsonObject("type" to jsonString("switch_profile"), "profile" to jsonString(action.profileId))

            is RuleAction.TaskerEvent ->
                jsonObject(
                    "type" to jsonString("tasker"),
                    "name" to jsonString(action.name),
                    "payload" to action.payload?.let { jsonString(it) },
                )
        }

    private fun decodeRule(value: JsonValue): AutomationRule? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        val id = fields.string("id") ?: return null
        val name = fields.string("name") ?: return null
        val conditions = fields.array("conditions").orEmpty().mapNotNull { decodeCondition(it) }
        val actions = fields.array("actions").orEmpty().mapNotNull { decodeAction(it) }
        if (conditions.isEmpty() || actions.isEmpty()) return null
        return AutomationRule(
            id = id,
            name = name,
            enabled = fields.boolean("enabled") ?: true,
            conditions = conditions,
            actions = actions,
            priority = (fields.long("priority") ?: 0L).toInt(),
            stopProcessing = fields.boolean("stopProcessing") ?: false,
            createdAtMillis = fields.long("createdAt") ?: 0L,
        )
    }

    private fun decodeCondition(value: JsonValue): RuleCondition? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return when (fields.string("type")) {
            "sender" -> fields.string("chatId")?.let { RuleCondition.Sender(it) }
            "group" -> fields.string("groupId")?.let { RuleCondition.GroupChat(it) }
            "message_type" ->
                MessageType.entries
                    .firstOrNull { it.name == fields.string("value") }
                    ?.let { RuleCondition.MessageTypeIs(it) }

            "keyword" ->
                fields.string("text")?.let {
                    RuleCondition.Keyword(it, fields.boolean("caseSensitive") ?: false)
                }

            "regex" ->
                fields.string("pattern")?.let { pattern ->
                    val options =
                        fields
                            .stringList("options")
                            .mapNotNull { name -> RegexOption.entries.firstOrNull { it.name == name } }
                            .toSet()
                    RuleCondition.RegexMatch(pattern, options)
                }

            "time" -> {
                val start = fields.string("start")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                val end = fields.string("end")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                if (start == null || end == null) null else RuleCondition.TimeWindow(start, end)
            }

            "weekdays" -> {
                val days =
                    fields
                        .stringList("days")
                        .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
                        .toSet()
                if (days.isEmpty()) null else RuleCondition.Weekdays(days)
            }

            "wifi" -> RuleCondition.Wifi(fields.boolean("value") ?: return null)
            "charging" -> RuleCondition.Charging(fields.boolean("value") ?: return null)
            "battery" -> RuleCondition.BatteryBelow((fields.long("percent") ?: return null).toInt())
            "package" -> fields.string("package")?.let { RuleCondition.PackageProfile(it) }
            else -> null
        }
    }

    private fun decodeAction(value: JsonValue): RuleAction? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return when (fields.string("type")) {
            "auto_reply" -> fields.string("text")?.let { RuleAction.AutoReply(it) }
            "mute" -> RuleAction.MuteChat
            "mark_later" -> RuleAction.MarkLater
            "bookmark" -> fields.string("collection")?.let { RuleAction.Bookmark(it) }
            "save_media" -> RuleAction.SaveMedia
            "notify" -> RuleAction.Notify(fields.string("title") ?: "", fields.string("text") ?: "")
            "switch_profile" -> fields.string("profile")?.let { RuleAction.SwitchPrivacyProfile(it) }
            "tasker" -> fields.string("name")?.let { RuleAction.TaskerEvent(it, fields.string("payload")) }
            else -> null
        }
    }

    private fun encodeAudit(entry: AuditEntry): JsonValue.Obj =
        jsonObject(
            "at" to jsonNumber(entry.timestampMillis),
            "rule" to jsonString(entry.ruleId),
            "action" to jsonString(entry.actionId),
            "outcome" to jsonString(entry.outcome.name),
            "event" to jsonString(entry.eventType.name),
            "detail" to entry.detail?.let { jsonString(it) },
        )

    private fun decodeAudit(value: JsonValue): AuditEntry? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return AuditEntry(
            timestampMillis = fields.long("at") ?: return null,
            ruleId = fields.string("rule") ?: return null,
            actionId = fields.string("action") ?: return null,
            outcome =
                ActionOutcome.entries.firstOrNull { it.name == fields.string("outcome") }
                    ?: return null,
            eventType =
                MessageType.entries.firstOrNull { it.name == fields.string("event") }
                    ?: MessageType.OTHER,
            detail = fields.string("detail"),
        )
    }

    companion object {
        /** Storage key for the rule array. */
        const val KEY_RULES: String = "wae.automation.rules"

        /** Storage key for the audit log. */
        const val KEY_AUDIT: String = "wae.automation.audit"

        /** Storage key for the emergency disable flag. */
        const val KEY_EMERGENCY_DISABLED: String = "wae.automation.emergency_disabled"

        /** How many audit records are kept. */
        const val MAX_AUDIT: Int = 500

        /** The largest number of rules allowed. */
        const val MAX_RULES: Int = 100

        /** Default cap on actions one event may produce. */
        const val DEFAULT_MAX_ACTIONS_PER_EVENT: Int = 10
    }
}
