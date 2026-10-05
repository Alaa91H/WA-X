package com.wmods.wppenhacer.privacy

import com.wmods.wppenhacer.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class PrivacyScheduleTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 1_700_000_000_000L
    }

    private fun engine() = PrivacyScheduleEngine(store, { now })

    private fun context(
        time: String = "23:00",
        day: DayOfWeek = DayOfWeek.MONDAY,
        charging: Boolean = false,
        wifi: Boolean = false,
        bluetooth: Boolean = false,
        events: Set<String> = emptySet(),
    ) = ScheduleContext(LocalTime.parse(time), day, charging, wifi, bluetooth, events)

    private fun nodeContext() = context(time = "23:00")

    @Test
    fun aTimeWindowCrossingMidnightMatchesBothSides() {
        val trigger = PrivacyTrigger.TimeWindow(LocalTime.parse("22:00"), LocalTime.parse("07:00"))
        assertEquals(true, trigger.matches(context(time = "23:30")))
        assertEquals(true, trigger.matches(context(time = "06:00")))
        assertEquals(false, trigger.matches(context(time = "12:00")))
        assertEquals(false, trigger.matches(context(time = "07:00")))
    }

    @Test
    fun everyTriggerOfARuleMustMatch() {
        val weekdayOnly = PrivacyTrigger.Weekdays(setOf(DayOfWeek.MONDAY))
        val charging = PrivacyTrigger.Charging(true)
        assertTrue(weekdayOnly.matches(context(day = DayOfWeek.MONDAY)))
        assertFalse(weekdayOnly.matches(context(day = DayOfWeek.TUESDAY)))
        assertTrue(charging.matches(context(charging = true)))
        assertFalse(charging.matches(context(charging = false)))
    }

    @Test
    fun taskerEventsCanTriggerAProfile() {
        val trigger = PrivacyTrigger.TaskerEvent("Night_Start")
        assertTrue(trigger.matches(context(events = setOf("Night_Start"))))
        assertFalse(trigger.matches(context(events = setOf("Morning"))))
    }

    @Test
    fun aRuleWithAllTriggersMatchingSwitchesTheProfile() {
        val engine = engine()
        val added =
            engine.addRule(
                name = "Night on weekdays",
                profileId = BuiltInPrivacyProfiles.NIGHT,
                triggers =
                    listOf(
                        PrivacyTrigger.TimeWindow(LocalTime.parse("22:00"), LocalTime.parse("07:00")),
                        PrivacyTrigger.Weekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)),
                    ),
            )
        assertTrue(added.isSuccess)
        val decision = engine.evaluate(nodeContext(), activeProfileId = BuiltInPrivacyProfiles.NORMAL)
        assertTrue(decision is PrivacyScheduleDecision.SwitchTo)
        assertEquals(BuiltInPrivacyProfiles.NIGHT, (decision as PrivacyScheduleDecision.SwitchTo).profileId)
    }

    @Test
    fun automaticSwitchesAreRecordedLocally() {
        val engine = engine()
        engine.addRule(
            "Ghost at night",
            BuiltInPrivacyProfiles.GHOST,
            listOf(PrivacyTrigger.TimeWindow(LocalTime.parse("22:00"), LocalTime.parse("07:00"))),
        )
        engine.evaluate(nodeContext(), activeProfileId = BuiltInPrivacyProfiles.NORMAL)
        val record = engine.history().single()
        assertTrue(record.automatic)
        assertEquals(BuiltInPrivacyProfiles.NORMAL, record.fromProfileId)
        assertEquals(BuiltInPrivacyProfiles.GHOST, record.toProfileId)
        assertEquals(now, record.timestampMillis)
    }

    @Test
    fun noSwitchIsRecordedWhenTheProfileAlreadyApplies() {
        val engine = engine()
        engine.addRule("Ghost at night", BuiltInPrivacyProfiles.GHOST, listOf(PrivacyTrigger.Charging(true)))
        val decision = engine.evaluate(context(charging = true), activeProfileId = BuiltInPrivacyProfiles.GHOST)
        assertTrue(decision is PrivacyScheduleDecision.KeepCurrent)
        assertTrue(engine.history().isEmpty())
    }

    @Test
    fun rulesAreEvaluatedInDeclarationOrderAndFirstMatchWins() {
        val engine = engine()
        engine.addRule("First", BuiltInPrivacyProfiles.GHOST, listOf(PrivacyTrigger.Charging(true)))
        engine.addRule("Second", BuiltInPrivacyProfiles.NIGHT, listOf(PrivacyTrigger.Charging(true)))
        val decision = engine.evaluate(context(charging = true), activeProfileId = BuiltInPrivacyProfiles.NORMAL)
        assertEquals(BuiltInPrivacyProfiles.GHOST, (decision as PrivacyScheduleDecision.SwitchTo).profileId)
    }

    @Test
    fun aDisabledRuleDoesNotFire() {
        val engine = engine()
        val rule = (engine.addRule("Off", BuiltInPrivacyProfiles.NIGHT, listOf(PrivacyTrigger.Wifi(true))) as PrivacyOpResult.Success).value
        assertTrue(engine.setRuleEnabled(rule.id, false))
        val decision = engine.evaluate(context(wifi = true), activeProfileId = BuiltInPrivacyProfiles.NORMAL)
        assertTrue(decision is PrivacyScheduleDecision.KeepCurrent)
    }

    @Test
    fun aManualOverrideBeatsAutomaticRulesAndIsRecorded() {
        val engine = engine()
        engine.addRule(
            "Ghost",
            BuiltInPrivacyProfiles.GHOST,
            listOf(PrivacyTrigger.TimeWindow(LocalTime.parse("22:00"), LocalTime.parse("07:00"))),
        )
        assertTrue(engine.manualOverride(BuiltInPrivacyProfiles.WORK, durationMillis = 60_000))
        val decision = engine.evaluate(nodeContext(), activeProfileId = BuiltInPrivacyProfiles.NORMAL)
        assertTrue(decision is PrivacyScheduleDecision.ManualOverride)
        assertEquals(BuiltInPrivacyProfiles.WORK, (decision as PrivacyScheduleDecision.ManualOverride).profileId)
        assertFalse(engine.history().single().automatic)
    }

    @Test
    fun aManualOverrideExpiresAndRulesTakeOverAgain() {
        val engine = engine()
        engine.manualOverride(BuiltInPrivacyProfiles.WORK, durationMillis = 1_000)
        now += 1_001
        assertNull(engine.manualOverrideProfileId())
        engine.addRule("Ghost", BuiltInPrivacyProfiles.GHOST, listOf(PrivacyTrigger.Charging(true)))
        val decision = engine.evaluate(context(charging = true), activeProfileId = BuiltInPrivacyProfiles.NORMAL)
        assertTrue(decision is PrivacyScheduleDecision.SwitchTo)
    }

    @Test
    fun aRuleNeedsAKnownProfileAndAtLeastOneTrigger() {
        val unknownProfileEngine = PrivacyScheduleEngine(store, { now }, profileExists = { it == BuiltInPrivacyProfiles.NIGHT })
        assertTrue(unknownProfileEngine.addRule("X", "missing.profile", listOf(PrivacyTrigger.Wifi(true))) is PrivacyOpResult.Rejected)
        assertTrue(engine().addRule("X", BuiltInPrivacyProfiles.NIGHT, emptyList()) is PrivacyOpResult.Rejected)
    }

    @Test
    fun rulesAndHistorySurviveARecreation() {
        val first = engine()
        first.addRule("Persisted", BuiltInPrivacyProfiles.GHOST, listOf(PrivacyTrigger.Charging(true)))
        first.evaluate(context(charging = true), activeProfileId = BuiltInPrivacyProfiles.NORMAL)

        val second = engine()
        assertEquals(1, second.rules().size)
        assertEquals(1, second.history().size)
    }

    @Test
    fun moveRuleChangesWhichRuleWins() {
        val engine = engine()
        val first =
            (
                engine.addRule(
                    "First",
                    BuiltInPrivacyProfiles.GHOST,
                    listOf(PrivacyTrigger.Charging(true)),
                ) as PrivacyOpResult.Success
            ).value
        engine.addRule("Second", BuiltInPrivacyProfiles.NIGHT, listOf(PrivacyTrigger.Charging(true)))
        assertTrue(engine.moveRule(first.id, 1))
        val decision = engine.evaluate(context(charging = true), activeProfileId = BuiltInPrivacyProfiles.NORMAL)
        assertEquals(BuiltInPrivacyProfiles.NIGHT, (decision as PrivacyScheduleDecision.SwitchTo).profileId)
    }

    @Test
    fun removingARuleIsReported() {
        val engine = engine()
        assertFalse(engine.removeRule("missing"))
        val rule = (engine.addRule("A", BuiltInPrivacyProfiles.NIGHT, listOf(PrivacyTrigger.Wifi(true))) as PrivacyOpResult.Success).value
        assertTrue(engine.removeRule(rule.id))
        assertTrue(engine.rules().isEmpty())
    }
}
