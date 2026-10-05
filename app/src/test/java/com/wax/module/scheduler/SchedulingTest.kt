package com.wax.module.scheduler

import com.wax.module.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulingTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 1_700_000_000_000L
    private val zone = ZoneId.of("UTC")

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 1_700_000_000_000L
    }

    private fun engine() = ScheduledMessageEngine(store, { now }, { zone })

    // --- T91: scheduling --------------------------------------------------------------

    @Test
    fun aMessageCanBeScheduledAndBecomesDue() {
        val engine = engine()
        val due = now + 60_000
        val outcome = engine.schedule("chat", "see you", due)
        assertTrue(outcome is ScheduleOutcome.Scheduled)
        assertTrue(engine.due().isEmpty())
        now = due
        val dueMessage = engine.due().single()
        assertEquals("see you", dueMessage.text)
        assertEquals(ScheduledState.PENDING, dueMessage.state)
    }

    @Test
    fun anIdenticalPendingMessageIsNotDuplicated() {
        val engine = engine()
        val due = now + 60_000
        val first = engine.schedule("chat", "same", due)
        val second = engine.schedule("chat", "same", due)
        assertTrue(first is ScheduleOutcome.Scheduled)
        assertTrue(second is ScheduleOutcome.Duplicate)
        assertEquals(1, engine.pending().size)
    }

    @Test
    fun theSameTextAtADifferentTimeIsNotADuplicate() {
        val engine = engine()
        engine.schedule("chat", "same", now + 60_000)
        assertTrue(engine.schedule("chat", "same", now + 120_000) is ScheduleOutcome.Scheduled)
    }

    @Test
    fun unusableSchedulesAreRejected() {
        val engine = engine()
        assertTrue(engine.schedule("", "text", now + 1) is ScheduleOutcome.Rejected)
        assertTrue(engine.schedule("chat", "  ", now + 1) is ScheduleOutcome.Rejected)
        assertTrue(engine.schedule("chat", "text", 0) is ScheduleOutcome.Rejected)
    }

    @Test
    fun aFailedSendRetriesWithBackoffThenGivesUp() {
        val engine = engine()
        val due = now + 1_000
        val message = (engine.schedule("chat", "text", due) as ScheduleOutcome.Scheduled).message
        now = due
        assertTrue(engine.markFailed(message.id, "network"))
        val pending = engine.pending().single()
        assertEquals(1, pending.attempts)
        assertTrue(pending.nextAttemptAtMillis > now)
        assertTrue(engine.due().isEmpty())

        now = pending.nextAttemptAtMillis
        assertTrue(engine.markFailed(message.id, "network"))
        now = engine.pending().single().nextAttemptAtMillis
        assertTrue(engine.markFailed(message.id, "network"))
        assertTrue(engine.pending().isEmpty())
        assertEquals(ScheduledState.FAILED, engine.history().single().state)
    }

    @Test
    fun cancellingMovesTheMessageToHistory() {
        val engine = engine()
        val message = (engine.schedule("chat", "text", now + 1_000) as ScheduleOutcome.Scheduled).message
        assertTrue(engine.cancel(message.id))
        assertFalse(engine.cancel(message.id))
        assertEquals(ScheduledState.CANCELLED, engine.history().single().state)
    }

    @Test
    fun aPendingMessageSurvivesARecreation() {
        val due = now + 5_000
        engine().schedule("chat", "persisted", due)
        val reloaded = engine()
        assertEquals(1, reloaded.pending().size)
        now = due
        assertEquals("persisted", reloaded.due().single().text)
    }

    // --- T92: recurrence --------------------------------------------------------------

    @Test
    fun aDailyRecurrenceAdvancesByOneDay() {
        val recurrence = Recurrence(RecurrenceKind.DAILY)
        val start = ZonedDateTime.of(2026, 3, 10, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val next = recurrence.nextOccurrence(start, zone)
        val nextTime = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next!!), zone)
        assertEquals(11, nextTime.dayOfMonth)
        assertEquals(8, nextTime.hour)
    }

    @Test
    fun weekdaysSkipTheWeekend() {
        val recurrence = Recurrence(RecurrenceKind.WEEKDAYS)
        // Friday 2026-03-13 08:00 UTC.
        val friday = ZonedDateTime.of(2026, 3, 13, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val monday =
            ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(recurrence.nextOccurrence(friday, zone)!!),
                zone,
            )
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(16, monday.dayOfMonth)
    }

    @Test
    fun monthlyClampsToTheMonthsLength() {
        val recurrence = Recurrence(RecurrenceKind.MONTHLY, dayOfMonth = 31)
        // January 31 -> February has 28 days in 2026.
        val january = ZonedDateTime.of(2026, 1, 31, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val february =
            ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(recurrence.nextOccurrence(january, zone)!!),
                zone,
            )
        assertEquals(2, february.monthValue)
        assertEquals(28, february.dayOfMonth)
    }

    @Test
    fun aWeeklyRecurrenceNeedsADay() {
        assertTrue(Recurrence(RecurrenceKind.WEEKLY).validate().isNotEmpty())
        assertTrue(Recurrence(RecurrenceKind.WEEKLY, daysOfWeek = setOf(DayOfWeek.SUNDAY)).validate().isEmpty())
    }

    @Test
    fun aCustomRecurrenceNeedsASaneInterval() {
        assertTrue(Recurrence(RecurrenceKind.CUSTOM).validate().isNotEmpty())
        assertTrue(Recurrence(RecurrenceKind.CUSTOM, intervalMillis = 500).validate().isNotEmpty())
        assertTrue(Recurrence(RecurrenceKind.CUSTOM, intervalMillis = 90 * 60 * 1000L).validate().isEmpty())
    }

    @Test
    fun aRecurringSendReschedulesItselfAndLeavesATrail() {
        val engine = engine()
        val due = now + 1_000
        val message =
            (
                engine.schedule("chat", "daily report", due, recurrence = Recurrence(RecurrenceKind.DAILY))
                    as ScheduleOutcome.Scheduled
            ).message
        now = due
        assertTrue(engine.markSent(message.id))
        val pending = engine.pending().single()
        assertTrue("the recurring message stays pending", pending.nextAttemptAtMillis > due)
        assertEquals(0, pending.attempts)
        assertEquals(ScheduledState.SENT, engine.history().single().state)
    }

    @Test
    fun anAbsoluteDueTimeIsNotMovedByATimezoneChange() {
        val engine = ScheduledMessageEngine(store, { now }, { ZoneId.of("UTC") })
        val due = ZonedDateTime.of(2026, 3, 10, 8, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        engine.schedule("chat", "text", due, recurrence = Recurrence(RecurrenceKind.DAILY))

        // Reopen in a different zone: the stored absolute time must be untouched.
        val moved = ScheduledMessageEngine(store, { now }, { ZoneId.of("Asia/Riyadh") })
        assertEquals(due, moved.pending().single().nextAttemptAtMillis)
    }

    // --- T93: undo send ---------------------------------------------------------------

    @Test
    fun aQueuedSendCanBeCancelledBeforeItIsDispatched() {
        val queue = UndoSendQueue(store, { now })
        val send = queue.enqueue("chat", "oops", UndoDelay.FIVE)!!
        assertEquals(now + 5_000, send.sendAtMillis)
        assertTrue(queue.due().isEmpty())
        assertTrue(queue.cancel(send.id))
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun anUncancelledSendBecomesDueAndCanBeDispatched() {
        val queue = UndoSendQueue(store, { now })
        val send = queue.enqueue("chat", "hello", UndoDelay.THREE)!!
        now += 3_000
        assertEquals(send.id, queue.due().single().id)
        assertTrue(queue.markDispatched(send.id))
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun theQueueSurvivesARestart() {
        UndoSendQueue(store, { now }).enqueue("chat", "persisted", UndoDelay.TEN)
        val reloaded = UndoSendQueue(store, { now })
        assertEquals(1, reloaded.pending().size)
    }

    @Test
    fun onlyTheThreeAllowedDelaysExist() {
        assertEquals(listOf(3, 5, 10), UndoDelay.entries.map { it.seconds })
    }

    // --- T94: templates ---------------------------------------------------------------

    @Test
    fun templatesAreGroupedAndCanBeEdited() {
        val templates = ReplyTemplateStore(store, { now })
        val saved = templates.save(null, "Work", "On my way") as TemplateSaveResult.Success
        templates.save(null, "Work", "Running late")
        templates.save(null, "Personal", "Happy birthday!")
        assertEquals(listOf("Work", "Personal"), templates.categories())
        assertEquals(2, templates.templatesFor("work").size)

        val updated = templates.save(saved.template.id, "Work", "On my way now")
        assertTrue(updated is TemplateSaveResult.Success)
        assertEquals("On my way now", (updated as TemplateSaveResult.Success).template.text)
        assertTrue(templates.delete(saved.template.id))
        assertEquals(2, templates.templates().size)
    }

    @Test
    fun aBlankTemplateIsRejected() {
        val templates = ReplyTemplateStore(store, { now })
        assertTrue(templates.save(null, "Work", "   ") is TemplateSaveResult.Rejected)
        assertTrue(templates.save(null, " ", "text") is TemplateSaveResult.Rejected)
    }

    @Test
    fun allowlistedVariablesExpandOnce() {
        val context =
            TemplateContext(
                contactName = "Sam",
                myName = "Ali",
                now = ZonedDateTime.of(2026, 3, 10, 14, 30, 0, 0, zone),
            )
        val rendered = TemplateRenderer.render("Hi {name}, see you on {date} at {time} ({weekday})", context)
        assertTrue(rendered.isComplete)
        assertEquals("Hi Sam, see you on 2026-03-10 at 14:30 (Tuesday)", rendered.text)
    }

    @Test
    fun unknownValuesStayVisibleInsteadOfBecomingEmpty() {
        val rendered = TemplateRenderer.render("Hi {name}", TemplateContext())
        assertFalse(rendered.isComplete)
        assertEquals("Hi {name}", rendered.text)
        assertEquals(setOf(TemplateVariable.CONTACT_NAME), rendered.unresolved)
    }

    @Test
    fun aValueContainingATokenIsNotExpandedAgain() {
        val context = TemplateContext(contactName = "{date}", now = ZonedDateTime.now(zone))
        val rendered = TemplateRenderer.render("Hi {name}", context)
        assertEquals("Hi {date}", rendered.text)
    }

    @Test
    fun unknownTokensAreReportedButAllowed() {
        val unknown = TemplateRenderer.unknownTokens("Hello {name} {bogus} {another_one}")
        assertEquals(listOf("{bogus}", "{another_one}"), unknown)
    }
}
