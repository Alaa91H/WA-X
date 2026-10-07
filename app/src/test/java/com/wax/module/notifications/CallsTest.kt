package com.wax.module.notifications

import com.wax.module.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class CallsTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 1_700_000_000_000L
    }

    private fun event(
        callerId: String? = "unknown",
        known: Boolean = false,
        time: String = "12:00",
        day: DayOfWeek = DayOfWeek.MONDAY,
        previousAttempt: Long? = null,
    ) = CallEvent(callerId, known, LocalTime.parse(time), day, previousAttempt, now)

    // --- T140: call rules -------------------------------------------------------------

    @Test
    fun withNoRulesCallsAreAllowed() {
        val decision = CallRuleEngine(store).decide(event())
        assertEquals(CallAction.ALLOW, decision.action)
        assertNull(decision.ruleId)
    }

    @Test
    fun unknownCallersCanBeRejected() {
        val engine = CallRuleEngine(store)
        assertNull(engine.addRule("Block strangers", listOf(CallCondition.UnknownCaller), CallAction.REJECT))
        assertEquals(CallAction.REJECT, engine.decide(event(known = false)).action)
        assertEquals(CallAction.ALLOW, engine.decide(event(callerId = "friend", known = true)).action)
    }

    @Test
    fun higherPriorityRulesWin() {
        val engine = CallRuleEngine(store)
        engine.addRule("Mute everyone", listOf(CallCondition.Weekdays(DayOfWeek.entries.toSet())), CallAction.MUTE, priority = 1)
        engine.addRule(
            "Reject unknown",
            listOf(CallCondition.UnknownCaller),
            CallAction.REJECT,
            priority = 10,
        )
        assertEquals(CallAction.REJECT, engine.decide(event()).action)
        assertEquals(CallAction.MUTE, engine.decide(event(known = true)).action)
    }

    @Test
    fun scheduledBehaviourIsHonoured() {
        val engine = CallRuleEngine(store)
        engine.addRule(
            "Mute work nights",
            listOf(
                CallCondition.TimeWindow(LocalTime.parse("21:00"), LocalTime.parse("08:00")),
                CallCondition.Weekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)),
            ),
            CallAction.MUTE,
        )
        assertEquals(CallAction.MUTE, engine.decide(event(time = "22:30")).action)
        assertEquals(CallAction.ALLOW, engine.decide(event(time = "12:00")).action)
        assertEquals(CallAction.ALLOW, engine.decide(event(time = "22:30", day = DayOfWeek.SATURDAY)).action)
    }

    @Test
    fun repeatedCallsWithinTheWindowCanBeAllowedThroughAStricterRule() {
        val engine = CallRuleEngine(store)
        engine.addRule("Reject strangers", listOf(CallCondition.UnknownCaller), CallAction.REJECT, priority = 1)
        engine.addRule(
            "Urgent second call gets through",
            listOf(
                CallCondition.UnknownCaller,
                CallCondition.RepeatCaller(withinMillis = 5 * 60_000L),
            ),
            CallAction.ALLOW,
            priority = 10,
        )
        assertEquals(CallAction.ALLOW, engine.decide(event(previousAttempt = now - 60_000)).action)
        assertEquals(CallAction.REJECT, engine.decide(event(previousAttempt = null)).action)
        assertEquals(CallAction.REJECT, engine.decide(event(previousAttempt = now - 10 * 60_000)).action)
    }

    @Test
    fun rulesCanBeDisabledAndRemoved() {
        val engine = CallRuleEngine(store)
        engine.addRule("Reject strangers", listOf(CallCondition.UnknownCaller), CallAction.REJECT)
        val rule = engine.rules().single()
        assertTrue(engine.setEnabled(rule.id, false))
        assertEquals(CallAction.ALLOW, engine.decide(event()).action)
        assertTrue(engine.removeRule(rule.id))
        assertTrue(engine.rules().isEmpty())
    }

    @Test
    fun aRuleNeedsAConditionAndName() {
        val engine = CallRuleEngine(store)
        assertTrue(engine.addRule("  ", listOf(CallCondition.UnknownCaller), CallAction.REJECT) != null)
        assertTrue(engine.addRule("No conditions", emptyList(), CallAction.REJECT) != null)
    }

    @Test
    fun rulesSurviveRecreation() {
        CallRuleEngine(store).addRule("Reject", listOf(CallCondition.UnknownCaller), CallAction.REJECT)
        assertEquals(1, CallRuleEngine(store).rules().size)
    }

    // --- T141: call history -----------------------------------------------------------

    @Test
    fun historyIsSearchableLocally() {
        val history = CallHistoryStore(store, { now })
        history.add("4915", CallDirection.MISSED, now - 1_000, 0)
        history.add("4915", CallDirection.INCOMING, now, 42)
        history.add("1203", CallDirection.OUTGOING, now - 2_000, 10)
        assertEquals(3, history.count())
        assertEquals(2, history.byContact("4915").size)
        assertEquals(1, history.search("1203").size)
        assertTrue(history.all().first().startedAtMillis >= history.all().last().startedAtMillis)
    }

    @Test
    fun notesCanBeAttachedAndSearched() {
        val history = CallHistoryStore(store, { now })
        val entry = history.add("4915", CallDirection.INCOMING, now, 42)
        assertTrue(history.setNote(entry.id, "Invoice call"))
        assertEquals(1, history.search("invoice").size)
        assertFalse(history.setNote("missing", "x"))
        assertEquals(1, history.count())
    }

    @Test
    fun negativeDurationsAreClamped() {
        val history = CallHistoryStore(store, { now })
        assertEquals(0, history.add("x", CallDirection.MISSED, now, -5).durationSeconds)
    }

    // --- T142: recordings -------------------------------------------------------------

    @Test
    fun recordingsCanBeNamedNotedSearchedAndArchived() {
        val library = RecordingLibrary(store, { now })
        val entry = library.add("call-2026-03-10.m4a", now - 1_000, 120)!!
        assertTrue(library.rename(entry.id, "Client call.m4a"))
        assertTrue(library.setNote(entry.id, "About the contract"))
        assertTrue(library.setArchived(entry.id, true))
        assertEquals(1, library.search("contract").size)
        assertEquals(1, library.search("client").size)
        assertTrue(library.all().single().archived)
        assertTrue(library.remove(entry.id))
        assertTrue(library.all().isEmpty())
    }

    @Test
    fun pathLikeNamesAreRejected() {
        val library = RecordingLibrary(store, { now })
        assertNull(library.add("../escape.m4a", now, 1))
        assertNull(library.add("folder/name.m4a", now, 1))
        assertNull(library.add("back\\slash.m4a", now, 1))
        val entry = library.add("safe.m4a", now, 1)!!
        assertFalse(library.rename(entry.id, "../oops.m4a"))
    }

    @Test
    fun theLegalReminderIsAlwaysAvailable() {
        val notice = RecordingLibrary.LEGAL_NOTICE
        assertTrue(notice.contains("laws vary by jurisdiction"))
    }
}
