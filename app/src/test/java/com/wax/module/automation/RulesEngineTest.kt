package com.wax.module.automation

import com.wax.module.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class RulesEngineTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 1_700_000_000_000L
    }

    private fun engine(
        rateLimiter: AutomationRateLimiter = AutomationRateLimiter(),
        developerMode: Boolean = false,
        maxActionsPerEvent: Int = RulesEngine.DEFAULT_MAX_ACTIONS_PER_EVENT,
    ) = RulesEngine(store, { now }, rateLimiter, { developerMode }, maxActionsPerEvent)

    private fun event(
        chatId: String = "chat-1",
        isGroup: Boolean = false,
        text: String = "hello world",
        type: MessageType = MessageType.TEXT,
        time: String = "23:00",
        day: DayOfWeek = DayOfWeek.MONDAY,
        charging: Boolean = false,
        wifi: Boolean = false,
        battery: Int? = null,
        packageName: String? = null,
        fromAutomation: Boolean = false,
    ) = RuleEvent(
        chatId = chatId,
        senderId = chatId,
        isGroup = isGroup,
        messageType = type,
        text = text,
        time = LocalTime.parse(time),
        dayOfWeek = day,
        wifiConnected = wifi,
        charging = charging,
        batteryPercent = battery,
        packageName = packageName,
        fromAutomation = fromAutomation,
    )

    // --- conditions (T98) -------------------------------------------------------------

    @Test
    fun keywordMatchingIsCaseInsensitiveByDefault() {
        assertTrue(RuleEvaluator.evaluate(RuleCondition.Keyword("hello"), event(text = "Hello World")))
        assertFalse(RuleEvaluator.evaluate(RuleCondition.Keyword("hello", caseSensitive = true), event(text = "Hello")))
    }

    @Test
    fun aRegexConditionUsesThePatternAndFailsSafelyWhenBroken() {
        assertTrue(RuleEvaluator.evaluate(RuleCondition.RegexMatch("\\d{4}"), event(text = "code 1234")))
        assertFalse(RuleEvaluator.evaluate(RuleCondition.RegexMatch("["), event(text = "anything")))
        assertFalse(RuleEvaluator.isValidRegex("["))
    }

    @Test
    fun aSenderConditionOnlyMatchesDirectChats() {
        val condition = RuleCondition.Sender("john")
        assertTrue(RuleEvaluator.evaluate(condition, event(chatId = "john")))
        assertFalse(RuleEvaluator.evaluate(condition, event(chatId = "john", isGroup = true)))
    }

    @Test
    fun batteryAndConnectivityConditionsUseTheContext() {
        assertTrue(RuleEvaluator.evaluate(RuleCondition.Charging(true), event(charging = true)))
        assertTrue(RuleEvaluator.evaluate(RuleCondition.Wifi(true), event(wifi = true)))
        assertTrue(RuleEvaluator.evaluate(RuleCondition.BatteryBelow(20), event(battery = 15)))
        assertFalse(RuleEvaluator.evaluate(RuleCondition.BatteryBelow(20), event(battery = 80)))
        assertFalse("unknown battery must not match", RuleEvaluator.evaluate(RuleCondition.BatteryBelow(20), event()))
    }

    @Test
    fun packageAndMessageTypeConditionsMatchExactly() {
        assertTrue(
            RuleEvaluator.evaluate(
                RuleCondition.PackageProfile("com.whatsapp.w4b"),
                event(packageName = "com.whatsapp.w4b"),
            ),
        )
        assertTrue(RuleEvaluator.evaluate(RuleCondition.MessageTypeIs(MessageType.VOICE), event(type = MessageType.VOICE)))
    }

    // --- rule management --------------------------------------------------------------

    @Test
    fun aRuleNeedsConditionsAndActions() {
        val engine = engine()
        assertTrue(engine.addRule("No conditions", emptyList(), listOf(RuleAction.MuteChat)) is RuleWriteResult.Rejected)
        assertTrue(engine.addRule("No actions", listOf(RuleCondition.Wifi(true)), emptyList()) is RuleWriteResult.Rejected)
    }

    @Test
    fun anInvalidRegexIsRejectedAtSaveTime() {
        val engine = engine()
        val result =
            engine.addRule(
                "Broken",
                listOf(RuleCondition.RegexMatch("[")),
                listOf(RuleAction.MuteChat),
            )
        assertTrue(result is RuleWriteResult.Rejected)
    }

    @Test
    fun rulesSurviveRecreationAndCanBeToggled() {
        val first = engine()
        val rule =
            (
                first.addRule("Night mute", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MuteChat))
                    as RuleWriteResult.Success
            ).rule
        first.setEnabled(rule.id, false)
        first.setPriority(rule.id, 7)

        val second = engine()
        val reloaded = second.rules().single()
        assertFalse(reloaded.enabled)
        assertEquals(7, reloaded.priority)
        assertTrue(second.removeRule(rule.id))
    }

    // --- priority and conflicts (T100) ------------------------------------------------

    @Test
    fun higherPriorityRulesExecuteFirst() {
        val engine = engine()
        engine.addRule("Low", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MarkLater), priority = 1)
        engine.addRule("High", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MuteChat), priority = 5)
        val plan = engine.plan(event(wifi = true))
        assertEquals(listOf("High", "Low"), plan.plannedActions.map { it.ruleName })
    }

    @Test
    fun stopProcessingEndsTheChain() {
        val engine = engine()
        engine.addRule(
            "Stop here",
            listOf(RuleCondition.Wifi(true)),
            listOf(RuleAction.MuteChat),
            priority = 10,
            stopProcessing = true,
        )
        engine.addRule("Never runs", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MarkLater))
        val plan = engine.plan(event(wifi = true))
        assertEquals(listOf("Stop here"), plan.plannedActions.map { it.ruleName })
        assertTrue(plan.skippedRules.any { it.ruleName == "Never runs" })
    }

    @Test
    fun twoAutoRepliesConflictAndTheHigherPriorityRuleWins() {
        val engine = engine()
        engine.addRule("First", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.AutoReply("a")), priority = 5)
        engine.addRule("Second", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.AutoReply("b")), priority = 1)
        val plan = engine.plan(event(wifi = true))
        assertEquals(1, plan.plannedActions.size)
        assertEquals("First", plan.plannedActions.single().ruleName)
        assertEquals(RuleConflictKind.DUPLICATE_AUTO_REPLY, plan.conflicts.single().kind)
    }

    @Test
    fun conflictingProfileSwitchesAreReportedAndFirstWins() {
        val engine = engine()
        engine.addRule("Work", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.SwitchPrivacyProfile("work")), priority = 5)
        engine.addRule("Ghost", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.SwitchPrivacyProfile("ghost")), priority = 1)
        val plan = engine.plan(event(wifi = true))
        assertEquals(1, plan.plannedActions.size)
        assertEquals(RuleConflictKind.CONFLICTING_PROFILE_SWITCH, plan.conflicts.single().kind)
    }

    @Test
    fun duplicateIdenticalProfileSwitchesAreDeduplicatedWithoutConflict() {
        val engine = engine()
        engine.addRule("A", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.SwitchPrivacyProfile("work")))
        engine.addRule("B", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.SwitchPrivacyProfile("work")))
        val plan = engine.plan(event(wifi = true))
        assertEquals(1, plan.plannedActions.size)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun thePerEventActionLimitBoundsThePlan() {
        val engine = engine(maxActionsPerEvent = 1)
        engine.addRule("One", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MuteChat, RuleAction.MarkLater))
        val plan = engine.plan(event(wifi = true))
        assertEquals(1, plan.plannedActions.size)
        assertEquals(RuleConflictKind.ACTION_LIMIT, plan.conflicts.single().kind)
    }

    // --- simulator (T101) -------------------------------------------------------------

    @Test
    fun theSimulatorShowsMatchedAndFailedConditionsWithoutExecuting() {
        val engine = engine()
        engine.addRule(
            "Charging only",
            listOf(RuleCondition.Charging(true), RuleCondition.Keyword("hello")),
            listOf(RuleAction.MuteChat),
        )
        val report = engine.simulate(event(charging = false, text = "hello"))
        val simulated = report.rules.single()
        assertFalse(simulated.result.matched)
        assertEquals(1, simulated.result.matchedConditions.size)
        assertEquals(1, simulated.result.failedConditions.size)
        assertEquals("conditions not met", simulated.skippedReason)
        assertTrue(report.describe().contains("failed:"))
    }

    @Test
    fun theSimulatorIncludesDisabledRulesAndSaysWhy() {
        val engine = engine()
        val rule =
            (
                engine.addRule("Off", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MuteChat))
                    as RuleWriteResult.Success
            ).rule
        engine.setEnabled(rule.id, false)
        val report = engine.simulate(event(wifi = true))
        assertEquals("rule is disabled", report.rules.single().skippedReason)
    }

    // --- execution and safety (T102, T105) --------------------------------------------

    @Test
    fun anEventProducedByAutomationIsNeverEvaluated() {
        val engine = engine()
        engine.addRule("Reply", listOf(RuleCondition.Keyword("hello")), listOf(RuleAction.AutoReply("hi")))
        val plan = engine.plan(event(fromAutomation = true))
        assertTrue(plan.plannedActions.isEmpty())
        assertTrue(plan.blockedReason!!.contains("loop prevention"))
    }

    @Test
    fun theEmergencyDisableBlocksEverything() {
        val engine = engine()
        engine.addRule("Anything", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MuteChat))
        engine.setEmergencyDisabled(true)
        assertTrue(engine.plan(event(wifi = true)).blockedReason!!.contains("emergency"))
        engine.setEmergencyDisabled(false)
        assertEquals(1, engine.plan(event(wifi = true)).plannedActions.size)
    }

    @Test
    fun executionRunsActionsInOrderAndAuditsThem() {
        val engine = engine()
        engine.addRule("Mute", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MuteChat, RuleAction.MarkLater))
        val executed = ArrayList<String>()
        val report = engine.execute(event(wifi = true)) { action, _ -> executed.add(action.actionId) }
        assertEquals(listOf("mute_chat", "mark_later"), executed)
        assertEquals(2, report.entries.size)
        assertEquals(2, engine.audit().size)
        assertTrue(report.blockedReason == null)
    }

    @Test
    fun aThrowingActionIsIsolatedAndAuditedAsFailed() {
        val engine = engine()
        engine.addRule("Two actions", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MuteChat, RuleAction.MarkLater))
        val report =
            engine.execute(event(wifi = true)) { action, _ ->
                if (action == RuleAction.MuteChat) throw SecurityException("no permission")
            }
        assertEquals(ActionOutcome.FAILED, report.entries.first().outcome)
        assertEquals(ActionOutcome.EXECUTED, report.entries.last().outcome)
    }

    @Test
    fun auditDetailsStayEmptyUnlessDeveloperModeIsOn() {
        val defaultEngine = engine()
        defaultEngine.addRule("Reply", listOf(RuleCondition.Keyword("hello")), listOf(RuleAction.AutoReply("secret reply")))
        defaultEngine.execute(event()) { _, _ -> }
        assertNull(defaultEngine.audit().single().detail)

        // Both engines share the test's store, so clear the first engine's rule and audit
        // record before the second engine runs; otherwise its audit holds both entries.
        defaultEngine.clear()
        val developerEngine = engine(developerMode = true)
        developerEngine.addRule("Reply", listOf(RuleCondition.Keyword("hello")), listOf(RuleAction.AutoReply("secret reply")))
        developerEngine.execute(event()) { _, _ -> }
        val detail = developerEngine.audit().single().detail
        assertTrue(detail != null && detail.contains("auto-reply"))
        assertFalse("message text must never reach the audit log", detail!!.contains("secret reply"))
    }

    @Test
    fun thePerChatRateLimitStopsABurstOfActions() {
        val limiter = AutomationRateLimiter(maxActionsPerChatPerWindow = 2, windowMillis = 60_000L) { now }
        val engine = engine(rateLimiter = limiter)
        engine.addRule("Mute", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MuteChat))
        engine.execute(event(wifi = true)) { _, _ -> }
        engine.execute(event(wifi = true)) { _, _ -> }
        val third = engine.execute(event(wifi = true)) { _, _ -> }
        assertEquals(ActionOutcome.RATE_LIMITED, third.entries.single().outcome)
        now += 60_001
        assertEquals(
            ActionOutcome.EXECUTED,
            engine
                .execute(event(wifi = true)) { _, _ -> }
                .entries
                .single()
                .outcome,
        )
    }

    @Test
    fun clearingRemovesRulesAuditAndTheEmergencyFlag() {
        val engine = engine()
        engine.addRule("A", listOf(RuleCondition.Wifi(true)), listOf(RuleAction.MuteChat))
        engine.setEmergencyDisabled(true)
        engine.clear()
        assertTrue(engine.rules().isEmpty())
        assertTrue(engine.audit().isEmpty())
        assertFalse(engine.isEmergencyDisabled())
    }
}
